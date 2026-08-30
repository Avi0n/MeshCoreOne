import Foundation
@testable import MC1Services
import MeshCore
import Testing

// MARK: - Helpers

private enum AdvertisementServiceTestError: Error {
  case deadlineExceeded(String)
  case storeUnavailable
}

private func makePublicKey(seed: UInt8) -> Data {
  Data((0..<ProtocolLimits.publicKeySize).map { UInt8(($0 &+ Int(seed)) & 0xFF) })
}

private func makeMeshContact(
  publicKey: Data,
  name: String = "Node",
  type: ContactType = .chat,
  outPathLength: UInt8 = 0,
  outPath: Data = Data(),
  latitude: Double = 0,
  longitude: Double = 0,
  lastAdvertTimestamp: Date = Date(timeIntervalSince1970: 1_700_000_000),
  lastModified: Date = Date(timeIntervalSince1970: 1_700_000_100)
) -> MeshContact {
  MeshContact(
    id: publicKey.hexString,
    publicKey: publicKey,
    type: type,
    flags: ContactFlags(rawValue: 0),
    outPathLength: outPathLength,
    outPath: outPath,
    advertisedName: name,
    lastAdvertisement: lastAdvertTimestamp,
    latitude: latitude,
    longitude: longitude,
    lastModified: lastModified
  )
}

