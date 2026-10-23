import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("ChatSendQueueService")
@MainActor
struct ChatSendQueueServiceTests {
  private static func makeStore() throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private static func makeMessageService(dataStore: PersistenceStore) async -> MessageService {
    let transport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: transport)
    return MessageService(session: session, dataStore: dataStore, contactService: nil)
  }

  private static func makeChannelService(dataStore: PersistenceStore) async -> ChannelService {
    let transport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: transport)
    return ChannelService(session: session, dataStore: dataStore, rxLogService: nil)
  }

  /// PendingSend row pointing at a contact that was deleted between
  /// enqueue and hydrate. The send closure's contact lookup fails,
  /// which is the "drop envelope" path — the row is purged without
  /// calling `sendPendingDirectMessage`. This exercises hydrate's queue
  /// loading and the queue's drain → onError → row-cleanup path on
  /// the simplest available transport-independent surface.
  @Test
  func `hydrate replays persisted PendingSend rows and drains them`() async throws {
    let store = try Self.makeStore()
    let radioID = UUID()
    let messageID = UUID()
    let contactID = UUID()
    let envelope = DirectMessageEnvelope(messageID: messageID, contactID: contactID)
    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envelope, radioID: radioID)
    )

    let preRows = try await store.fetchPendingSends(radioID: radioID)
    #expect(preRows.count == 1, "fixture should have inserted exactly one row")

    let messageService = await Self.makeMessageService(dataStore: store)
    let channelService = await Self.makeChannelService(dataStore: store)
    let service = ChatSendQueueService(
      radioID: radioID,
      dataStore: store,
      messageService: messageService,
      channelService: channelService,
      reactionService: ReactionService()
    )

    await service.hydrate()
    await service.awaitDrainCompletion()

    let rowsAfter = try await store.fetchPendingSends(radioID: radioID)
    #expect(rowsAfter.isEmpty, "hydrate + drain should clear the persisted row")
  }

  /// A `PendingSend` row whose send fails with a transient transport
  /// error must survive the failure — the closure parks in
  /// `withCooperativeTimeout` on `triggers.wait(forAnyOf:)`, and the
  /// row stays on disk until either the trigger fires or the deadline
  /// elapses. Without `transportDidOpen()`, the wait suspends.
  @Test
  func `transient send error preserves the persisted row while parked on the trigger`() async throws {
    let device = Device(
      publicKey: Data(repeating: 1, count: 32),
      nodeName: "Test Device"
    )
    let container = try PersistenceStore.createContainer(inMemory: true)
    container.mainContext.insert(device)
    try container.mainContext.save()
    let store = PersistenceStore(modelContainer: container)

    let radioID = device.id
    let contact = Contact(
      radioID: radioID,
      publicKey: Data(repeating: 2, count: 32),
      name: "Test Contact",
      lastHeardTimestamp: 0
    )
    container.mainContext.insert(contact)
    try container.mainContext.save()
    let contactDTO = try #require(try await store.fetchContact(id: contact.id))

    let transport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: transport)
    let messageService = MessageService(session: session, dataStore: store, contactService: nil)
    let message = try await messageService.createPendingMessage(text: "Hello", to: contactDTO)

    let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contactDTO.id)
    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envelope, radioID: radioID)
    )

    let channelService = ChannelService(session: session, dataStore: store, rxLogService: nil)
    let service = ChatSendQueueService(
      radioID: radioID,
      dataStore: store,
      messageService: messageService,
      channelService: channelService,
      reactionService: ReactionService()
    )

    await service.hydrate()
    // Give the drain time to call sendPendingDirectMessage and throw
    // a transient transport error, parking inside withCooperativeTimeout.
    try? await Task.sleep(for: .milliseconds(200))
    let rowsMidFlight = try await store.fetchPendingSends(radioID: radioID)
    #expect(rowsMidFlight.count == 1,
            "transient error must not delete the row while suspended on the trigger")

    // Release the queue so the actor can deinit at test scope end —
    // otherwise its drain task suspends for up to `transportWaitTimeout`
    // before the cooperative timeout fires.
    await service.shutdown()
  }

  /// `hydrate` must filter by the service's `radioID`. A row inserted
  /// for a different radio must not flow into this service's queues
  /// (which would either send the wrong envelope or, at minimum,
  /// delete the foreign row when the contact lookup fails on the
  /// drop path). Regression guard against a future "optimization"
  /// that drops the `radioID` predicate from `fetchPendingSends`.
  @Test
  func `hydrate processes only the service's own radio rows`() async throws {
    let store = try Self.makeStore()
    let radioA = UUID()
    let radioB = UUID()
    let envA1 = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())
    let envA2 = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())
    let envB = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())

    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envA1, radioID: radioA)
    )
    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envA2, radioID: radioA)
    )
    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envB, radioID: radioB)
    )

    let messageService = await Self.makeMessageService(dataStore: store)
    let channelService = await Self.makeChannelService(dataStore: store)
    let service = ChatSendQueueService(
      radioID: radioA,
      dataStore: store,
      messageService: messageService,
      channelService: channelService,
      reactionService: ReactionService()
    )

    await service.hydrate()
    await service.awaitDrainCompletion()

    let radioARowsAfter = try await store.fetchPendingSends(radioID: radioA)
    let radioBRowsAfter = try await store.fetchPendingSends(radioID: radioB)
    #expect(radioARowsAfter.isEmpty,
            "hydrate must drain only the service's own radio rows")
    #expect(radioBRowsAfter.count == 1,
            "hydrate must not touch another radio's rows")
    #expect(radioBRowsAfter.first?.messageID == envB.messageID,
            "the surviving row must be the foreign radio's original envelope")
  }

  /// `fetchPendingSends` orders by sequence ASC, and `hydrate` iterates
  /// that result with sequential `await dmQueue.enqueue(_:)` calls,
  /// which `SendQueue` appends to a FIFO list. This verifies the
  /// persistence layer preserves enqueue order across the hydrate
  /// path so a future "optimize the fetch" change can't silently
  /// reorder replays. We assert at the fetch boundary because the
  /// downstream `SendQueue` order is exercised by its own tests.
  @Test
  func `hydrate fetch returns rows in sequence order`() async throws {
    let store = try Self.makeStore()
    let radioID = UUID()
    let envA = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())
    let envB = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())
    let envC = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())

    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envA, radioID: radioID)
    )
    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envB, radioID: radioID)
    )
    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envC, radioID: radioID)
    )

    let rows = try await store.fetchPendingSends(radioID: radioID)
    let messageIDs = rows.map(\.messageID)
    #expect(messageIDs == [envA.messageID, envB.messageID, envC.messageID],
            "fetchPendingSends must return rows in sequence ASC; hydrate depends on this for FIFO replay")
  }

  /// Regression: the classifier must unwrap `MessageServiceError.sessionError`
  /// so the wrapped form produced by `failMessageAndRethrow` matches. DM path
  /// treats firmware code 3 (TABLE_FULL pool exhaustion) as transient.
  @Test
  func `isTransientDirectMessageError unwraps sessionError on deviceError(3)`() {
    let wrapped = MessageServiceError.sessionError(.deviceError(code: 3))
    #expect(ChatSendQueueService.isTransientDirectMessageError(wrapped) == true)
  }

  /// Symmetric regression for the channel path: code 2 (NOT_FOUND pool
  /// exhaustion / stale channel index) is transient when wrapped in
  /// MessageServiceError.sessionError.
  @Test
  func `isTransientChannelMessageError unwraps sessionError on deviceError(2)`() {
    let wrapped = MessageServiceError.sessionError(.deviceError(code: 2))
    #expect(ChatSendQueueService.isTransientChannelMessageError(wrapped) == true)
  }

  /// A DM-path classifier must not park on code 2 — only code 3 is the DM
  /// pool-exhaustion signal. This locks in the asymmetry between paths.
  @Test
  func `isTransientDirectMessageError treats deviceError(2) as terminal`() {
    let wrapped = MessageServiceError.sessionError(.deviceError(code: 2))
    #expect(ChatSendQueueService.isTransientDirectMessageError(wrapped) == false)
  }

  /// Symmetric guard for the channel classifier: code 3 is the DM signal,
  /// not the channel one.
  @Test
  func `isTransientChannelMessageError treats deviceError(3) as terminal`() {
    let wrapped = MessageServiceError.sessionError(.deviceError(code: 3))
    #expect(ChatSendQueueService.isTransientChannelMessageError(wrapped) == false)
  }

  /// Regression: the channel-cap helper recognises the raw
  /// `MeshCoreError.deviceError(2)` shape produced by `withPoolBackoff`'s
  /// re-throw before `MessageService` wraps it.
  @Test
  func `isChannelMessageNotFound matches raw MeshCoreError.deviceError(2)`() {
    let raw: Error = MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.channelMessageNotFound)
    #expect(ChatSendQueueService.isChannelMessageNotFound(raw) == true)
  }

  /// Regression: the helper unwraps the `MessageServiceError.sessionError`
  /// shape produced by `failMessageAndRethrow` in `MessageService`.
  @Test
  func `isChannelMessageNotFound unwraps MessageServiceError.sessionError(deviceError(2))`() {
    let wrapped: Error = MessageServiceError.sessionError(.deviceError(code: FirmwareDeviceErrorCode.channelMessageNotFound))
    #expect(ChatSendQueueService.isChannelMessageNotFound(wrapped) == true)
  }

  /// Regression: only firmware code 2 maps to the cap. Other firmware codes
  /// (e.g. code 3 — the DM TABLE_FULL signal) must not be classified as
  /// NOT_FOUND, since the channel cap should not trigger on DM-pool exhaustion.
  @Test
  func `isChannelMessageNotFound rejects non-NOT_FOUND device errors`() {
    let wrongCode: Error = MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull)
    let wrappedWrongCode: Error = MessageServiceError.sessionError(.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull))
    let timeout: Error = MeshCoreError.timeout
    #expect(ChatSendQueueService.isChannelMessageNotFound(wrongCode) == false)
    #expect(ChatSendQueueService.isChannelMessageNotFound(wrappedWrongCode) == false)
    #expect(ChatSendQueueService.isChannelMessageNotFound(timeout) == false)
  }

  @Test
  func `channel drain fails when fetchChannel confirms the channel is gone`() async throws {
    let harness = try await Self.setUpChannelCapHarness(attemptCount: 7)
    defer { Task { await harness.queue.shutdown() } }
    await harness.session.setGetChannelError(
      MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.channelMessageNotFound)
    )

    await harness.queue.hydrate()
    try await waitUntil("missing channel should drain the pending send") {
      let exists = try? await harness.dataStore.hasPendingSend(messageID: harness.messageID)
      return exists == false
    }
    await harness.queue.awaitDrainCompletion()

    #expect(try await harness.dataStore.fetchMessage(id: harness.messageID)?.status == .failed)
    #expect(await harness.session.getChannelIndices == [0])
    await harness.queue.shutdown()
  }

  @Test
  func `channel drain parks deviceError(2) below disambiguateAfterAttempts`() async throws {
    let harness = try await Self.setUpChannelCapHarness(attemptCount: 0)
    defer { Task { await harness.queue.shutdown() } }
    try await harness.dataStore.updateMessageStatus(id: harness.messageID, status: .failed)

    await harness.queue.hydrate()
    try await waitUntil("channel send should fail and return to pending") {
      let sent = await harness.session.sendChannelMessageInvocations.count
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      return sent >= 2 && message?.status == .pending
    }

    #expect(try await harness.dataStore.hasPendingSend(messageID: harness.messageID))
    #expect(await harness.session.getChannelIndices.isEmpty)
    await harness.queue.shutdown()
  }

  @Test
  func `persistent channel fetch failures stop at the configured cap`() async throws {
    let harness = try await Self.setUpChannelCapHarness(
      attemptCount: 7,
      queueConfig: ChatSendQueueConfig(maxConsecutiveFetchChannelFailures: 2)
    )
    defer { Task { await harness.queue.shutdown() } }
    await harness.session.setGetChannelError(MeshCoreError.deviceError(code: 1))

    await harness.queue.hydrate()
    try await waitUntil("first failed channel fetch should park") {
      let fetches = await harness.session.getChannelIndices.count
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      return fetches == 1 && message?.status == .pending
    }
    #expect(try await harness.dataStore.hasPendingSend(messageID: harness.messageID))

    harness.queue.transportDidOpen()
    try await waitUntil("the fetch failure cap should drain the pending send") {
      let exists = try? await harness.dataStore.hasPendingSend(messageID: harness.messageID)
      return exists == false
    }
    await harness.queue.awaitDrainCompletion()

    #expect(await harness.session.getChannelIndices == [0, 0])
    #expect(try await harness.dataStore.fetchMessage(id: harness.messageID)?.status == .failed)
    await harness.queue.shutdown()
  }

  // MARK: - Channel-cap helpers

  private struct ChannelCapHarness {
    let queue: ChatSendQueueService
    let session: MockMeshCoreSession
    let dataStore: PersistenceStore
    let messageID: UUID
  }

  private static func setUpChannelCapHarness(
    attemptCount: Int,
    queueConfig: ChatSendQueueConfig = .default
  ) async throws -> ChannelCapHarness {
    let device = Device(
      publicKey: Data(repeating: 0x11, count: 32),
      nodeName: "Test Radio"
    )
    let container = try PersistenceStore.createContainer(inMemory: true)
    container.mainContext.insert(device)
    try container.mainContext.save()
    let store = PersistenceStore(modelContainer: container)
    let radioID = device.radioID

    let channelIndex: UInt8 = 0
    let channel = Channel(
      radioID: radioID,
      index: channelIndex,
      name: "Test Channel",
      secret: Data(repeating: 0xAA, count: 16)
    )
    container.mainContext.insert(channel)
    try container.mainContext.save()

    let session = MockMeshCoreSession()
    await session.setSendChannelMessageError(
      MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.channelMessageNotFound)
    )
    let messageConfig = MessageServiceConfig(poolBackoff: PoolBackoffConfig(attemptCap: 2, baseDelay: 0.01))
    let messageService = MessageService(session: session, dataStore: store, contactService: nil, config: messageConfig)

    let pending = try await messageService.createPendingChannelMessage(
      text: "Hello channel",
      channelIndex: channelIndex,
      radioID: radioID
    )

    let envelope = ChannelMessageEnvelope(
      messageID: pending.id,
      channelIndex: channelIndex,
      isResend: true,
      messageText: pending.text,
      messageTimestamp: pending.timestamp,
      localNodeName: "Test Radio"
    )
    let dto = PendingSendDTO(
      id: UUID(),
      radioID: radioID,
      messageID: envelope.messageID,
      kind: .channel,
      contactID: nil,
      channelIndex: channelIndex,
      isResend: true,
      messageText: pending.text,
      messageTimestamp: pending.timestamp,
      localNodeName: "Test Radio",
      sequence: 1,
      enqueuedAt: Date(),
      attemptCount: attemptCount
    )
    try await store.upsertPendingSend(dto)

    let channelService = ChannelService(session: session, dataStore: store, rxLogService: nil)
    let queueService = ChatSendQueueService(
      radioID: radioID,
      dataStore: store,
      messageService: messageService,
      channelService: channelService,
      reactionService: ReactionService(),
      config: queueConfig
    )

    return ChannelCapHarness(
      queue: queueService,
      session: session,
      dataStore: store,
      messageID: pending.id
    )
  }

  @Test
  func `hydrate runs once per service instance`() async throws {
    let store = try Self.makeStore()
    let radioID = UUID()
    let envelope = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())
    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envelope, radioID: radioID)
    )

    let messageService = await Self.makeMessageService(dataStore: store)
    let channelService = await Self.makeChannelService(dataStore: store)
    let service = ChatSendQueueService(
      radioID: radioID,
      dataStore: store,
      messageService: messageService,
      channelService: channelService,
      reactionService: ReactionService()
    )

    await service.hydrate()
    await service.awaitDrainCompletion()

    // First hydrate drained the row.
    let rowsAfterFirst = try await store.fetchPendingSends(radioID: radioID)
    #expect(rowsAfterFirst.isEmpty)

    // Insert a new row to detect whether a second hydrate would enqueue it.
    let envelope2 = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())
    _ = try await store.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envelope2, radioID: radioID)
    )

    await service.hydrate()
    await service.awaitDrainCompletion()

    // The second hydrate must be a no-op; the new row must survive.
    let rowsAfterSecond = try await store.fetchPendingSends(radioID: radioID)
    #expect(rowsAfterSecond.count == 1,
            "second hydrate on same instance must not re-enqueue persisted rows")
  }

  /// Regression: when a DM send fails with a transient firmware code
  /// (e.g. `directMessageTableFull`), `failMessageAndRethrow` writes
  /// `.failed` and broadcasts the failure before the queue's
  /// catch reclassifies the error and remaps the status back to
  /// `.pending`. The broadcast propagates an in-memory `.failed`
  /// snapshot to the UI even though the persisted state is `.pending`,
  /// causing the bubble to flicker "Failed" while the queue is parked.
  /// Transient errors must not broadcast `.failed` at all.
  @Test
  func `Transient DM send error must not broadcast .failed`() async throws {
    // Tight pool-backoff so the failure surfaces fast. The invariant
    // under test is independent of backoff duration.
    let messageConfig = MessageServiceConfig(
      poolBackoff: PoolBackoffConfig(attemptCap: 2, baseDelay: 0.01)
    )
    let harness = try await Self.setUpDMHarness(messageConfig: messageConfig)
    defer { Task { await harness.tearDown() } }

    let statusEvents = harness.messageService.statusEvents()

    // A transient send failure must park without a terminal notification.
    await harness.session.setSendMessageResult(.failure(
      MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull)
    ))

    try await harness.drainUntilParked()
    let observed = await harness.messageService.drainStatusEvents(statusEvents).failedIDs
    #expect(observed.count == 0, "Transient errors must not broadcast .failed; got \(observed.count) events")
  }

  /// Complement to `transientDMError_DoesNotFireFailedHandler`. A truly
  /// terminal DM send error must still surface to the UI via the `.failed`
  /// broadcast, exactly once. Guards against accidentally suppressing the
  /// broadcast on the queue-routed path given that `failMessageAndRethrow`
  /// does not broadcast itself.
  @Test
  func `Terminal DM send error broadcasts .failed exactly once`() async throws {
    let harness = try await Self.setUpDMHarness()
    defer { Task { await harness.tearDown() } }

    let statusEvents = harness.messageService.statusEvents()

    // Non-transient firmware code so the drain's classifier treats the
    // error as terminal and rethrows from the inner catch, hitting the
    // outer catch in the queue closure that calls `notifyMessageFailed`.
    await harness.session.setSendMessageResult(.failure(
      MeshCoreError.deviceError(code: ProtocolError.illegalArgument.rawValue)
    ))

    await harness.queue.hydrate()
    try await waitUntil("terminal DM failure should finish the queue notification") {
      let exists = try? await harness.dataStore.hasPendingSend(messageID: harness.messageID)
      return exists == false
    }
    await harness.queue.awaitDrainCompletion()

    let ids = await harness.messageService.drainStatusEvents(statusEvents).failedIDs
    #expect(ids.count == 1, "Terminal errors must broadcast .failed exactly once; got \(ids.count)")
    #expect(ids.first == harness.messageID, ".failed must carry the failed envelope's messageID")
  }

  /// A container built after the connection already reached `.ready` receives
  /// the edge through `observeConnectionState`'s initial value: an already-ready
  /// initial state must fire the trigger so a drain parked in
  /// `withCooperativeTimeout` wakes (observable as a second attemptCount bump)
  /// instead of waiting out `transportWaitTimeout`.
  @Test
  func `observeConnectionState fires the trigger for an already-ready initial state`() async throws {
    let messageConfig = MessageServiceConfig(
      poolBackoff: PoolBackoffConfig(attemptCap: 2, baseDelay: 0.01)
    )
    let harness = try await Self.setUpDMHarness(messageConfig: messageConfig)
    defer { Task { await harness.tearDown() } }

    await harness.session.setSendMessageResult(.failure(
      MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull)
    ))
    try await harness.drainUntilParked()

    harness.queue.observeConnectionState(
      initial: .ready,
      events: AsyncStream { $0.finish() }
    )

    try await waitUntil(timeout: .seconds(10), "ready should wake the parked send") {
      let row = try? await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .first(where: { $0.messageID == harness.messageID })
      return (row?.attemptCount ?? 0) >= 2
    }
  }

  /// `.connected` (link up, initial sync not yet cleared) must NOT wake a
  /// parked drain: hydrated sends stay parked through the sync window so they
  /// do not contend with sync's reads. The drain only wakes once `.ready`
  /// arrives on the stream.
  @Test
  func `observeConnectionState keeps a drain parked through .connected and wakes it on .ready`() async throws {
    let messageConfig = MessageServiceConfig(
      poolBackoff: PoolBackoffConfig(attemptCap: 2, baseDelay: 0.01)
    )
    let harness = try await Self.setUpDMHarness(messageConfig: messageConfig)
    defer { Task { await harness.tearDown() } }

    await harness.session.setSendMessageResult(.failure(
      MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull)
    ))

    let (stream, continuation) = AsyncStream.makeStream(of: DeviceConnectionState.self)
    harness.queue.observeConnectionState(initial: .disconnected, events: stream)

    try await harness.drainUntilParked()

    // `.connected` alone must not arm the trigger: the parked drain stays at one attempt.
    continuation.yield(.disconnected)
    continuation.yield(.connected)
    continuation.yield(.syncing)
    try await Task.sleep(for: .seconds(1))
    let parkedRow = try await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
      .first(where: { $0.messageID == harness.messageID })
    #expect(parkedRow?.attemptCount == 1, ".connected must not wake the drain during the sync window")

    // Entering `.ready` fires the trigger and the drain wakes for its second attempt.
    try await harness.dataStore.updateMessageStatus(id: harness.messageID, status: .failed)
    continuation.yield(.ready)
    try await waitUntil(timeout: .seconds(10), "ready should wake the parked send") {
      let sent = await harness.session.sendMessageInvocations.count
      let row = try? await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .first(where: { $0.messageID == harness.messageID })
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      return sent >= 4 && row?.attemptCount == 2 && message?.status == .pending
    }
    continuation.yield(.ready)
    continuation.finish()
    try await Task.sleep(for: .seconds(1))
    let settledRow = try await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
      .first(where: { $0.messageID == harness.messageID })
    #expect(settledRow?.attemptCount == 2, "duplicate ready states must not fire another trigger")
  }

  // MARK: - DM drain helpers

  private struct DMHarness {
    let queue: ChatSendQueueService
    let messageService: MessageService
    let dataStore: PersistenceStore
    let session: MockMeshCoreSession
    let radioID: UUID
    let messageID: UUID

    func drainUntilParked() async throws {
      // A transition from failed to pending witnesses the completed error handling.
      try await dataStore.updateMessageStatus(id: messageID, status: .failed)
      await queue.hydrate()
      try await waitUntil("DM send should fail and return to pending") {
        let sent = await session.sendMessageInvocations.count
        let row = try? await dataStore.fetchPendingSends(radioID: radioID)
          .first(where: { $0.messageID == messageID })
        let message = try? await dataStore.fetchMessage(id: messageID)
        return sent >= 2 && (row?.attemptCount ?? 0) >= 1 && message?.status == .pending
      }
    }

    func tearDown() async {
      await queue.shutdown()
    }
  }

  private static func setUpDMHarness(
    queueConfig: ChatSendQueueConfig = .default,
    messageConfig: MessageServiceConfig = .default
  ) async throws -> DMHarness {
    let device = Device(
      publicKey: Data(repeating: 0x22, count: 32),
      nodeName: "Test Radio"
    )
    let container = try PersistenceStore.createContainer(inMemory: true)
    container.mainContext.insert(device)
    try container.mainContext.save()
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = device.radioID

    let contact = Contact(
      radioID: radioID,
      publicKey: Data(repeating: 0x33, count: 32),
      name: "Test Contact",
      lastHeardTimestamp: 0
    )
    container.mainContext.insert(contact)
    try container.mainContext.save()
    let contactDTO = try #require(try await dataStore.fetchContact(id: contact.id))

    let session = MockMeshCoreSession()
    await session.setCurrentSelfInfo(.testSelfInfo())
    let messageService = MessageService(session: session, dataStore: dataStore, contactService: nil, config: messageConfig)
    let pending = try await messageService.createPendingMessage(text: "Hello", to: contactDTO)

    let envelope = DirectMessageEnvelope(messageID: pending.id, contactID: contactDTO.id)
    _ = try await dataStore.insertPendingSendAssigningSequence(
      PendingSendDTO(envelope: envelope, radioID: radioID)
    )

    let channelService = ChannelService(session: session, dataStore: dataStore, rxLogService: nil)
    let queue = ChatSendQueueService(
      radioID: radioID,
      dataStore: dataStore,
      messageService: messageService,
      channelService: channelService,
      reactionService: ReactionService(),
      config: queueConfig
    )

    return DMHarness(
      queue: queue,
      messageService: messageService,
      dataStore: dataStore,
      session: session,
      radioID: radioID,
      messageID: pending.id
    )
  }
}
