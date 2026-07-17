import Foundation
@testable import MC1Services
import MeshCore
import Testing

// MARK: - Helpers

private enum AdvertisementServiceTestError: Error {
  case getContactFailed
  case deadlineExceeded(String)
}

private func makePublicKey(seed: UInt8) -> Data {
  Data((0..<ProtocolLimits.publicKeySize).map { UInt8(($0 &+ Int(seed)) & 0xFF) })
}

private func makeMeshContact(
  publicKey: Data,
  name: String = "Node",
  type: ContactType = .chat,
  outPathLength: UInt8 = 0,
  outPath: Data = Data()
) -> MeshContact {
  MeshContact(
    id: publicKey.hexString,
    publicKey: publicKey,
    type: type,
    flags: ContactFlags(rawValue: 0),
    outPathLength: outPathLength,
    outPath: outPath,
    advertisedName: name,
    lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
    latitude: 0,
    longitude: 0,
    lastModified: Date(timeIntervalSince1970: 1_700_000_100)
  )
}

private func makeContactFrame(
  publicKey: Data,
  name: String = "LocalContact",
  type: ContactType = .chat
) -> ContactFrame {
  ContactFrame(
    publicKey: publicKey,
    type: type,
    flags: 0,
    outPathLength: 0,
    outPath: Data(),
    name: name,
    lastAdvertTimestamp: 1_700_000_000,
    latitude: 0,
    longitude: 0,
    lastModified: 1_700_000_100
  )
}

/// Polls until `predicate` is true or `deadline` elapses.
private func waitUntil(
  timeout: Duration = .seconds(2),
  poll: Duration = .milliseconds(10),
  _ predicate: @Sendable () async -> Bool
) async -> Bool {
  let clock = ContinuousClock()
  let deadline = clock.now + timeout
  while clock.now < deadline {
    if await predicate() { return true }
    try? await Task.sleep(for: poll)
  }
  return await predicate()
}

// MARK: - Suite

@Suite("AdvertisementService Tests", .serialized)
struct AdvertisementServiceTests {
  private let radioID = UUID()

