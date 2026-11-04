import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Contact favourite operation order", .timeLimit(.minutes(1)))
struct ContactServiceFavoriteOrderingTests {
  private func publicKey(_ byte: UInt8) -> Data {
    Data(repeating: byte, count: ProtocolLimits.publicKeySize)
  }

  private func meshContact(
    _ keyByte: UInt8,
    name: String,
    flags: ContactFlags = []
  ) -> MeshContact {
    let key = publicKey(keyByte)
    return MeshContact(
      id: key.uppercaseHexString(),
      publicKey: key,
      type: .chat,
      flags: flags,
      outPathLength: 0,
      outPath: Data(),
      advertisedName: name,
      lastAdvertisement: Date(timeIntervalSince1970: 0),
      latitude: 0,
      longitude: 0,
      lastModified: Date(timeIntervalSince1970: 0)
    )
  }

  private func makeFixture(name: String = "Phone Row") async throws -> FavoriteOrderFixture {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: publicKey(0xAA),
      name: name,
      isFavorite: false
    )
    try await store.saveContact(contact)
    let session = MockMeshCoreSession()
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )
    return FavoriteOrderFixture(
      radioID: radioID,
      contact: contact,
      store: store,
      session: session,
      service: service
    )
  }

  private func waitUntilWaiters(_ service: ContactService, equal expected: Int) async {
    while await service.contactOperationWaiterCountForTesting() != expected {
      await Task.yield()
    }
  }

  @Test
  func `favourite admitted first makes the later sync read the committed radio value`() async throws {
    let fixture = try await makeFixture()
    let gate = SuspendGate()
    await fixture.session.setStubbedContacts([meshContact(0xAA, name: "Phone Row")])
    await fixture.session.setChangeContactFlagsHold { @Sendable in
      await gate.enter()
      await gate.waitForRelease()
    }
    await fixture.session.setStubbedContact(
      meshContact(0xAA, name: "Readback", flags: [.favorite]),
      for: fixture.contact.publicKey
    )

    let favoriteTask = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await gate.waitForEntry()

    let syncTask = Task {
      try await fixture.service.syncContacts(radioID: fixture.radioID)
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    #expect(await fixture.session.getContactsInvocations.isEmpty)

    await fixture.session.setStubbedContacts([
      meshContact(0xAA, name: "Committed Snapshot", flags: [.favorite])
    ])
    await gate.release()

    _ = try await favoriteTask.value
    _ = try await syncTask.value

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.name == "Committed Snapshot")
    #expect(stored.isFavorite)
    #expect(stored.flags & ContactFlags.favorite.rawValue == ContactFlags.favorite.rawValue)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `sync admitted first keeps the favourite command as the final value`() async throws {
    let fixture = try await makeFixture()
    await fixture.session.setStubbedContacts([meshContact(0xAA, name: "Radio Snapshot")])
    await fixture.session.setStubbedContact(
      meshContact(0xAA, name: "Readback", flags: [.favorite]),
      for: fixture.contact.publicKey
    )
    await fixture.session.holdNextGetContacts()

    let syncTask = Task {
      try await fixture.service.syncContacts(radioID: fixture.radioID)
    }
    await fixture.session.waitForGetContactsStart()

    let favoriteTask = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)

    await fixture.session.releaseGetContacts()
    _ = try await syncTask.value
    _ = try await favoriteTask.value

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.name == "Radio Snapshot")
    #expect(stored.isFavorite)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `a sync error releases the gate for the queued favourite`() async throws {
    let fixture = try await makeFixture()
    await fixture.session.setStubbedContact(
      meshContact(0xAA, name: "Readback", flags: [.favorite]),
      for: fixture.contact.publicKey
    )
    await fixture.session.holdNextGetContacts()

    let syncTask = Task {
      try await fixture.service.syncContacts(radioID: fixture.radioID)
    }
    await fixture.session.waitForGetContactsStart()

    let favoriteTask = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)

    await fixture.session.setGetContactsError(MeshCoreError.timeout)
    await fixture.session.releaseGetContacts()

    await #expect(throws: ContactServiceError.self) {
      _ = try await syncTask.value
    }
    let saved = try await favoriteTask.value
    #expect(saved.isFavorite)
    #expect(await fixture.session.changeContactFlagsCalls.count == 1)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `a cancelled queued favourite does no radio work and a later command completes`() async throws {
    let fixture = try await makeFixture()
    await fixture.session.setStubbedContacts([meshContact(0xAA, name: "Phone Row")])
    await fixture.session.setStubbedContact(
      meshContact(0xAA, name: "Readback", flags: [.favorite]),
      for: fixture.contact.publicKey
    )
    await fixture.session.holdNextGetContacts()

    let syncTask = Task {
      try await fixture.service.syncContacts(
        radioID: fixture.radioID,
        since: Date(timeIntervalSince1970: 100)
      )
    }
    await fixture.session.waitForGetContactsStart()

    let favoriteTask = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    favoriteTask.cancel()
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)

    await fixture.session.releaseGetContacts()
    _ = try await syncTask.value
    await #expect(throws: CancellationError.self) {
      _ = try await favoriteTask.value
    }
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)
    #expect(try await fixture.store.fetchContact(id: fixture.contact.id)?.isFavorite == false)

    let saved = try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    #expect(saved.isFavorite)
    #expect(await fixture.session.changeContactFlagsCalls.count == 1)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `a cancelled running sync stops before deleting an orphan and a later sync completes`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let orphanKey = publicKey(0xDD)
    _ = try await store.saveContact(
      radioID: radioID,
      from: ContactFrame(
        publicKey: orphanKey,
        type: .chat,
        flags: ContactFlags.favorite.rawValue,
        outPathLength: 0,
        outPath: Data(),
        name: "Orphan",
        lastAdvertTimestamp: 0,
        latitude: 0,
        longitude: 0,
        lastModified: 0
      )
    )
    let session = MockMeshCoreSession()
    await session.setStubbedContacts([])
    await session.setStubbedReportedTotal(0)
    await session.holdNextGetContacts()
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    let syncTask = Task {
      try await service.syncContacts(radioID: radioID)
    }
    await session.waitForGetContactsStart()
    syncTask.cancel()
    await session.releaseGetContacts()

    await #expect(throws: CancellationError.self) {
      _ = try await syncTask.value
    }
    #expect(try await store.fetchContact(radioID: radioID, publicKey: orphanKey) != nil)

    _ = try await service.syncContacts(radioID: radioID)
    #expect(try await store.fetchContact(radioID: radioID, publicKey: orphanKey) == nil)
    #expect(await service.contactOperationWaiterCountForTesting() == 0)
  }
}

private struct FavoriteOrderFixture {
  let radioID: UUID
  let contact: ContactDTO
  let store: PersistenceStore
  let session: MockMeshCoreSession
  let service: ContactService
}

private actor SuspendGate {
  private var hasEntered = false
  private var entryWaiters: [CheckedContinuation<Void, Never>] = []
  private var releaseWaiter: CheckedContinuation<Void, Never>?
  private var isReleased = false

  func enter() {
    hasEntered = true
    let waiters = entryWaiters
    entryWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  func waitForEntry() async {
    if hasEntered { return }
    await withCheckedContinuation { entryWaiters.append($0) }
  }

  func waitForRelease() async {
    if isReleased { return }
    await withCheckedContinuation { releaseWaiter = $0 }
  }

  func release() {
    isReleased = true
    releaseWaiter?.resume()
    releaseWaiter = nil
  }
}
