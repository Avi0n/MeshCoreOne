import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("ContactService Watchlist membership", .timeLimit(.minutes(1)))
struct ContactServiceWatchlistTests {
  @Test
  func `add confirms favorite before saving the mark`() async throws {
    let fixture = try await Fixture.make()
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold {
      await gate.park()
    }

    let add = Task {
      try await fixture.service.setWatchlistMembership(
        contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
      )
    }
    #expect(await waitUntilParked(gate))
    let whileFavoritePending = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(!whileFavoritePending.isMyNode)
    #expect(!whileFavoritePending.isFavorite)
    #expect(await fixture.session.changeContactFlagsCalls.count == 1)

    gate.release()
    let saved = try await add.value
    #expect(saved.isFavorite)
    #expect(saved.isMyNode)
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.isFavorite)
    #expect(stored.isMyNode)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
    #expect(await fixture.coordinator.contactsVersion == 1)
  }

  @Test
  func `add marks a repeater that is already favorite`() async throws {
    let fixture = try await Fixture.make(isFavorite: true)
    let saved = try await fixture.service.setWatchlistMembership(
      contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
    )
    #expect(saved.isFavorite)
    #expect(saved.isMyNode)
    #expect(await fixture.session.changeContactFlagsCalls.count == 1)
  }

  @Test
  func `a failed favorite write does not mark`() async throws {
    let fixture = try await Fixture.make()
    await fixture.session.setChangeContactFlagsHold { throw MeshCoreError.timeout }

    await #expect {
      try await fixture.service.setWatchlistMembership(
        contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
      )
    } throws: { error in
      guard case ContactServiceError.sessionError(.timeout) = error else { return false }
      return true
    }

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(!stored.isFavorite)
    #expect(!stored.isMyNode)
  }

  @Test
  func `missing and mismatched favorite readback leave the mark off`() async throws {
    let missing = try await Fixture.make(seedRadio: false)
    await #expect {
      try await missing.service.setWatchlistMembership(
        contactID: missing.contact.id, radioID: missing.radioID, isMember: true
      )
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }
    #expect(try await missing.store.fetchContact(id: missing.contact.id)?.isMyNode == false)

    let mismatched = try await Fixture.make(seedRadio: false)
    await mismatched.session.setStubbedContact(
      meshRepeater(mismatched.contact.publicKey, flags: []),
      for: mismatched.contact.publicKey
    )
    await #expect {
      try await mismatched.service.setWatchlistMembership(
        contactID: mismatched.contact.id, radioID: mismatched.radioID, isMember: true
      )
    } throws: { error in
      guard case ContactServiceError.invalidResponse = error else { return false }
      return true
    }
    let stored = try #require(try await mismatched.store.fetchContact(id: mismatched.contact.id))
    #expect(!stored.isFavorite)
    #expect(!stored.isMyNode)
  }

  @Test(arguments: [false, true])
  func `a failed or cancelled mark publishes the confirmed favorite`(cancelled: Bool) async throws {
    let fixture = try await Fixture.make()
    let events = fixture.coordinator.dataEvents()
    await fixture.store.setWatchlistSaveFaultInjection {
      if cancelled { throw CancellationError() }
      throw PersistenceStoreError.saveFailed("mark")
    }

    await #expect {
      try await fixture.service.setWatchlistMembership(
        contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
      )
    } throws: { error in
      if cancelled { return error is CancellationError }
      guard let storeError = error as? PersistenceStoreError, case .saveFailed = storeError else { return false }
      return true
    }

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.isFavorite)
    #expect(!stored.isMyNode)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
    #expect(await fixture.coordinator.contactsVersion == 1)

    fixture.coordinator.finishDataEvents()
    var contactChanges = 0
    for await event in events {
      if case .contactsChanged = event { contactChanges += 1 }
    }
    #expect(contactChanges == 1)
  }

  @Test
  func `a cancelled waiter does not mark or write favorite`() async throws {
    let fixture = try await Fixture.make()
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold {
      await gate.park()
    }
    let holder = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    #expect(await waitUntilParked(gate))

    let add = Task {
      try await fixture.service.setWatchlistMembership(
        contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
      )
    }
    #expect(await waitUntilWaiters(fixture.service, equal: 1))
    add.cancel()
    gate.release()

    _ = try await holder.value
    await #expect(throws: CancellationError.self) {
      try await add.value
    }
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.isFavorite)
    #expect(!stored.isMyNode)
    #expect(await fixture.session.changeContactFlagsCalls.count == 1)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `add and remove stay on the captured radio`() async throws {
    let fixture = try await Fixture.make()
    await #expect {
      try await fixture.service.setWatchlistMembership(
        contactID: fixture.contact.id, radioID: UUID(), isMember: true
      )
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)
    #expect(try await fixture.store.fetchContact(id: fixture.contact.id)?.isMyNode == false)

    _ = try await fixture.service.setWatchlistMembership(
      contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
    )
    let removed = try await fixture.service.setWatchlistMembership(
      contactID: fixture.contact.id, radioID: fixture.radioID, isMember: false
    )
    #expect(!removed.isMyNode)
    #expect(removed.isFavorite)
    #expect(await fixture.session.changeContactFlagsCalls.count == 1)

    _ = try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: false)
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(!stored.isFavorite)
    #expect(!stored.isMyNode)
  }

  @Test
  func `marked unfavorite is rejected from the service and from preferences`() async throws {
    let fixture = try await Fixture.make(isFavorite: true, isMyNode: true)
    await #expect {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: false)
    } throws: { error in
      guard case ContactServiceError.removeFromWatchlistBeforeUnfavorite = error else { return false }
      return true
    }
    await #expect {
      try await fixture.service.updateContactPreferences(contactID: fixture.contact.id, isFavorite: false)
    } throws: { error in
      guard case ContactServiceError.removeFromWatchlistBeforeUnfavorite = error else { return false }
      return true
    }
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.isFavorite)
    #expect(stored.isMyNode)
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)

    _ = try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    #expect(try await fixture.store.fetchContact(id: fixture.contact.id)?.isMyNode == true)
  }

  @Test
  func `add admitted first makes a later unfavorite and preference clear fail`() async throws {
    let fixture = try await Fixture.make()
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold {
      await gate.park()
    }
    let add = Task {
      try await fixture.service.setWatchlistMembership(
        contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
      )
    }
    #expect(await waitUntilParked(gate))

    let unfavorite = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: false)
    }
    let preferences = Task {
      try await fixture.service.updateContactPreferences(contactID: fixture.contact.id, isFavorite: false)
    }
    #expect(await waitUntilWaiters(fixture.service, equal: 2))
    gate.release()

    _ = try await add.value
    await #expect(throws: ContactServiceError.self) { try await unfavorite.value }
    await #expect(throws: ContactServiceError.self) { try await preferences.value }
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.isFavorite)
    #expect(stored.isMyNode)
  }

  @Test
  func `unfavorite admitted first still lets a later add mark`() async throws {
    let fixture = try await Fixture.make(isFavorite: true)
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold {
      if gate.consumeFirstPark() {
        await gate.park()
      }
    }
    let unfavorite = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: false)
    }
    #expect(await waitUntilParked(gate))
    let add = Task {
      try await fixture.service.setWatchlistMembership(
        contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
      )
    }
    #expect(await waitUntilWaiters(fixture.service, equal: 1))
    gate.release()

    _ = try await unfavorite.value
    let saved = try await add.value
    #expect(saved.isFavorite)
    #expect(saved.isMyNode)
  }

  @Test
  func `add admitted first keeps the mark and saved firmware through the later sync`() async throws {
    let fixture = try await Fixture.make()
    _ = try await fixture.store.updateWatchlistFirmware(
      contactID: fixture.contact.id, radioID: fixture.radioID, version: "9.9.9"
    )
    let gate = SuspendGate()
    await fixture.session.setStubbedReportsNoTotal(true)
    await fixture.session.setStubbedContacts([
      meshRepeater(fixture.contact.publicKey, flags: [.favorite]),
    ])
    await fixture.session.setChangeContactFlagsHold {
      await gate.park()
    }
    let add = Task {
      try await fixture.service.setWatchlistMembership(
        contactID: fixture.contact.id, radioID: fixture.radioID, isMember: true
      )
    }
    #expect(await waitUntilParked(gate))
    let sync = Task {
      try await fixture.service.syncContacts(radioID: fixture.radioID)
    }
    #expect(await waitUntilWaiters(fixture.service, equal: 1))
    #expect(await fixture.session.getContactsInvocations.isEmpty)
    gate.release()

    _ = try await add.value
    _ = try await sync.value
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.isMyNode)
    #expect(stored.isFavorite)
    #expect(stored.myNodeFirmwareVersion == "9.9.9")
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }
}

