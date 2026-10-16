import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

/// Service-level coverage for `ContactService.syncContacts` after it was restructured to
/// batch-persist in a single transaction. Uses the real in-memory `PersistenceStore` so the
/// optimized `batchSaveContacts` override (not the protocol default) is exercised end to end.
@Suite("ContactService Sync Tests")
struct ContactServiceSyncTests {
  private func publicKey(_ byte: UInt8) -> Data {
    Data(repeating: byte, count: ProtocolLimits.publicKeySize)
  }

  private func meshContact(_ keyByte: UInt8, name: String, lastModified: Date = Date(timeIntervalSince1970: 0)) -> MeshContact {
    let key = publicKey(keyByte)
    return MeshContact(
      id: key.uppercaseHexString(),
      publicKey: key,
      type: .chat,
      flags: ContactFlags(rawValue: 0),
      outPathLength: 0,
      outPath: Data(),
      advertisedName: name,
      lastAdvertisement: Date(timeIntervalSince1970: 0),
      latitude: 0,
      longitude: 0,
      lastModified: lastModified
    )
  }

  private func contactFrame(_ keyByte: UInt8, name: String) -> ContactFrame {
    contactFrame(key: publicKey(keyByte), name: name)
  }

  private func contactFrame(key: Data, name: String) -> ContactFrame {
    ContactFrame(
      publicKey: key,
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: name,
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
  }

  @Test
  func `Full sync persists all device contacts and prunes locals not on device`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    // A stale local contact that the device no longer reports.
    _ = try await store.saveContact(radioID: radioID, from: contactFrame(0xDD, name: "Stale"))

    let session = MockMeshCoreSession()
    let lastModified = Date(timeIntervalSince1970: 1_700_000_000)
    await session.setStubbedContacts([
      meshContact(0xAA, name: "Alice", lastModified: lastModified),
      meshContact(0xBB, name: "Bob")
    ])

    let service = ContactService(session: session, dataStore: store, syncCoordinator: nil, cleanupCoordinator: nil)
    let result = try await service.syncContacts(radioID: radioID, since: nil)

    #expect(result.contactsReceived == 2)
    #expect(result.isIncremental == false)
    #expect(result.lastSyncTimestamp == UInt32(lastModified.timeIntervalSince1970))

    let stored = try await store.fetchContacts(radioID: radioID)
    #expect(Set(stored.map(\.name)) == ["Alice", "Bob"])
    // The stale contact was pruned on full sync.
    #expect(try await store.fetchContact(radioID: radioID, publicKey: publicKey(0xDD)) == nil)
  }