  private func makeStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let device = DeviceDTO.testDevice(id: radioID, radioID: radioID, nodeName: "TestRadio")
    try await store.saveDevice(device)
    return store
  }

  private func makeService(
    session: MockMeshCoreSession,
    store: any PersistenceStoreProtocol
  ) -> AdvertisementService {
    AdvertisementService(session: session, dataStore: store)
  }

  private func startMonitoring(_ service: AdvertisementService, session: MockMeshCoreSession) async {
    await service.startEventMonitoring(radioID: radioID)
    let subscribed = await waitUntil {
      await session.eventSubscriptionCount >= 1
    }
    #expect(subscribed)
  }

  // MARK: - Drain-loop non-blocking

  @Test
  func `held getContact for A does not block contactDeleted for B`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let keyA = makePublicKey(seed: 0xA1)
    let keyB = makePublicKey(seed: 0xB2)

    // Seed contact B locally so 0x8F can delete it.
    let frameB = makeContactFrame(publicKey: keyB, name: "ContactB")
    let contactBID = try await store.saveContact(radioID: radioID, from: frameB).id

    await session.setStubbedContact(makeMeshContact(publicKey: keyA, name: "ContactA"), for: keyA)
    await session.holdNextGetContact(for: keyA)

    await startMonitoring(service, session: session)

    // Unknown-key advert for A parks getContact.
    await session.yieldEvent(.advertisement(publicKey: keyA))
    let held = await waitUntil {
      await session.isGetContactHeld(for: keyA)
    }
    #expect(held, "getContact for A should be held open")

    // While A is held, 0x8F for B must still be processed (drain is non-blocking).
    await session.yieldEvent(.contactDeleted(publicKey: keyB))

    let deleted = await waitUntil(timeout: .seconds(1)) {
      let contact = try? await store.fetchContact(id: contactBID)
      return contact == nil
    }

    await session.releaseGetContact(for: keyA)
    await service.stopEventMonitoring()

    #expect(deleted, "B must be deleted while A's getContact is still airborne")
  }

  // MARK: - Ghost-contact cancel (same key, no local row)

  @Test
  func `0x8F for unknown airborne key prevents ghost save`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let keyA = makePublicKey(seed: 0xC3)
    await session.setStubbedContact(makeMeshContact(publicKey: keyA, name: "Ghost"), for: keyA)
    await session.holdNextGetContact(for: keyA)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: keyA))

    let held = await waitUntil { await session.isGetContactHeld(for: keyA) }
    #expect(held)

    // 0x8F with no local Contact row — cancel must still be recorded.
    await session.yieldEvent(.contactDeleted(publicKey: keyA))
    await session.releaseGetContact(for: keyA)

    // Wait until the held getContact has actually returned (not just been appended at hold entry).
    let fetchReturned = await waitUntil {
      await !session.isGetContactHeld(for: keyA)
    }
    #expect(fetchReturned, "getContact must complete after release")

    // Bound "commit finished and still nil": contact stays absent across several polls.
    var sawContact = false
    for _ in 0..<5 {
      try? await Task.sleep(for: .milliseconds(20))
      if await (try? store.fetchContact(radioID: radioID, publicKey: keyA)) != nil {
        sawContact = true
        break
      }
    }
    await service.stopEventMonitoring()
    #expect(!sawContact, "cancelled fetch must not insert a ghost contact")
  }

  // MARK: - Cancel after save (isNew false)

  @Test
  func `pathUpdate cancel after save does not cascade-delete messages`() async throws {
    // Hold saveContact after getContact returns so cancel lands post-save
    // (isNew false must not cascade-delete messages).
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0xD4)
    let frame = makeContactFrame(publicKey: key, name: "HasMessages")
    let contactID = try await store.saveContact(radioID: radioID, from: frame).id
    try await store.saveMessage(
      MessageDTO.testDirectMessage(radioID: radioID, contactID: contactID, text: "keep me")
    )

    await session.setStubbedContact(
      makeMeshContact(publicKey: key, name: "HasMessages", outPathLength: 1, outPath: Data([0xAA])),
      for: key
    )
    await store.holdNextSaveContact()

    await startMonitoring(service, session: session)
    await session.yieldEvent(.pathUpdate(publicKey: key))

    let saveHeld = await waitUntil { await store.isSaveContactHeld }
    #expect(saveHeld, "saveContact must be held so cancel can land after getContact")

    // Teardown cancels while save is airborne; isNew false must not deleteContact.
    let completedBeforeRelease = await store.saveContactCompletedCount
    await service.stopEventMonitoring()
    await store.releaseSaveContact()

    // Wait for save to finish, then multi-poll so a late deleteContact cannot
    // race past a single empty-deleted snapshot.
    let saveFinished = await waitUntil {
      await store.saveContactCompletedCount > completedBeforeRelease
    }
    #expect(saveFinished, "saveContact must complete after release")

    var sawCascadeDefect = false
    for _ in 0..<5 {
      try? await Task.sleep(for: .milliseconds(20))
      let contact = try? await store.fetchContact(id: contactID)
      let messages = await (try? store.fetchMessages(contactID: contactID, limit: 50, offset: 0)) ?? []
      let deleted = await store.deletedContactIDs
      if contact == nil || messages.count != 1 || !deleted.isEmpty {
        sawCascadeDefect = true
        break
      }
    }
    #expect(!sawCascadeDefect, "contact, messages, and empty deletes must hold across the settle window")

    let messages = try await store.fetchMessages(contactID: contactID, limit: 50, offset: 0)
    let contact = try await store.fetchContact(id: contactID)
    #expect(contact != nil, "existing contact must survive cancelled path-update upsert")
    #expect(messages.count == 1, "messages must not be cascade-deleted on cancelled upsert")
    #expect(await store.deletedContactIDs.isEmpty, "isNew false must not invoke deleteContact")
  }

  // MARK: - Reason upgrade

  @Test
  func `pathUpdate upgraded by advert saves DiscoveredNode`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0xE5)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "Upgraded"), for: key)
    await session.holdNextGetContact(for: key)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.pathUpdate(publicKey: key))

    let held = await waitUntil { await session.isGetContactHeld(for: key) }
    #expect(held)

    // Upgrade reason while airborne. Yield and allow the drain loop to process
    // the advert before releasing getContact so the upgrade is visible to the worker.
    await session.yieldEvent(.advertisement(publicKey: key))
    try? await Task.sleep(for: .milliseconds(50))
    await session.releaseGetContact(for: key)

    let nodesAppeared = await waitUntil {
      let nodes = await (try? store.fetchDiscoveredNodes(radioID: radioID)) ?? []
      return nodes.contains { $0.publicKey == key }
    }
    await service.stopEventMonitoring()
    #expect(nodesAppeared, "advert reason upgrade must upsert DiscoveredNode")
  }

  @Test
  func `advert during pathUpdate saveContact upgrades to DiscoveredNode`() async {
    // Reason is pathUpdate at switch time; advert upgrades the queue entry while
    // pathUpdate's saveContact is airborne. commitPathUpdateFetch never upserts
    // DiscoveredNode, so Discover visibility depends on the upgrade path.
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0xE6)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "MidCommit"), for: key)
    await store.holdNextSaveContact()

    await startMonitoring(service, session: session)
    await session.yieldEvent(.pathUpdate(publicKey: key))

    let saveHeld = await waitUntil { await store.isSaveContactHeld }
    #expect(saveHeld, "pathUpdate saveContact must be held so advert can upgrade mid-commit")

    // Unknown-key advert upgrades the queue entry while pathUpdate save is airborne.
    // PathUpdate inserts first, so the advert commit sees isNew false and emits
    // .contactUpdated rather than .newContactDiscovered.
    await session.yieldEvent(.advertisement(publicKey: key))
    try? await Task.sleep(for: .milliseconds(50))
    await store.releaseSaveContact()

    let nodesAppeared = await waitUntil {
      let nodes = await (try? store.fetchDiscoveredNodes(radioID: radioID)) ?? []
      return nodes.contains { $0.publicKey == key }
    }
    await service.stopEventMonitoring()
    #expect(nodesAppeared, "mid-commit advert upgrade must upsert DiscoveredNode")
  }

  // MARK: - One drainer / re-entrant barrier

  @Test
  func `concurrent setSyncingContacts false performs one fetch per key`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0xF6)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "Once"), for: key)
    await session.holdNextGetContact(for: key)

    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    // Two concurrent barrier callers.
    async let barrier1: Void = service.setSyncingContacts(false)
    async let barrier2: Void = service.setSyncingContacts(false)

    let held = await waitUntil { await session.isGetContactHeld(for: key) }
    #expect(held)

    await session.releaseGetContact(for: key)
    _ = await (barrier1, barrier2)

    let fetchCount = await session.getContactPublicKeys.filter { $0 == key }.count
    await service.stopEventMonitoring()
    #expect(fetchCount == 1, "one drainer must fetch each key once across re-entrant barriers")
  }

  // MARK: - Cancel-guarded removal (re-enqueue while airborne)

  @Test
  func `0x8F then re-advert while airborne fetches again and saves`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x17)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "ReAdded"), for: key)
    await session.holdNextGetContact(for: key)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let held = await waitUntil { await session.isGetContactHeld(for: key) }
    #expect(held)

    // Cancel airborne fetch, then radio re-adds and advert re-enqueues before release.
    // A cancelled fetch must leave the newer entry so the worker fetches again.
    await session.yieldEvent(.contactDeleted(publicKey: key))
    try? await Task.sleep(for: .milliseconds(30))
    await session.yieldEvent(.advertisement(publicKey: key))
    try? await Task.sleep(for: .milliseconds(50))
    await session.releaseGetContact(for: key)

    let saved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) != nil
    }
    let fetchCount = await session.getContactPublicKeys.filter { $0 == key }.count
    await service.stopEventMonitoring()
    #expect(saved, "re-enqueued key must be saved after second fetch")
    #expect(fetchCount == 2, "cancelled fetch must not strand the re-enqueued key")
  }

  @Test
  func `0x8F re-advert then getContact throw does not strand re-enqueued key`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x18)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "ThrowThenOK"), for: key)
    await session.holdNextGetContact(for: key)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let held = await waitUntil { await session.isGetContactHeld(for: key) }
    #expect(held)

    await session.yieldEvent(.contactDeleted(publicKey: key))
    try? await Task.sleep(for: .milliseconds(30))
    await session.yieldEvent(.advertisement(publicKey: key))
    try? await Task.sleep(for: .milliseconds(30))

    // First release throws while cancelled — must not wipe the re-enqueued entry.
    await session.setGetContactError(AdvertisementServiceTestError.getContactFailed, for: key)
    await session.holdNextGetContact(for: key)
    await session.releaseGetContact(for: key)

    let heldAgain = await waitUntil { await session.isGetContactHeld(for: key) }
    #expect(heldAgain, "re-enqueued key must be fetched again after cancelled throw")
    await session.setGetContactError(nil, for: key)
    await session.releaseGetContact(for: key)

    let saved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) != nil
    }
    await service.stopEventMonitoring()
    #expect(saved, "throw after cancel must not strand the re-enqueued key")
  }

  @Test
  func `0x8F re-advert then getContact nil does not strand re-enqueued key`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x19)
    let contact = makeMeshContact(publicKey: key, name: "NilThenOK")
    await session.setStubbedContact(contact, for: key)
    await session.holdNextGetContact(for: key)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let held = await waitUntil { await session.isGetContactHeld(for: key) }
    #expect(held)

    await session.yieldEvent(.contactDeleted(publicKey: key))
    try? await Task.sleep(for: .milliseconds(30))
    await session.yieldEvent(.advertisement(publicKey: key))
    try? await Task.sleep(for: .milliseconds(30))

    // First release returns nil while cancelled — must not wipe the re-enqueued entry.
    await session.setStubbedContact(nil, for: key)
    await session.holdNextGetContact(for: key)
    await session.releaseGetContact(for: key)

    let heldAgain = await waitUntil { await session.isGetContactHeld(for: key) }
    #expect(heldAgain, "re-enqueued key must be fetched again after cancelled nil")
    await session.setStubbedContact(contact, for: key)
    await session.releaseGetContact(for: key)

    let saved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) != nil
    }
    await service.stopEventMonitoring()
    #expect(saved, "nil after cancel must not strand the re-enqueued key")
  }

  // MARK: - Throw drops and continues + barrier return

  @Test
  func `getContact throw drops entry and continues to next key`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let keyFail = makePublicKey(seed: 0x28)
    let keyOK = makePublicKey(seed: 0x29)
    await session.setGetContactError(AdvertisementServiceTestError.getContactFailed, for: keyFail)
    await session.setStubbedContact(makeMeshContact(publicKey: keyOK, name: "OK"), for: keyOK)

    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: keyFail))
    await session.yieldEvent(.advertisement(publicKey: keyOK))

    // Barrier must return even when one key throws.
    await service.setSyncingContacts(false)

    let okSaved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: keyOK)) != nil
    }
    let failSaved = try await store.fetchContact(radioID: radioID, publicKey: keyFail)
    await service.stopEventMonitoring()
    #expect(okSaved)
    #expect(failSaved == nil)
  }

  @Test
  func `all keys throw ends pass without spin or residual contacts`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let keys = [makePublicKey(seed: 0x2A), makePublicKey(seed: 0x2B), makePublicKey(seed: 0x2C)]
    for key in keys {
      await session.setGetContactError(AdvertisementServiceTestError.getContactFailed, for: key)
    }

    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    for key in keys {
      await session.yieldEvent(.advertisement(publicKey: key))
    }

    let barrierState = BarrierFlag()
    let barrierTask = Task {
      await service.setSyncingContacts(false)
      await barrierState.markDone()
    }

    let barrierReturned = await waitUntil(timeout: .seconds(2)) {
      await barrierState.isDone
    }
    #expect(barrierReturned, "barrier must return when every key throws")
    _ = await barrierTask.result

    for key in keys {
      let count = await session.getContactPublicKeys.filter { $0 == key }.count
      #expect(count == 1, "each throwing key fetched once")
      let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
      #expect(contact == nil)
    }
    await service.stopEventMonitoring()
  }

  // MARK: - Bounded barrier

  @Test
  func `live enqueue after pass starts does not extend barrier`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let keySnap = makePublicKey(seed: 0x3A)
    let keyLate = makePublicKey(seed: 0x3B)
    await session.setStubbedContact(makeMeshContact(publicKey: keySnap, name: "Snap"), for: keySnap)
    await session.setStubbedContact(makeMeshContact(publicKey: keyLate, name: "Late"), for: keyLate)
    await session.holdNextGetContact(for: keySnap)

    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: keySnap))

    // Start barrier (pass snapshots keySnap).
    let barrierState = BarrierFlag()
    let barrierTask = Task {
      await service.setSyncingContacts(false)
      await barrierState.markDone()
    }

    let held = await waitUntil { await session.isGetContactHeld(for: keySnap) }
    #expect(held)

    // Live enqueue after pass starts.
    await session.yieldEvent(.advertisement(publicKey: keyLate))
    await session.releaseGetContact(for: keySnap)

    // Barrier must return without waiting for keyLate's fetch to complete the pass.
    let barrierReturned = await waitUntil(timeout: .seconds(2)) {
      await barrierState.isDone
    }
    #expect(barrierReturned, "barrier must not be extended by mid-pass enqueues")
    _ = await barrierTask.result

    // Late key is still drained by the same worker afterwards.
    let lateSaved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: keyLate)) != nil
    }
    await service.stopEventMonitoring()
    #expect(lateSaved)
  }

  @Test
  func `late barrier during subsequent pass does not await further live enqueues`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let keySnap = makePublicKey(seed: 0x3C)
    let keyLate = makePublicKey(seed: 0x3D)
    let keyExtra = makePublicKey(seed: 0x3E)
    await session.setStubbedContact(makeMeshContact(publicKey: keySnap, name: "Snap"), for: keySnap)
    await session.setStubbedContact(makeMeshContact(publicKey: keyLate, name: "Late"), for: keyLate)
    await session.setStubbedContact(makeMeshContact(publicKey: keyExtra, name: "Extra"), for: keyExtra)
    await session.holdNextGetContact(for: keySnap)

    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: keySnap))

    // First barrier: awaits snapshotted pass over keySnap only.
    let firstBarrier = BarrierFlag()
    let firstTask = Task {
      await service.setSyncingContacts(false)
      await firstBarrier.markDone()
    }

    let snapHeld = await waitUntil { await session.isGetContactHeld(for: keySnap) }
    #expect(snapHeld)

    // Late key arrives during the first pass; hold so the next pass parks on it.
    await session.holdNextGetContact(for: keyLate)
    await session.yieldEvent(.advertisement(publicKey: keyLate))
    await session.releaseGetContact(for: keySnap)

    let firstReturned = await waitUntil(timeout: .seconds(2)) { await firstBarrier.isDone }
    #expect(firstReturned, "first barrier returns at its pass boundary")
    _ = await firstTask.result

    let lateHeld = await waitUntil { await session.isGetContactHeld(for: keyLate) }
    #expect(lateHeld, "late key is being drained in a subsequent snapshotted pass")

    // Late barrier joins mid-pass. Extra keys enqueued during this pass must not
    // extend it (no drain-until-empty under live traffic).
    let lateBarrier = BarrierFlag()
    let lateTask = Task {
      await service.setSyncingContacts(false)
      await lateBarrier.markDone()
    }

    await session.holdNextGetContact(for: keyExtra)
    await session.yieldEvent(.advertisement(publicKey: keyExtra))
    await session.releaseGetContact(for: keyLate)

    let lateReturned = await waitUntil(timeout: .seconds(2)) { await lateBarrier.isDone }
    #expect(lateReturned, "late barrier returns at the end of its snapshotted pass")
    _ = await lateTask.result

    // Extra is next-pass work: not saved when the late barrier returns.
    let extraBeforeRelease = try await store.fetchContact(radioID: radioID, publicKey: keyExtra)
    #expect(extraBeforeRelease == nil, "live enqueue mid-pass must not block the barrier")

    await session.releaseGetContact(for: keyExtra)
    let extraSaved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: keyExtra)) != nil
    }
    await service.stopEventMonitoring()
    #expect(extraSaved)
  }

  // MARK: - Lost-wakeup handshake

  /// Covers enqueue after an idle worker exit (starts a new worker). Production
  /// keeps empty-queue observation and generation-matched ref clear in one
  /// actor region with no await between them.
  @Test
  func `enqueue after worker exit still fetches`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let keyFirst = makePublicKey(seed: 0x4C)
    let keySecond = makePublicKey(seed: 0x4D)
    await session.setStubbedContact(makeMeshContact(publicKey: keyFirst, name: "First"), for: keyFirst)
    await session.setStubbedContact(makeMeshContact(publicKey: keySecond, name: "Second"), for: keySecond)
    await session.holdNextGetContact(for: keyFirst)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: keyFirst))

    let held = await waitUntil { await session.isGetContactHeld(for: keyFirst) }
    #expect(held)

    // Release first so worker can finish; immediately enqueue second.
    await session.releaseGetContact(for: keyFirst)
    // Small yield so first fetch can complete, then enqueue near exit.
    try? await Task.sleep(for: .milliseconds(20))
    await session.yieldEvent(.advertisement(publicKey: keySecond))

    let secondSaved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: keySecond)) != nil
    }
    await service.stopEventMonitoring()
    #expect(secondSaved, "key enqueued at worker exit must not strand")
  }

  // MARK: - Dedup and nil drop

  @Test
  func `duplicate adverts for same key perform one fetch`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x5E)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "Dup"), for: key)
    await session.holdNextGetContact(for: key)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    await session.yieldEvent(.advertisement(publicKey: key))
    await session.yieldEvent(.advertisement(publicKey: key))

    let held = await waitUntil { await session.isGetContactHeld(for: key) }
    #expect(held)
    await session.releaseGetContact(for: key)

    let saved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) != nil
    }
    let fetchCount = await session.getContactPublicKeys.filter { $0 == key }.count
    await service.stopEventMonitoring()
    #expect(saved)
    #expect(fetchCount == 1)
  }

  @Test
  func `getContact nil drops the entry`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x6F)
    // Explicit nil stub (no contact on device).
    await session.setStubbedContact(nil, for: key)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    // Wait for worker to process.
    let fetched = await waitUntil {
      await session.getContactPublicKeys.contains(key)
    }
    try? await Task.sleep(for: .milliseconds(50))

    let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
    await service.stopEventMonitoring()
    #expect(fetched)
    #expect(contact == nil)
  }

  @Test
  func `keys enqueued during sync fetch after setSyncingContacts false`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x70)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "Deferred"), for: key)

    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    // Still no fetch while syncing.
    try? await Task.sleep(for: .milliseconds(50))
    let midFetchCount = await session.getContactPublicKeys.filter { $0 == key }.count
    #expect(midFetchCount == 0)

    await service.setSyncingContacts(false)

    let saved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) != nil
    }
    await service.stopEventMonitoring()
    #expect(saved)
  }

  @Test
  func `successful advert save emits newContactDiscovered once`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x81)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "Signal"), for: key)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        if case .newContactDiscovered = event {
          await counter.increment()
        }
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let saved = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) != nil
    }
    try? await Task.sleep(for: .milliseconds(50))
    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result

    let newContactCount = await counter.count
    #expect(saved)
    #expect(newContactCount == 1, "exactly one UI-refresh signal on successful save")
  }
}

// MARK: - Concurrency helpers

private actor BarrierFlag {
  private(set) var isDone = false
  func markDone() {
    isDone = true
  }
}

private actor EventCounter {
  private(set) var count = 0
  func increment() {
    count += 1
  }
}