private struct Fixture {
  let radioID: UUID
  let contact: ContactDTO
  let store: PersistenceStore
  let session: MockMeshCoreSession
  let service: ContactService
  let coordinator: SyncCoordinator

  static func make(
    isFavorite: Bool = false,
    isMyNode: Bool = false,
    seedRadio: Bool = true
  ) async throws -> Fixture {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: Data(repeating: 0x44, count: 32),
      name: "Ridge",
      typeRawValue: ContactType.repeater.rawValue,
      flags: isFavorite ? ContactFlags.favorite.rawValue : 0,
      isFavorite: isFavorite,
      isMyNode: isMyNode
    )
    try await store.saveContact(contact)
    let session = MockMeshCoreSession()
    if seedRadio {
      let flags: ContactFlags = isFavorite ? [.favorite] : []
      await session.seedRadioContact(meshRepeater(contact.publicKey, flags: flags))
    }
    let coordinator = SyncCoordinator()
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: coordinator,
      cleanupCoordinator: nil
    )
    return Fixture(
      radioID: radioID, contact: contact, store: store, session: session, service: service, coordinator: coordinator
    )
  }
}

private func meshRepeater(_ key: Data, flags: ContactFlags) -> MeshContact {
  MeshContact(
    id: key.uppercaseHexString(),
    publicKey: key,
    type: .repeater,
    flags: flags,
    outPathLength: 0,
    outPath: Data(),
    advertisedName: "Ridge",
    lastAdvertisement: Date(timeIntervalSince1970: 0),
    latitude: 0,
    longitude: 0,
    lastModified: Date(timeIntervalSince1970: 0)
  )
}