  @Test
  func `Full sync prunes true orphans but keeps the ZephCore V-contact`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)

    // The V-contact derived from the test device's self key is omitted from GET_CONTACTS
    // while clock-deferred or disabled, so a full sync must not treat it as a table orphan.
    let selfKey = Data(repeating: 0x01, count: ProtocolLimits.publicKeySize)
    let vKey = try #require(VContactIdentity.publicKey(forSelfPublicKey: selfKey))
    _ = try await store.saveContact(radioID: radioID, from: contactFrame(key: vKey, name: "vTestDevice"))
    // A genuine orphan the device no longer reports.
    _ = try await store.saveContact(radioID: radioID, from: contactFrame(0xDD, name: "Orphan"))

    let session = MockMeshCoreSession()
    await session.setStubbedContacts([meshContact(0xAA, name: "Alice")])

    let service = ContactService(session: session, dataStore: store, syncCoordinator: nil, cleanupCoordinator: nil)
    _ = try await service.syncContacts(radioID: radioID, since: nil)

    // The orphan is pruned; the V-contact survives.
    #expect(try await store.fetchContact(radioID: radioID, publicKey: publicKey(0xDD)) == nil)
    #expect(try await store.fetchContact(radioID: radioID, publicKey: vKey) != nil)
    let stored = try await store.fetchContacts(radioID: radioID)
    #expect(Set(stored.map(\.name)) == ["Alice", "vTestDevice"])
  }

  @Test
  func `Incremental sync upserts without pruning unseen locals`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    // A local contact not present in this incremental batch must survive.
    _ = try await store.saveContact(radioID: radioID, from: contactFrame(0xDD, name: "Existing"))

    let session = MockMeshCoreSession()
    await session.setStubbedContacts([meshContact(0xAA, name: "Alice")])

    let service = ContactService(session: session, dataStore: store, syncCoordinator: nil, cleanupCoordinator: nil)
    let result = try await service.syncContacts(radioID: radioID, since: Date(timeIntervalSince1970: 100))

    #expect(result.contactsReceived == 1)
    #expect(result.isIncremental == true)

    let stored = try await store.fetchContacts(radioID: radioID)
    #expect(Set(stored.map(\.name)) == ["Alice", "Existing"])
  }

  @Test
  func `Manual refresh waits for an in-flight advert delta sync`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(
      radioID: radioID,
      maxChannels: 8,
      lastContactSync: 1_704_067_200
    )
    let coordinator = SyncCoordinator()

    let session = MockMeshCoreSession()
    await session.setStubbedContacts([meshContact(0xAA, name: "Alice")])
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: coordinator,
      cleanupCoordinator: nil
    )

    let gated = GatedSyncContactService()
    let advertTask = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: radioID,
        dataStore: store,
        contactService: gated
      )
    }
    await gated.waitForSyncStart()

    let refreshTask = Task { try await service.syncContactsForRefresh(radioID: radioID) }

    try await Task.sleep(for: .milliseconds(200))
    #expect(
      await session.getContactsInvocations.isEmpty,
      "A manual refresh must not fetch while an advert delta sync holds the claim"
    )

    await gated.release()
    #expect(await advertTask.value == .synced)
    _ = try await refreshTask.value
    #expect(await session.getContactsInvocations.count == 1)
  }

  @Test
  func `Manual refresh claim is atomic so a racing advert delta returns busy`() async throws {
    // claimManualContactSync waits and sets the flag in one actor method so a racing
    // performAdvertContactSync either waits behind the claim or sees manual = true.
    // Separate wait and claim hops leave a gap where a delta can pass both.
    //
    // The refresh body is gated at getContacts so the test can observe the claim held
    // before racing a second advert. Without that barrier the refresh can finish (and
    // clear the flag) or not yet claim before the race is evaluated.
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(
      radioID: radioID,
      maxChannels: 8,
      lastContactSync: 1_704_067_200
    )
    let coordinator = SyncCoordinator()

    let session = MockMeshCoreSession()
    await session.setStubbedContacts([meshContact(0xAA, name: "Alice")])
    await session.holdNextGetContacts()
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: coordinator,
      cleanupCoordinator: nil
    )

    let gated = GatedSyncContactService()
    let firstAdvert = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: radioID,
        dataStore: store,
        contactService: gated
      )
    }
    await gated.waitForSyncStart()

    let refreshTask = Task { try await service.syncContactsForRefresh(radioID: radioID) }
    // Refresh is parked in the claim wait; release the first advert so the claim lands.
    await gated.release()
    #expect(await firstAdvert.value == .synced)

    // Claim + set manual finished, refresh body parked in getContacts.
    await session.waitForGetContactsStart()
    #expect(await session.isGetContactsHeld())

    // Claim is held for the whole refresh body. A new advert round must return .busy
    // rather than entering syncContacts and racing progress events.
    let secondAdvert = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: radioID,
      dataStore: store,
      contactService: MockContactService()
    )
    #expect(secondAdvert == .busy, "Manual claim must block advert delta for the whole refresh")

    await session.releaseGetContacts()
    _ = try await refreshTask.value
    #expect(await session.getContactsInvocations.count == 1)
  }

  @Test
  func `Manual refresh surfaces syncInterrupted when the advert claim wait times out`() async throws {
    // ContactService maps a timed-out claim to syncInterrupted so the refresh
    // spinner stops with an error rather than hanging or racing the advert stream.
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(
      radioID: radioID,
      maxChannels: 8,
      lastContactSync: 1_704_067_200
    )
    let coordinator = SyncCoordinator()
    await coordinator.setAdvertContactSyncWaitTimeoutOverride(.milliseconds(40))

    let session = MockMeshCoreSession()
    await session.setStubbedContacts([meshContact(0xAA, name: "Alice")])
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: coordinator,
      cleanupCoordinator: nil
    )

    let gated = GatedSyncContactService()
    let advertTask = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: radioID,
        dataStore: store,
        contactService: gated
      )
    }
    await gated.waitForSyncStart()

    var surfaced: ContactServiceError?
    do {
      _ = try await service.syncContactsForRefresh(radioID: radioID)
    } catch let error as ContactServiceError {
      surfaced = error
    } catch {
      Issue.record("Expected ContactServiceError.syncInterrupted, got \(error)")
    }

    guard case .syncInterrupted = surfaced else {
      Issue.record("Refresh must map wait timeout to syncInterrupted, got \(String(describing: surfaced))")
      await gated.release()
      _ = await advertTask.value
      return
    }
    #expect(
      await session.getContactsInvocations.isEmpty,
      "Timed-out refresh must not start a contact fetch"
    )

    await gated.release()
    _ = await advertTask.value
  }
}

/// Contact service stub that parks inside `syncContacts` until released, so a test can
/// hold an advert-driven delta sync open.
private actor GatedSyncContactService: ContactServiceProtocol {
  private var hasStarted = false
  private var startWaiters: [CheckedContinuation<Void, Never>] = []
  private var gate: CheckedContinuation<Void, Never>?
  private var isReleased = false

  func waitForSyncStart() async {
    if hasStarted { return }
    await withCheckedContinuation { startWaiters.append($0) }
  }

  func release() {
    isReleased = true
    gate?.resume()
    gate = nil
  }

  func syncContacts(radioID _: UUID, since _: Date?) async throws -> ContactSyncResult {
    hasStarted = true
    while !startWaiters.isEmpty {
      startWaiters.removeFirst().resume()
    }
    if !isReleased {
      await withCheckedContinuation { gate = $0 }
    }
    return ContactSyncResult(contactsReceived: 0, lastSyncTimestamp: 0, isIncremental: true)
  }
}