private func makeContactFrame(
  publicKey: Data,
  name: String = "LocalContact",
  type: ContactType = .chat,
  latitude: Double = 0,
  longitude: Double = 0,
  lastAdvertTimestamp: UInt32 = 1_700_000_000,
  lastModified: UInt32 = 1_700_000_100
) -> ContactFrame {
  ContactFrame(
    publicKey: publicKey,
    type: type,
    flags: 0,
    outPathLength: 0,
    outPath: Data(),
    name: name,
    lastAdvertTimestamp: lastAdvertTimestamp,
    latitude: latitude,
    longitude: longitude,
    lastModified: lastModified
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

private actor HandlerRecorder {
  private(set) var calls: [Bool] = []
  private var results: [AdvertContactSyncOutcome] = []
  private var persistFrames: [ContactFrame] = []
  private let store: any PersistenceStoreProtocol
  private let radioID: UUID

  init(store: any PersistenceStoreProtocol, radioID: UUID) {
    self.store = store
    self.radioID = radioID
  }

  func enqueueResult(_ outcome: AdvertContactSyncOutcome) {
    results.append(outcome)
  }

  func enqueuePersist(_ frame: ContactFrame) {
    persistFrames.append(frame)
  }

  func handle(fullRefetch: Bool) async -> AdvertContactSyncOutcome {
    calls.append(fullRefetch)
    let outcome = results.isEmpty ? AdvertContactSyncOutcome.synced : results.removeFirst()
    // Only persist on success so a failed call cannot leave a row that
    // masks a missing re-merge of pending keys.
    if outcome == .synced, !persistFrames.isEmpty {
      let frame = persistFrames.removeFirst()
      _ = try? await store.saveContact(radioID: radioID, from: frame)
    }
    return outcome
  }

  var callCount: Int {
    calls.count
  }

  var fullRefetchFlags: [Bool] {
    calls
  }
}

private actor EventCounter {
  private(set) var newContactCount = 0
  private(set) var contactUpdatedCount = 0
  private(set) var conversationsChangedCount = 0
  private(set) var adoptedContactIDs: [UUID] = []

  func note(_ event: AdvertisementEvent) {
    switch event {
    case .newContactDiscovered:
      newContactCount += 1
    case .contactUpdated:
      contactUpdatedCount += 1
    case .conversationsChanged:
      conversationsChangedCount += 1
    case let .orphanDirectMessagesAdopted(contactIDs):
      adoptedContactIDs.append(contentsOf: contactIDs)
    default:
      break
    }
  }
}

/// Records `fullRefetch` flags from a custom delta-sync handler.
private actor CallFlagRecorder {
  private(set) var flags: [Bool] = []

  func note(_ fullRefetch: Bool) {
    flags.append(fullRefetch)
  }
}

/// Fails every round up to the cap, and on the final failing round records a
/// fresh advert key mid-flight — reproducing an 0x80 that lands while the last
/// failing handler is awaited. Rounds past the cap succeed so the fresh key drains.
private actor CapRoundInjector {
  private let service: AdvertisementService
  private let freshKey: Data
  private let cap: Int
  private(set) var calls = 0

  init(service: AdvertisementService, freshKey: Data, cap: Int) {
    self.service = service
    self.freshKey = freshKey
    self.cap = cap
  }

  func handle() async -> AdvertContactSyncOutcome {
    calls += 1
    if calls == cap {
      await service.recordPendingAdvertKey(freshKey)
    }
    return calls > cap ? .synced : .failed
  }
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
    store: any PersistenceStoreProtocol,
    advertSyncDebounce: Duration = .zero,
    advertSyncMinInterval: Duration = .zero,
    advertSyncBusyBackoff: Duration = .zero
  ) -> AdvertisementService {
    AdvertisementService(
      session: session,
      dataStore: store,
      advertSyncDebounce: advertSyncDebounce,
      advertSyncMinInterval: advertSyncMinInterval,
      advertSyncBusyBackoff: advertSyncBusyBackoff
    )
  }

  private func startMonitoring(_ service: AdvertisementService, session: MockMeshCoreSession) async {
    await service.startEventMonitoring(radioID: radioID)
    let subscribed = await waitUntil {
      await session.eventSubscriptionCount >= 1
    }
    #expect(subscribed)
  }

  private func installHandler(
    _ service: AdvertisementService,
    recorder: HandlerRecorder
  ) async {
    await service.setDeltaSyncHandler { fullRefetch in
      await recorder.handle(fullRefetch: fullRefetch)
    }
  }

  // MARK: - Rollback cascade safety

  @Test
  func `pathUpdate cancel after save does not cascade-delete messages`() async throws {
    // A contact re-saved mid-round after 0x8F must not cascade-wipe a DM
    // that landed before rollback.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0xD4)
    let hold = HandlerHold()
    await service.setDeltaSyncHandler { _ in
      await hold.waitUntilReleased()
      let saved = try? await store.saveContact(
        radioID: radioID, from: makeContactFrame(publicKey: key, name: "HasMessages")
      )
      if let contactID = saved?.id {
        try? await store.saveMessage(
          MessageDTO.testDirectMessage(radioID: radioID, contactID: contactID, text: "keep me")
        )
      }
      return .synced
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let waiting = await waitUntil { await hold.isWaiting }
    #expect(waiting)

    // 0x8F while commit is held: no local row yet, so only the rollback key is set.
    await session.yieldEvent(.contactDeleted(publicKey: key))
    try? await Task.sleep(for: .milliseconds(40))
    await hold.release()

    let roundDone = await waitUntil { await service.deltaSyncTask == nil }
    #expect(roundDone)

    let contact = try #require(await store.fetchContact(radioID: radioID, publicKey: key))
    let messages = try await store.fetchMessages(contactID: contact.id, limit: 50, offset: 0)
    #expect(messages.count == 1, "messages must not be cascade-deleted on rollback")

    await service.stopEventMonitoring()
  }

  // MARK: - Coalescing

  @Test
  func `burst of adverts coalesces to one handler call`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    // Hold the first handler call so later adverts accumulate in pendingAdvertKeys
    // instead of each completing a separate drain under .zero durations.
    let hold = HandlerHold()
    await service.setDeltaSyncHandler { fullRefetch in
      let isFirst = await recorder.callCount == 0
      let result = await recorder.handle(fullRefetch: fullRefetch)
      if isFirst {
        await hold.waitUntilReleased()
      }
      return result
    }

    let keys = (0..<5).map { makePublicKey(seed: UInt8(0xA0 &+ $0)) }
    for key in keys {
      let frame = makeContactFrame(publicKey: key, name: "K\(key.prefix(1).hexString)")
      _ = try await store.saveContact(radioID: radioID, from: frame)
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: keys[0]))
    let held = await waitUntil { await hold.isWaiting }
    #expect(held)
    #expect(await recorder.callCount == 1)

    for key in keys.dropFirst() {
      await session.yieldEvent(.advertisement(publicKey: key))
    }
    // Let the event loop record the remaining keys while the first drain is held.
    try? await Task.sleep(for: .milliseconds(40))
    #expect(await recorder.callCount == 1, "mid-hold adverts must not start a parallel handler")
    await hold.release()

    // Mid-hold keys schedule exactly one follow-up pass after release.
    let secondPass = await waitUntil { await recorder.callCount == 2 }
    #expect(secondPass, "keys recorded mid-hold should schedule one second pass")
    try? await Task.sleep(for: .milliseconds(40))
    await service.stopEventMonitoring()

    #expect(await recorder.callCount == 2)
    #expect(await session.getContactPublicKeys.isEmpty)
  }

  // MARK: - Known contact 0x80

  @Test
  func `known contact advert bumps lastHeard without getContact`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0xB1)
    let frame = makeContactFrame(publicKey: key, name: "Known")
    _ = try await store.saveContact(radioID: radioID, from: frame)
    _ = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)

    let before = Date().addingTimeInterval(-1)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let heard = await waitUntil {
      let contact = try? await store.fetchContact(radioID: radioID, publicKey: key)
      return (contact?.lastHeardTimestamp ?? 0) > 0
    }
    #expect(heard)

    let contact = try #require(await store.fetchContact(radioID: radioID, publicKey: key))
    #expect((contact.lastHeardTimestamp ?? 0) >= UInt32(before.timeIntervalSince1970))

    let nodes = try await store.fetchDiscoveredNodes(radioID: radioID)
    let node = try #require(nodes.first { $0.publicKey == key })
    #expect(node.lastHeard >= before)

    let handlerRan = await waitUntil { await recorder.callCount >= 1 }
    #expect(handlerRan)
    #expect(await session.getContactPublicKeys.isEmpty)
    await service.stopEventMonitoring()
  }

  // MARK: - Unknown key 0x80

  @Test
  func `unknown key advert yields newContactDiscovered once after handler persists`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    let key = makePublicKey(seed: 0xC2)
    await recorder.enqueuePersist(
      makeContactFrame(publicKey: key, name: "NewNode", latitude: 10, longitude: 20)
    )
    await installHandler(service, recorder: recorder)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let discovered = await waitUntil {
      await counter.newContactCount >= 1
    }
    #expect(discovered)

    // Second advert for the same key must not re-notify as new.
    await recorder.enqueuePersist(
      makeContactFrame(publicKey: key, name: "NewNode", latitude: 11, longitude: 21)
    )
    await session.yieldEvent(.advertisement(publicKey: key))
    let secondHandler = await waitUntil { await recorder.callCount >= 2 }
    #expect(secondHandler)
    try? await Task.sleep(for: .milliseconds(50))

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result

    #expect(await counter.newContactCount == 1)
    let contact = try #require(await store.fetchContact(radioID: radioID, publicKey: key))
    #expect(contact.name == "NewNode")
  }

  // MARK: - 0x8A parity

  @Test
  func `0x8A then 0x80 for same key notifies from each path`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0xD3)
    let mesh = makeMeshContact(publicKey: key, name: "Manual")

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.newContact(mesh))

    let from8A = await waitUntil { await counter.newContactCount >= 1 }
    #expect(from8A)

    // 0x8A leaves a Discover row but no Contact row. The 0x80 delta round's
    // pre-round snapshot therefore lacks the key; after the handler persists the
    // Contact row it lands in insertedKeys and is announced separately from 0x8A.
    await recorder.enqueuePersist(makeContactFrame(publicKey: key, name: "Manual"))
    await session.yieldEvent(.advertisement(publicKey: key))

    // 0x8A yielded the first .contactUpdated; the second one lands after reconcile.
    let reconciled = await waitUntil { await counter.contactUpdatedCount >= 2 }
    #expect(reconciled)
    try? await Task.sleep(for: .milliseconds(30))

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result

    #expect(await counter.newContactCount == 2)
    #expect(await recorder.callCount == 1)
  }

  // MARK: - Path update

  @Test
  func `pathUpdate only triggers handler without Discover row`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0xE4)
    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.pathUpdate(publicKey: key))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)

    let updated = await waitUntil { await counter.contactUpdatedCount >= 1 }
    #expect(updated)

    let nodes = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(nodes.isEmpty)
    #expect(await session.getContactPublicKeys.isEmpty)

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result
  }

  @Test
  func `pathUpdate for known contact escalates when incremental leaves lastModified unchanged`() async throws {
    // Radio RTC reset / clock step-back can stamp path lastmod at or below the
    // stored watermark. Incremental GET_CONTACTS then returns nothing; known
    // contacts never enter escalateMissingUnknownKeys. Without a post-round
    // path check the out-path stays stale forever.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0xA1)
    let oldLastMod: UInt32 = 1_700_000_100
    let oldPath = Data([0x01, 0x02])
    let newPath = Data([0x03, 0x04])
    let newLastMod: UInt32 = oldLastMod + 50
    _ = try await store.saveContact(
      radioID: radioID,
      from: ContactFrame(
        publicKey: key,
        type: .repeater,
        flags: 0,
        outPathLength: 2,
        outPath: oldPath,
        name: "Relay",
        lastAdvertTimestamp: 1_700_000_000,
        latitude: 10.0,
        longitude: 20.0,
        lastModified: oldLastMod
      )
    )

    let calls = CallFlagRecorder()
    await service.setDeltaSyncHandler { fullRefetch in
      await calls.note(fullRefetch)
      // Incremental models an empty watermark filter (no row written).
      // Only the escalated full refetch delivers the path update.
      if fullRefetch {
        _ = try? await store.saveContact(
          radioID: radioID,
          from: ContactFrame(
            publicKey: key,
            type: .repeater,
            flags: 0,
            outPathLength: 2,
            outPath: newPath,
            name: "Relay",
            lastAdvertTimestamp: 1_700_000_000,
            latitude: 10.5,
            longitude: 20.5,
            lastModified: newLastMod
          )
        )
      }
      return .synced
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.pathUpdate(publicKey: key))

    let escalated = await waitUntil(timeout: .seconds(3)) {
      let flags = await calls.flags
      return flags.count >= 2 && flags.contains(true)
    }
    #expect(
      escalated,
      "when incremental leaves lastModified unchanged, path must escalate to full refetch"
    )
    #expect(await calls.flags.first == false)
    #expect(await session.getContactPublicKeys.isEmpty, "must not reinstate per-key getContact")

    let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
    #expect(contact?.outPath == newPath)
    #expect(contact?.lastModified == newLastMod)
    #expect(contact?.latitude == 10.5)

    await service.stopEventMonitoring()
  }

  @Test
  func `pathUpdate for known contact does not escalate when incremental refreshes lastModified`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0xA2)
    let oldLastMod: UInt32 = 1_700_000_100
    let newLastMod: UInt32 = oldLastMod + 10
    let newPath = Data([0xAA, 0xBB])
    _ = try await store.saveContact(
      radioID: radioID,
      from: ContactFrame(
        publicKey: key,
        type: .repeater,
        flags: 0,
        outPathLength: 1,
        outPath: Data([0x11]),
        name: "Relay",
        lastAdvertTimestamp: 1_700_000_000,
        latitude: 1.0,
        longitude: 2.0,
        lastModified: oldLastMod
      )
    )

    await recorder.enqueueResult(.synced)
    await recorder.enqueuePersist(
      ContactFrame(
        publicKey: key,
        type: .repeater,
        flags: 0,
        outPathLength: 2,
        outPath: newPath,
        name: "Relay",
        lastAdvertTimestamp: 1_700_000_000,
        latitude: 1.0,
        longitude: 2.0,
        lastModified: newLastMod
      )
    )
    await installHandler(service, recorder: recorder)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.pathUpdate(publicKey: key))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)
    // Allow a second round if one were incorrectly scheduled.
    try? await Task.sleep(for: .milliseconds(80))
    #expect(await recorder.callCount == 1, "successful path delivery must not escalate")
    #expect(await recorder.fullRefetchFlags == [false])
    #expect(await !service.escalateToFullRefetch)

    let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
    #expect(contact?.outPath == newPath)
    #expect(contact?.lastModified == newLastMod)

    await service.stopEventMonitoring()
  }

  // MARK: - Failure re-merge

  @Test
  func `handler failure remerges keys and retries`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    let key = makePublicKey(seed: 0xF5)
    // Unknown key: first fail leaves no row; second success persists + reconcile notifies.
    // Proves re-merge kept the key — empty drained would skip Discover/newContact.
    await recorder.enqueueResult(.failed)
    await recorder.enqueueResult(.synced)
    await recorder.enqueuePersist(makeContactFrame(publicKey: key, name: "Retry"))
    await installHandler(service, recorder: recorder)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let retried = await waitUntil { await recorder.callCount >= 2 }
    #expect(retried)

    let discovered = await waitUntil { await counter.newContactCount >= 1 }
    #expect(discovered, "re-merged key must reach reconcile after successful retry")

    let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
    #expect(contact != nil)
    let nodes = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(nodes.contains { $0.publicKey == key })

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result
  }

  // MARK: - Syncing deferral

  @Test
  func `isSyncingContacts defers handler until cleared`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x16)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Deferred")
    )

    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    try? await Task.sleep(for: .milliseconds(50))
    #expect(await recorder.callCount == 0)

    await service.setSyncingContacts(false)
    let ran = await waitUntil { await recorder.callCount >= 1 }
    await service.stopEventMonitoring()
    #expect(ran)
  }

  // MARK: - Teardown mid-handler

  @Test
  func `teardown mid-handler prevents reconcile writes`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x27)
    let hold = HandlerHold()

    await service.setDeltaSyncHandler { _ in
      await hold.waitUntilReleased()
      // Persist as a successful sync would.
      _ = try? await store.saveContact(
        radioID: radioID,
        from: makeContactFrame(publicKey: key, name: "Late")
      )
      return .synced
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let entered = await waitUntil { await hold.isWaiting }
    #expect(entered)

    await service.stopEventMonitoring()
    await hold.release()

    // Settle: Contact may have been written by the held handler body, but
    // reconcile must not create a Discover row after teardown.
    try? await Task.sleep(for: .milliseconds(80))
    let nodes = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(nodes.isEmpty, "reconcile must not land after stopEventMonitoring")
  }

  // MARK: - Escalation

  @Test
  func `unknown key missing after success escalates to fullRefetch once`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    // Incremental success (no row) → escalate; fullRefetch fails → flag restored;
    // next call still fullRefetch; final fullRefetch success drops still-missing key.
    await recorder.enqueueResult(.synced)
    await recorder.enqueueResult(.failed)
    await recorder.enqueueResult(.synced)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x38)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let threeCalls = await waitUntil { await recorder.callCount >= 3 }
    await service.stopEventMonitoring()
    #expect(threeCalls)

    let flags = await recorder.fullRefetchFlags
    #expect(flags.count == 3)
    #expect(flags[0] == false, "first pass is incremental")
    #expect(flags[1] == true, "escalated full refetch")
    #expect(flags[2] == true, "failed full refetch must restore escalate flag")
    // Still-missing key is dropped after escalated success — no fourth call.
    try? await Task.sleep(for: .milliseconds(50))
    #expect(await recorder.callCount == 3)
  }

  // MARK: - Nil handler keeps pending

  @Test
  func `nil handler leaves keys pending for later install`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x61)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Pending")
    )

    // No handler yet — schedule runs, finds nil, leaves keys pending.
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    try? await Task.sleep(for: .milliseconds(40))
    #expect(await recorder.callCount == 0)

    await installHandler(service, recorder: recorder)
    let ran = await waitUntil { await recorder.callCount >= 1 }
    await service.stopEventMonitoring()
    #expect(ran, "installing a handler must re-arm pending keys")
  }

  // MARK: - Min interval

  @Test
  func `min interval delays second delta sync after success`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(
      session: session,
      store: store,
      advertSyncDebounce: .zero,
      advertSyncMinInterval: .milliseconds(150)
    )
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let keyA = makePublicKey(seed: 0x71)
    let keyB = makePublicKey(seed: 0x72)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: keyA, name: "A")
    )
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: keyB, name: "B")
    )

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: keyA))
    let first = await waitUntil { await recorder.callCount >= 1 }
    #expect(first)

    await session.yieldEvent(.advertisement(publicKey: keyB))
    try? await Task.sleep(for: .milliseconds(40))
    #expect(await recorder.callCount == 1, "second pass must wait for min interval")

    let second = await waitUntil(timeout: .seconds(2)) {
      await recorder.callCount >= 2
    }
    await service.stopEventMonitoring()
    #expect(second)
  }

  // MARK: - 0x8F drops pending key

  @Test
  func `contactDeleted removes pending key before sync runs`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x49)
    // Known contact so .contactUpdated after touch proves the advert was handled
    // before 0x8F (event yields are not awaited through the drain loop).
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Doomed")
    )

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    // Defer delta sync so 0x8F can clear the pending map before drain.
    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let advertHandled = await waitUntil { await counter.contactUpdatedCount >= 1 }
    #expect(advertHandled)

    await session.yieldEvent(.contactDeleted(publicKey: key))
    let deleted = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) == nil
    }
    #expect(deleted)

    await service.setSyncingContacts(false)

    // Pending key was removed → setSyncingContacts(false) does not re-arm.
    try? await Task.sleep(for: .milliseconds(50))
    #expect(await recorder.callCount == 0)
    #expect(await counter.newContactCount == 0)

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result
  }

  @Test
  func `reconcile skips deleted contact after mid-handler 0x8F`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x4A)
    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    let hold = HandlerHold()
    await service.setDeltaSyncHandler { _ in
      await hold.waitUntilReleased()
      return .synced
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let waiting = await waitUntil { await hold.isWaiting }
    #expect(waiting)

    // Contact never persisted; 0x8F during handler; reconcile sees nil row.
    await session.yieldEvent(.contactDeleted(publicKey: key))
    await hold.release()

    let updated = await waitUntil { await counter.contactUpdatedCount >= 1 }
    #expect(updated)
    try? await Task.sleep(for: .milliseconds(40))
    #expect(await counter.newContactCount == 0)

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result
  }

  @Test
  func `contactDeleted during commit rolls back the resurrected contact`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x4B)
    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    let hold = HandlerHold()
    await service.setDeltaSyncHandler { _ in
      await hold.waitUntilReleased()
      // The batch commit re-saves a row the radio deleted while it was in flight.
      _ = try? await store.saveContact(
        radioID: radioID, from: makeContactFrame(publicKey: key, name: "Ghost")
      )
      return .synced
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let waiting = await waitUntil { await hold.isWaiting }
    #expect(waiting)

    await session.yieldEvent(.contactDeleted(publicKey: key))
    // Let the 0x8F handler record the key before the commit returns.
    try? await Task.sleep(for: .milliseconds(40))
    await hold.release()

    let synced = await waitUntil { await counter.contactUpdatedCount >= 1 }
    #expect(synced)
    try? await Task.sleep(for: .milliseconds(60))

    let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
    #expect(contact == nil, "a row re-saved after the radio deleted it must be rolled back")
    let nodes = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(nodes.isEmpty, "a rolled-back contact must leave no Discover row")
    #expect(await counter.newContactCount == 0)

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result
  }

  @Test
  func `rollback keeps contact the radio deleted then re-added mid-round`() async throws {
    // Overwrite-oldest can free a slot then auto-add the same key again inside
    // one delta round. Rollback must not treat that re-synced row as a stale
    // batch resurrection: the re-advert clears the 0x8F tombstone.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let advertKey = makePublicKey(seed: 0x93)
    let deletedKey = makePublicKey(seed: 0x94)
    _ = try await store.saveContact(
      radioID: radioID,
      from: makeContactFrame(publicKey: deletedKey, name: "SlotVictim")
    )

    let hold = HandlerHold()
    await service.setDeltaSyncHandler { _ in
      await hold.waitUntilReleased()
      // getContacts after the radio re-added the key re-creates the local row.
      _ = try? await store.saveContact(
        radioID: radioID,
        from: makeContactFrame(publicKey: deletedKey, name: "ReAdded")
      )
      return .synced
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: advertKey))
    let waiting = await waitUntil { await hold.isWaiting }
    #expect(waiting)

    await session.yieldEvent(.contactDeleted(publicKey: deletedKey))
    let deleted = await waitUntil {
      let gone = await (try? store.fetchContact(radioID: radioID, publicKey: deletedKey)) == nil
      let tracked = await service.contactsDeletedDuringSync.contains(deletedKey)
      return gone && tracked
    }
    #expect(deleted)

    // Re-advert: radio auto-added the contact again mid-round.
    await session.yieldEvent(.advertisement(publicKey: deletedKey))
    let reAdvertPending = await waitUntil {
      let pending = await service.pendingAdvertKeys.contains(deletedKey)
      let cleared = await !service.contactsDeletedDuringSync.contains(deletedKey)
      return pending && cleared
    }
    #expect(reAdvertPending, "re-advert must clear the mid-round delete tombstone")

    await hold.release()
    let roundDone = await waitUntil { await service.deltaSyncTask == nil }
    #expect(roundDone)
    try? await Task.sleep(for: .milliseconds(40))

    let contact = try await store.fetchContact(radioID: radioID, publicKey: deletedKey)
    #expect(contact != nil, "a contact the radio re-added mid-round must survive rollback")
    #expect(contact?.name == "ReAdded")
    #expect(
      await !service.contactsDeletedDuringSync.contains(deletedKey),
      "tombstone must stay clear so reconcile does not skip the re-added key"
    )

    await service.stopEventMonitoring()
  }

  @Test
  func `contactDeleted during failed commit rolls back and does not re-queue`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x4C)
    let hold = HandlerHold()
    await service.setDeltaSyncHandler { _ in
      await hold.waitUntilReleased()
      // An early batch committed the row before the sync failed.
      _ = try? await store.saveContact(
        radioID: radioID, from: makeContactFrame(publicKey: key, name: "Ghost")
      )
      return .failed
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let waiting = await waitUntil { await hold.isWaiting }
    #expect(waiting)

    await session.yieldEvent(.contactDeleted(publicKey: key))
    // Let the 0x8F handler record the key before the commit returns.
    try? await Task.sleep(for: .milliseconds(40))
    await hold.release()

    let roundDone = await waitUntil { await service.deltaSyncTask == nil }
    #expect(roundDone)

    let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
    #expect(contact == nil, "a row committed by a failed sync must still be rolled back")
    #expect(
      await !service.pendingAdvertKeys.contains(key),
      "a radio-deleted key must not re-queue for a fetch the radio cannot answer"
    )

    await service.stopEventMonitoring()
  }

  @Test
  func `rollback still runs when teardown cancels the sync mid-commit`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x4D)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Known")
    )

    let hold = HandlerHold()
    let marker = CommitMarker()
    await service.setDeltaSyncHandler { _ in
      await hold.waitUntilReleased()
      // The batch commit re-saves a row the radio deleted while it was in flight.
      _ = try? await store.saveContact(
        radioID: radioID, from: makeContactFrame(publicKey: key, name: "Ghost")
      )
      await marker.markCommitted()
      return .synced
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let waiting = await waitUntil { await hold.isWaiting }
    #expect(waiting)

    await session.yieldEvent(.contactDeleted(publicKey: key))
    let deleted = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) == nil
    }
    #expect(deleted)

    // Teardown cancels the round and clears the handler while the commit runs.
    await service.stopEventMonitoring()
    await hold.release()

    let committed = await waitUntil { await marker.committed }
    #expect(committed)

    let rolledBack = await waitUntil {
      await (try? store.fetchContact(radioID: radioID, publicKey: key)) == nil
    }
    #expect(rolledBack, "a commit landing after teardown must still be rolled back")
  }

  @Test
  func `contact deleted after the commit returns stays tracked for the round`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let advertKey = makePublicKey(seed: 0x4E)
    let deletedKey = makePublicKey(seed: 0x4F)
    await recorder.enqueuePersist(makeContactFrame(publicKey: advertKey, name: "Synced"))
    await installHandler(service, recorder: recorder)

    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: deletedKey, name: "Doomed")
    )

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: advertKey))
    let committed = await waitUntil { await recorder.callCount >= 1 }
    #expect(committed)

    await session.yieldEvent(.contactDeleted(publicKey: deletedKey))
    let tracked = await waitUntil {
      await service.contactsDeletedDuringSync.contains(deletedKey)
    }
    await service.stopEventMonitoring()
    #expect(tracked, "a delete outside the commit must still be tracked for the round")
  }

  @Test
  func `reconcile skips a contact the radio deleted this round`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x50)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Doomed")
    )

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    // No handler installed, so no round drains the recorded delete before reconcile.
    await startMonitoring(service, session: session)
    await session.yieldEvent(.contactDeleted(publicKey: key))
    let tracked = await waitUntil {
      await service.contactsDeletedDuringSync.contains(key)
    }
    #expect(tracked)

    // A batch commit re-saved the row the radio dropped.
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Ghost")
    )
    await service.reconcile([key], insertedKeys: [key])

    let nodes = try await store.fetchDiscoveredNodes(radioID: radioID)
    #expect(nodes.isEmpty, "a deleted key must not gain a Discover row")
    try? await Task.sleep(for: .milliseconds(30))
    #expect(await counter.newContactCount == 0, "a deleted key must not be announced")

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result
  }

  @Test
  func `rolled back key does not escalate to a full refetch`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x52)
    let hold = HandlerHold()
    await service.setDeltaSyncHandler { fullRefetch in
      let isFirst = await recorder.callCount == 0
      let result = await recorder.handle(fullRefetch: fullRefetch)
      if isFirst {
        await hold.waitUntilReleased()
        _ = try? await store.saveContact(
          radioID: radioID, from: makeContactFrame(publicKey: key, name: "Ghost")
        )
      }
      return result
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let waiting = await waitUntil { await hold.isWaiting }
    #expect(waiting)

    await session.yieldEvent(.contactDeleted(publicKey: key))
    let tracked = await waitUntil {
      await service.contactsDeletedDuringSync.contains(key)
    }
    #expect(tracked)
    await hold.release()

    let roundDone = await waitUntil { await service.deltaSyncTask == nil }
    #expect(roundDone)
    try? await Task.sleep(for: .milliseconds(80))
    await service.stopEventMonitoring()

    #expect(await recorder.fullRefetchFlags == [false], "a rolled-back key must not refetch")
    let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
    #expect(contact == nil)
  }

  // MARK: - notReady outcome

  @Test
  func `notReady outcome drops drained keys without reschedule or budget spend`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await recorder.enqueueResult(.notReady)
    // A second result would only run if notReady incorrectly re-armed.
    await recorder.enqueueResult(.synced)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x57)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "NotReady")
    )

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)
    try? await Task.sleep(for: .milliseconds(80))
    await service.stopEventMonitoring()

    #expect(await service.pendingAdvertKeys.isEmpty, "notReady must drop drained advert keys")
    #expect(await recorder.callCount == 1, "notReady must not schedule another round")
    #expect(await service.consecutiveDeltaSyncFailures == 0)
    #expect(await service.lastDeltaSyncEnd == nil, "notReady must not stamp lastDeltaSyncEnd")
  }

  // MARK: - Failure cap

  @Test
  func `repeated failures stop the retry loop`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    let cap = AdvertisementService.maxConsecutiveDeltaSyncFailures
    for _ in 0..<(cap + 3) {
      await recorder.enqueueResult(.failed)
    }
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x53)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Flaky")
    )

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let capped = await waitUntil { await recorder.callCount >= cap }
    #expect(capped)
    try? await Task.sleep(for: .milliseconds(120))
    await service.stopEventMonitoring()

    #expect(await recorder.callCount == cap, "retries must stop at the failure cap")
    #expect(await service.pendingAdvertKeys.isEmpty, "the capped round drops its drained keys")
  }

  @Test
  func `busy rounds keep their keys past the failure cap`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    let cap = AdvertisementService.maxConsecutiveDeltaSyncFailures
    for _ in 0..<(cap + 2) {
      await recorder.enqueueResult(.busy)
    }

    // The key has no local row, so only the round that carries it can announce it.
    let key = makePublicKey(seed: 0x54)
    await recorder.enqueuePersist(makeContactFrame(publicKey: key, name: "Late"))
    await installHandler(service, recorder: recorder)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let discovered = await waitUntil { await counter.newContactCount >= 1 }
    #expect(discovered, "the drained key must survive collisions and reach the round that syncs it")
    #expect(
      await recorder.callCount > cap,
      "a claim collision never reached the radio, so it must not spend the failure budget"
    )

    await service.stopEventMonitoring()
    listener.cancel()
  }

  // MARK: - Path-only sync

  @Test
  func `pathUpdate deferred by contact sync still runs`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    await service.setSyncingContacts(true)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.pathUpdate(publicKey: makePublicKey(seed: 0x63)))

    try? await Task.sleep(for: .milliseconds(50))
    #expect(await recorder.callCount == 0)

    await service.setSyncingContacts(false)
    let ran = await waitUntil { await recorder.callCount >= 1 }
    await service.stopEventMonitoring()
    #expect(ran, "a path update that fires while syncing must re-arm the delta sync")
  }

  // MARK: - Contact-row newness gates the new-contact notification

  @Test
  func `re-created contact with a surviving Discover row notifies again`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x74)
    let frame = makeContactFrame(publicKey: key, name: "Returning")
    // Deleting a contact leaves its Discover row. A re-advert for a missing
    // Contact row is absent from the pre-round snapshot, so after the handler
    // inserts the Contact it is in insertedKeys and notifies again.
    _ = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)
    await recorder.enqueuePersist(frame)
    await installHandler(service, recorder: recorder)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let discovered = await waitUntil { await counter.newContactCount >= 1 }
    #expect(discovered, "a re-created Contact row must notify even when its Discover row survived")
    try? await Task.sleep(for: .milliseconds(40))

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result

    let contact = try await store.fetchContact(radioID: radioID, publicKey: key)
    #expect(contact != nil, "the handler must have persisted the contact")
    #expect(await counter.newContactCount == 1, "one insert must not notify twice")
  }

  // MARK: - Store errors

  @Test
  func `advert retries touch once then drops key when both fail`() async {
    // Empty-round guard drops rounds with no recorded keys. A failed touch
    // cannot invent known/unknown, so record nothing and retry once; the
    // tradeoff is a permanently-failing store drops the advert until the next
    // successful touch.
    let store = MockPersistenceStore()
    await store.setStubbedTouchContactHeardError(AdvertisementServiceTestError.storeUnavailable)
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: makePublicKey(seed: 0x85)))

    let retried = await waitUntil { await store.touchContactHeardCalls.count >= 2 }
    #expect(retried, "touch must be retried once before giving up")
    try? await Task.sleep(for: .milliseconds(80))
    await service.stopEventMonitoring()
    #expect(await recorder.callCount == 0, "empty-round guard must skip when no key was recorded")
    #expect(await store.touchContactHeardCalls.count == 2)
  }

  @Test
  func `capped failure round restores pathSyncPending`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    let cap = AdvertisementService.maxConsecutiveDeltaSyncFailures
    for _ in 0..<cap {
      await recorder.enqueueResult(.failed)
    }
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x86)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Cap")
    )

    await startMonitoring(service, session: session)
    await service.setSyncingContacts(true)
    await session.yieldEvent(.pathUpdate(publicKey: key))
    await session.yieldEvent(.advertisement(publicKey: key))
    await service.setSyncingContacts(false)

    let capped = await waitUntil { await recorder.callCount >= cap }
    #expect(capped)
    try? await Task.sleep(for: .milliseconds(80))
    // Cap drops drained keys but must restore pathSyncPending so a path update
    // is not silently lost. The next advert or path event re-arms.
    #expect(await service.pathSyncPending)
    #expect(await service.pendingAdvertKeys.isEmpty)
    await service.stopEventMonitoring()
  }

  @Test
  func `capped failure round restores escalateToFullRefetch`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    let cap = AdvertisementService.maxConsecutiveDeltaSyncFailures
    // First success with no local row escalates; then fail to the cap so the
    // final drop restores escalateToFullRefetch from the drained fullRefetch flag.
    await recorder.enqueueResult(.synced)
    for _ in 0..<cap {
      await recorder.enqueueResult(.failed)
    }
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x88)
    // No Contact row: successful incremental still leaves the key missing → escalate.

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let capped = await waitUntil(timeout: .seconds(3)) {
      await recorder.callCount >= 1 + cap
    }
    #expect(capped)
    try? await Task.sleep(for: .milliseconds(80))
    #expect(
      await service.escalateToFullRefetch,
      "cap must restore escalateToFullRefetch when the drained round was a full refetch"
    )
    #expect(await service.pendingAdvertKeys.isEmpty)
    await service.stopEventMonitoring()
  }

  @Test
  func `fresh advert during the capped failing round re-arms and drains`() async throws {
    // An 0x80 that lands while the final failing round is awaited already spent
    // its one no-op scheduleDeltaSync (the task was still set). The cap must
    // detect that fresh key and re-arm, not strand it until an unrelated event.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let cap = AdvertisementService.maxConsecutiveDeltaSyncFailures

    let firstKey = makePublicKey(seed: 0x71)
    let freshKey = makePublicKey(seed: 0x72)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: firstKey, name: "First")
    )

    let injector = CapRoundInjector(service: service, freshKey: freshKey, cap: cap)
    await service.setDeltaSyncHandler { _ in await injector.handle() }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: firstKey))

    let rearmed = await waitUntil(timeout: .seconds(3)) { await injector.calls > cap }
    #expect(rearmed, "a fresh advert during the capped round must re-arm the sync")
    let drained = await waitUntil { await service.pendingAdvertKeys.isEmpty }
    #expect(drained, "the re-armed round drains the fresh advert key")

    await service.stopEventMonitoring()
  }

  @Test
  func `setSyncingContacts false re-arms owed full refetch with empty pending keys`() async throws {
    // hasPendingDeltaSyncWork and the empty-round guard must agree: an owed
    // escalateToFullRefetch alone is work. After connect/manual sync toggles
    // isSyncingContacts, re-arm must not require pendingAdvertKeys.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    let cap = AdvertisementService.maxConsecutiveDeltaSyncFailures
    await recorder.enqueueResult(.synced)
    for _ in 0..<cap {
      await recorder.enqueueResult(.failed)
    }
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x91)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let capped = await waitUntil(timeout: .seconds(3)) {
      await recorder.callCount >= 1 + cap
    }
    #expect(capped)
    let escalated = await waitUntil {
      let escalate = await service.escalateToFullRefetch
      let emptyKeys = await service.pendingAdvertKeys.isEmpty
      let idle = await service.deltaSyncTask == nil
      return escalate && emptyKeys && idle
    }
    #expect(escalated)
    #expect(await !service.pathSyncPending)

    let callsBeforeRearm = await recorder.callCount
    await service.setSyncingContacts(true)
    await service.setSyncingContacts(false)

    let rearmed = await waitUntil(timeout: .seconds(2)) {
      await recorder.callCount > callsBeforeRearm
    }
    await service.stopEventMonitoring()
    #expect(rearmed, "owed full refetch must re-arm when contact sync ends")
    #expect(
      await recorder.fullRefetchFlags.last == true,
      "re-armed round must run as prune-free full refetch"
    )
  }

  @Test
  func `setDeltaSyncHandler re-arms owed full refetch with empty pending keys`() async throws {
    // Same drift as setSyncingContacts: handler rewiring after a failed initial
    // sync must re-arm when only escalateToFullRefetch remains.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    let cap = AdvertisementService.maxConsecutiveDeltaSyncFailures
    await recorder.enqueueResult(.synced)
    for _ in 0..<cap {
      await recorder.enqueueResult(.failed)
    }
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x92)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let capped = await waitUntil(timeout: .seconds(3)) {
      await recorder.callCount >= 1 + cap
    }
    #expect(capped)
    let escalated = await waitUntil {
      let escalate = await service.escalateToFullRefetch
      let emptyKeys = await service.pendingAdvertKeys.isEmpty
      let idle = await service.deltaSyncTask == nil
      return escalate && emptyKeys && idle
    }
    #expect(escalated)

    let callsBeforeRearm = await recorder.callCount
    await service.setDeltaSyncHandler(nil)
    await installHandler(service, recorder: recorder)

    let rearmed = await waitUntil(timeout: .seconds(2)) {
      await recorder.callCount > callsBeforeRearm
    }
    await service.stopEventMonitoring()
    #expect(rearmed, "reinstalling the handler must re-arm an owed full refetch")
    #expect(await recorder.fullRefetchFlags.last == true)
  }

  @Test
  func `finishRound with stale generation leaves the new task registered`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(
      session: session,
      store: store,
      advertSyncDebounce: .seconds(30),
      advertSyncMinInterval: .zero
    )
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x89)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Gen")
    )
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let scheduled = await waitUntil { await service.deltaSyncTask != nil }
    #expect(scheduled)
    let liveGeneration = await service.deltaSyncGeneration
    // A stale finishRound (cancelled prior round) must not wipe the live task.
    await service.finishRound(generation: liveGeneration &- 1)
    #expect(await service.deltaSyncTask != nil)

    // Matching generation clears as designed.
    await service.finishRound(generation: liveGeneration)
    #expect(await service.deltaSyncTask == nil)
    await service.stopEventMonitoring()
  }

  @Test
  func `setDeltaSyncHandler nil then reinstall still syncs pending keys`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x8A)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Reinstall")
    )
    await startMonitoring(service, session: session)
    await service.setDeltaSyncHandler(nil)
    await session.yieldEvent(.advertisement(publicKey: key))
    try? await Task.sleep(for: .milliseconds(40))
    #expect(await recorder.callCount == 0)
    #expect(await service.pendingAdvertKeys.contains(key))

    await installHandler(service, recorder: recorder)
    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran, "reinstalling a handler must re-arm for already-pending keys")
    await service.stopEventMonitoring()
  }

  @Test
  func `snapshot fetch failure does not invent new-contact notifications`() async throws {
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x8B)
    // Known local contact: a failed snapshot must not treat it as inserted.
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Known")
    )
    await recorder.enqueueResult(.synced)
    await installHandler(service, recorder: recorder)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    // Fail only the snapshot read used by runDeltaSync (not touch / reconcile).
    await store.setStubbedFetchContactPublicKeysError(AdvertisementServiceTestError.storeUnavailable)
    await session.yieldEvent(.advertisement(publicKey: key))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)
    try? await Task.sleep(for: .milliseconds(80))

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result

    #expect(
      await counter.newContactCount == 0,
      "snapshot failure must suppress new-contact notifications (prefer miss over false notify)"
    )
  }

  @Test
  func `snapshot fetch failure still adopts orphaned DMs for drained keys`() async throws {
    // A failed pre-round snapshot must not set adoption keys to empty.
    // The handler still inserts the Contact; orphan DMs must link after the round.
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x8C)
    let prefix = Data(key.prefix(6))
    let messageID = UUID()
    try await store.saveMessage(
      MessageDTO(
        id: messageID,
        radioID: radioID,
        contactID: nil,
        channelIndex: nil,
        text: "orphan before delta",
        timestamp: 1_700_000_400,
        createdAt: Date(),
        direction: .incoming,
        status: .delivered,
        textType: .plain,
        ackCode: nil,
        pathLength: 0,
        snr: nil,
        pathNodes: nil,
        senderKeyPrefix: prefix,
        senderNodeName: nil,
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        sendCount: 1,
        retryAttempt: 0,
        maxRetryAttempts: 0
      )
    )

    await recorder.enqueuePersist(makeContactFrame(publicKey: key, name: "LateNode"))
    await recorder.enqueueResult(.synced)
    await installHandler(service, recorder: recorder)
    await startMonitoring(service, session: session)
    await store.setStubbedFetchContactPublicKeysError(AdvertisementServiceTestError.storeUnavailable)
    await session.yieldEvent(.advertisement(publicKey: key))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)
    let linked = await waitUntil {
      await store.messages[messageID]?.contactID != nil
    }
    await service.stopEventMonitoring()
    #expect(linked, "snapshot failure must not permanently orphan DMs already received")

    let contact = try #require(await store.fetchContact(radioID: radioID, publicKey: key))
    #expect(await store.messages[messageID]?.contactID == contact.id)
    #expect(contact.unreadCount == 1)
  }

  @Test
  func `adopting orphaned DMs emits conversationsChanged`() async throws {
    // Adoption stamps lastMessageDate, which creates a conversation row.
    // The mounted chat list reloads on conversationsVersion, not contactsVersion,
    // so the service must emit conversationsChanged when any message is linked.
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x8E)
    let prefix = Data(key.prefix(6))
    let messageID = UUID()
    try await store.saveMessage(
      MessageDTO(
        id: messageID,
        radioID: radioID,
        contactID: nil,
        channelIndex: nil,
        text: "orphan needs conversation signal",
        timestamp: 1_700_000_500,
        createdAt: Date(),
        direction: .incoming,
        status: .delivered,
        textType: .plain,
        ackCode: nil,
        pathLength: 0,
        snr: nil,
        pathNodes: nil,
        senderKeyPrefix: prefix,
        senderNodeName: nil,
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        sendCount: 1,
        retryAttempt: 0,
        maxRetryAttempts: 0
      )
    )

    await recorder.enqueuePersist(makeContactFrame(publicKey: key, name: "AdoptedDM"))
    await recorder.enqueueResult(.synced)
    await installHandler(service, recorder: recorder)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)
    let linked = await waitUntil {
      await store.messages[messageID]?.contactID != nil
    }
    #expect(linked)
    let signaled = await waitUntil { await counter.conversationsChangedCount >= 1 }
    #expect(signaled, "adoption must emit conversationsChanged so the chat list reloads")

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result

    #expect(await counter.conversationsChangedCount >= 1)
    #expect(await counter.contactUpdatedCount >= 1, "contactUpdated must still fire for Discover")
  }

  @Test
  func `adopting orphaned DMs emits orphanDirectMessagesAdopted with the contact`() async throws {
    // The adopted DM never notified at receipt (no contact then). The service
    // must hand the adopting contact to the NotificationService owner so the
    // banner and badge fire; a bare conversationsChanged does neither.
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x9E)
    let prefix = Data(key.prefix(6))
    let messageID = UUID()
    try await store.saveMessage(
      MessageDTO(
        id: messageID,
        radioID: radioID,
        contactID: nil,
        channelIndex: nil,
        text: "orphan awaiting notification",
        timestamp: 1_700_000_600,
        createdAt: Date(),
        direction: .incoming,
        status: .delivered,
        textType: .plain,
        ackCode: nil,
        pathLength: 0,
        snr: nil,
        pathNodes: nil,
        senderKeyPrefix: prefix,
        senderNodeName: nil,
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        sendCount: 1,
        retryAttempt: 0,
        maxRetryAttempts: 0
      )
    )

    await recorder.enqueuePersist(makeContactFrame(publicKey: key, name: "AdoptedDM"))
    await recorder.enqueueResult(.synced)
    await installHandler(service, recorder: recorder)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let linked = await waitUntil { await store.messages[messageID]?.contactID != nil }
    #expect(linked)
    let contact = try #require(await store.fetchContact(radioID: radioID, publicKey: key))
    let announced = await waitUntil { await counter.adoptedContactIDs.contains(contact.id) }
    #expect(announced, "adoption must announce the contact so the banner and badge fire")

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result
  }

  @Test
  func `delta round with no adoption does not emit conversationsChanged`() async throws {
    // A successful round that links zero orphan DMs must not force a chat-list
    // reload. contactUpdated still fires for Discover.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x8F)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "NoOrphans")
    )
    await recorder.enqueueResult(.synced)
    await installHandler(service, recorder: recorder)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)
    let updated = await waitUntil { await counter.contactUpdatedCount >= 1 }
    #expect(updated)
    // Yield to the event loop so a wrongly unconditional emit would be observed.
    try? await Task.sleep(for: .milliseconds(80))

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result

    #expect(
      await counter.conversationsChangedCount == 0,
      "rounds that adopt nothing must not emit conversationsChanged"
    )
  }

  @Test
  func `materializeContactForPendingAdvert creates contact for unique pending key`() async throws {
    // A DM in the debounce window needs a Contact row immediately so the live
    // notify path can run. Materialize from the pending 0x80 key.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0x8D)
    let mesh = makeMeshContact(publicKey: key, name: "DebounceNode")
    await session.setStubbedContact(mesh, for: key)

    // No delta handler: advert records the pending key and never inserts a row.
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))
    let pending = await waitUntil { await service.pendingAdvertKeys.contains(key) }
    #expect(pending)
    #expect(try await store.fetchContact(radioID: radioID, publicKey: key) == nil)

    let prefix = Data(key.prefix(6))
    let contact = try #require(
      await service.materializeContactForPendingAdvert(matchingPrefix: prefix, radioID: radioID)
    )
    #expect(contact.publicKey == key)
    #expect(contact.name == "DebounceNode")
    #expect(await service.pendingAdvertKeys.contains(key), "key must stay pending for delta reconcile")
    #expect(await session.getContactPublicKeys.contains(key))

    // Prefix lookup is what MessagePollingService uses for the next DM hop.
    let byPrefix = try await store.fetchContact(radioID: radioID, publicKeyPrefix: prefix)
    #expect(byPrefix?.id == contact.id)
    await service.stopEventMonitoring()
  }

  @Test
  func `materializeContactForPendingAdvert returns nil without pending match`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let key = makePublicKey(seed: 0x8E)
    await session.setStubbedContact(makeMeshContact(publicKey: key, name: "NoPending"), for: key)

    await startMonitoring(service, session: session)
    let result = await service.materializeContactForPendingAdvert(
      matchingPrefix: Data(key.prefix(6)),
      radioID: radioID
    )
    #expect(result == nil)
    #expect(await session.getContactPublicKeys.isEmpty)
    await service.stopEventMonitoring()
  }

  @Test
  func `materializeContactForPendingAdvert returns nil on multi-match prefix`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    // Two 32-byte keys that share the same 1-byte prefix used for the query.
    var keyA = makePublicKey(seed: 0x8F)
    var keyB = makePublicKey(seed: 0x90)
    keyA[0] = 0xAB
    keyB[0] = 0xAB
    #expect(keyA != keyB)

    await service.recordPendingAdvertKey(keyA)
    await service.recordPendingAdvertKey(keyB)
    let result = await service.materializeContactForPendingAdvert(
      matchingPrefix: Data([0xAB]),
      radioID: radioID
    )
    #expect(result == nil)
    #expect(await session.getContactPublicKeys.isEmpty)
  }

  @Test
  func `empty schedule with no pending work does not call the handler`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    await startMonitoring(service, session: session)
    // Schedule without any pending keys/path: empty-round guard must no-op.
    await service.scheduleDeltaSync()
    try? await Task.sleep(for: .milliseconds(80))
    #expect(await recorder.callCount == 0)
    await service.stopEventMonitoring()
  }

  @Test
  func `busy outcome does not stamp min-interval when backoff is non-zero`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    // Busy must not stamp lastDeltaSyncEnd; a non-zero min interval would block re-arm.
    let service = makeService(
      session: session,
      store: store,
      advertSyncDebounce: .zero,
      advertSyncMinInterval: .seconds(30),
      advertSyncBusyBackoff: .milliseconds(20)
    )
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await recorder.enqueueResult(.busy)
    await recorder.enqueueResult(.synced)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x87)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "Busy")
    )
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let second = await waitUntil(timeout: .seconds(2)) {
      await recorder.callCount >= 2
    }
    await service.stopEventMonitoring()
    #expect(second, "busy must re-arm on busy backoff, not the 30s min interval")
  }

  @Test
  func `touch failure does not announce a known contact as new`() async throws {
    // A failed touch cannot tell known from unknown, so the key is not recorded
    // after the one retry. Empty-round guard then skips the handler — prefer
    // losing the round over inventing a new-contact notification.
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0x51)
    _ = try await store.saveContact(
      radioID: radioID, from: makeContactFrame(publicKey: key, name: "LongKnown")
    )
    await store.setStubbedTouchContactHeardError(AdvertisementServiceTestError.storeUnavailable)

    let counter = EventCounter()
    let events = service.events()
    let listener = Task {
      for await event in events {
        await counter.note(event)
      }
    }

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let retried = await waitUntil { await store.touchContactHeardCalls.count >= 2 }
    #expect(retried)
    try? await Task.sleep(for: .milliseconds(60))

    await service.stopEventMonitoring()
    service.finishEvents()
    _ = await listener.result

    #expect(await recorder.callCount == 0, "no key recorded means empty-round guard skips")
    #expect(
      await counter.newContactCount == 0,
      "a store error must not record a known contact as unknown"
    )
  }

  @Test
  func `contact lookup failure does not escalate to full refetch`() async {
    let store = MockPersistenceStore()
    await store.setStubbedFetchContactError(AdvertisementServiceTestError.storeUnavailable)
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: makePublicKey(seed: 0x96)))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)
    try? await Task.sleep(for: .milliseconds(80))
    await service.stopEventMonitoring()

    #expect(await recorder.callCount == 1, "a local read failure must not trigger a radio refetch")
    #expect(await recorder.fullRefetchFlags == [false])
  }

  // MARK: - Prune safety

  @Test
  func `fresh lastHeard protects contact with old lastModified from prune`() async throws {
    let store = try await makeStore()
    let oldStamp: UInt32 = 1_000_000
    let freshStamp = UInt32(Date().timeIntervalSince1970)
    let key = makePublicKey(seed: 0x5A)

    try await store.saveContact(ContactDTO.testContact(
      radioID: radioID,
      publicKey: key,
      name: "StaleRadio",
      lastModified: oldStamp,
      lastHeardTimestamp: freshStamp
    ))

    // Production path: removeStaleNodes uses matchesStaleNodePrune on fetched DTOs.
    let fetched = try #require(await store.fetchContact(radioID: radioID, publicKey: key))
    let days = 30
    let cutoff = UInt32(Date().addingTimeInterval(-Double(days) * 86400).timeIntervalSince1970)

    #expect(fetched.lastModified < cutoff)
    #expect(!fetched.matchesStaleNodePrune(cutoff: cutoff))

    // Control: old lastHeard and lastModified both stale → would prune.
    let staleKey = makePublicKey(seed: 0x5B)
    try await store.saveContact(ContactDTO.testContact(
      radioID: radioID,
      publicKey: staleKey,
      name: "TrulyStale",
      lastModified: oldStamp,
      lastHeardTimestamp: oldStamp
    ))
    let trulyStale = try #require(await store.fetchContact(radioID: radioID, publicKey: staleKey))
    #expect(trulyStale.matchesStaleNodePrune(cutoff: cutoff))
  }

  @Test
  func `unknown advert contact stamped lastHeard survives stale-node prune`() async throws {
    // A 0x80 for a key with no local row cannot touch lastHeard until the delta
    // insert. Contact(radioID:from:) hardcodes lastHeard = 0; without a post-insert
    // stamp, a stale radio lastModified makes matchesStaleNodePrune true.
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)

    let key = makePublicKey(seed: 0x5C)
    let oldStamp: UInt32 = 1_000_000
    let oldDate = Date(timeIntervalSince1970: TimeInterval(oldStamp))
    await recorder.enqueuePersist(
      makeContactFrame(
        publicKey: key,
        name: "JustHeard",
        lastAdvertTimestamp: oldStamp,
        lastModified: oldStamp
      )
    )
    await installHandler(service, recorder: recorder)

    let beforeAdvert = Date().addingTimeInterval(-1)
    await startMonitoring(service, session: session)
    await session.yieldEvent(.advertisement(publicKey: key))

    let stamped = await waitUntil {
      let contact = try? await store.fetchContact(radioID: radioID, publicKey: key)
      return (contact?.lastHeardTimestamp ?? 0) > 0
    }
    #expect(stamped, "post-delta stamp must set lastHeardTimestamp after insert")

    let contact = try #require(await store.fetchContact(radioID: radioID, publicKey: key))
    let pruneDays = 30
    let secondsPerDay: TimeInterval = 86400
    let cutoff = UInt32(
      Date().addingTimeInterval(-Double(pruneDays) * secondsPerDay).timeIntervalSince1970
    )

    #expect(contact.lastModified == oldStamp)
    #expect(contact.lastModified < cutoff)
    #expect((contact.lastHeardTimestamp ?? 0) >= UInt32(beforeAdvert.timeIntervalSince1970))
    #expect(contact.recencyTimestamp >= UInt32(beforeAdvert.timeIntervalSince1970))
    #expect(!contact.matchesStaleNodePrune(cutoff: cutoff))

    // The radio-sourced timestamps alone would still fall before the cutoff.
    #expect(oldDate.timeIntervalSince1970 < Double(cutoff))

    await service.stopEventMonitoring()
  }

  @Test
  func `favorite contact with stale recency does not match stale-node prune`() {
    let oldStamp: UInt32 = 1_000_000
    let pruneDays = 30
    let secondsPerDay: TimeInterval = 86400
    let cutoff = UInt32(
      Date().addingTimeInterval(-Double(pruneDays) * secondsPerDay).timeIntervalSince1970
    )
    let favorite = ContactDTO.testContact(
      radioID: radioID,
      publicKey: makePublicKey(seed: 0x5D),
      name: "FavoriteStale",
      lastModified: oldStamp,
      lastHeardTimestamp: oldStamp,
      isFavorite: true
    )
    #expect(favorite.recencyTimestamp < cutoff)
    #expect(!favorite.matchesStaleNodePrune(cutoff: cutoff))
  }

  // MARK: - Path discovery response lastHeard

  @Test
  func `pathResponse stamps lastHeard and preserves radio lastModified`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)

    let key = makePublicKey(seed: 0xA5)
    let radioLastMod: UInt32 = 1_700_000_100
    _ = try await store.saveContact(
      radioID: radioID,
      from: makeContactFrame(
        publicKey: key,
        name: "PathPeer",
        lastModified: radioLastMod
      )
    )

    await startMonitoring(service, session: session)

    let pathInfo = PathInfo(
      publicKeyPrefix: Data(key.prefix(6)),
      outPathLength: 0,
      outPath: Data(),
      inPathLength: 0,
      inPath: Data()
    )
    await session.yieldEvent(.pathResponse(pathInfo))

    let stamped = await waitUntil {
      guard let contact = try? await store.fetchContact(radioID: radioID, publicKey: key) else {
        return false
      }
      return (contact.lastHeardTimestamp ?? 0) > 0
    }
    #expect(stamped)

    let updated = try #require(
      await store.fetchContact(radioID: radioID, publicKey: key)
    )
    #expect(updated.lastModified == radioLastMod)
    #expect((updated.lastHeardTimestamp ?? 0) > 0)

    await service.stopEventMonitoring()
  }

  @Test
  func `pathUpdate does not stamp lastHeard`() async throws {
    let store = try await makeStore()
    let session = MockMeshCoreSession()
    let service = makeService(session: session, store: store)
    let recorder = HandlerRecorder(store: store, radioID: radioID)
    await installHandler(service, recorder: recorder)

    let key = makePublicKey(seed: 0xA6)
    _ = try await store.saveContact(
      radioID: radioID,
      from: makeContactFrame(
        publicKey: key,
        name: "PathUpdatePeer",
        lastModified: 1_700_000_100
      )
    )

    await startMonitoring(service, session: session)
    await session.yieldEvent(.pathUpdate(publicKey: key))

    let ran = await waitUntil { await recorder.callCount >= 1 }
    #expect(ran)

    let updated = try #require(
      await store.fetchContact(radioID: radioID, publicKey: key)
    )
    #expect((updated.lastHeardTimestamp ?? 0) == 0)

    await service.stopEventMonitoring()
  }
}

// MARK: - Concurrency helpers

private actor CommitMarker {
  private(set) var committed = false

  func markCommitted() {
    committed = true
  }
}

private actor HandlerHold {
  private var continuation: CheckedContinuation<Void, Never>?
  private(set) var isWaiting = false
  /// Sticky: once released, later `waitUntilReleased` calls return immediately so a
  /// follow-up delta round (re-arm / escalate) does not hang on a one-shot gate.
  private var isReleased = false

  func waitUntilReleased() async {
    if isReleased { return }
    isWaiting = true
    await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
      if isReleased {
        cont.resume()
      } else {
        continuation = cont
      }
    }
    isWaiting = false
  }

  func release() {
    isReleased = true
    let cont = continuation
    continuation = nil
    cont?.resume()
  }
}