private func waitUntilWaiters(_ service: ContactService, equal expected: Int) async -> Bool {
  let deadline = ContinuousClock.now + .seconds(2)
  while await service.contactOperationWaiterCountForTesting() != expected {
    if ContinuousClock.now > deadline { return false }
    await Task.yield()
  }
  return true
}

private func waitUntilParked(_ gate: SuspendGate) async -> Bool {
  let deadline = ContinuousClock.now + .seconds(2)
  while !gate.isParked {
    if ContinuousClock.now > deadline { return false }
    await Task.yield()
  }
  return true
}

private final class SuspendGate: @unchecked Sendable {
  private let lock = NSLock()
  private var continuation: CheckedContinuation<Void, Never>?
  private var parked = false
  private var didPark = false

  var isParked: Bool {
    lock.lock()
    defer { lock.unlock() }
    return parked
  }

  func park() async {
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      lock.lock()
      parked = true
      self.continuation = continuation
      lock.unlock()
    }
  }

  /// The first caller parks. Later flag writes in the same test run immediately.
  func consumeFirstPark() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    if didPark { return false }
    didPark = true
    return true
  }

  func release() {
    lock.lock()
    let continuation = continuation
    self.continuation = nil
    parked = false
    lock.unlock()
    continuation?.resume()
  }
}
