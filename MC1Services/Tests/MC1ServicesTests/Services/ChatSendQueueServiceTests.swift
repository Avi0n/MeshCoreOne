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
    await harness.session.setGetChannelResults(
      harness.channelReads(endingWith: MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.channelMessageNotFound))
    )

    await harness.queue.hydrate()
    try await waitUntil("missing channel should drain the pending send") {
      let exists = try? await harness.dataStore.hasPendingSend(messageID: harness.messageID)
      return exists == false
    }
    await harness.queue.awaitDrainCompletion()

    #expect(try await harness.dataStore.fetchMessage(id: harness.messageID)?.status == .failed)
    #expect(await harness.session.getChannelIndices == [0])
    #expect(await harness.session.sendChannelMessageInvocations.count == harness.poolAttempts)
    await harness.queue.shutdown()
  }

  @Test
  func `channel drain parks deviceError(2) below disambiguateAfterAttempts`() async throws {
    let harness = try await Self.setUpChannelCapHarness(attemptCount: 0)
    defer { Task { await harness.queue.shutdown() } }
    await harness.queue.hydrate()
    try await waitUntil("channel pool exhaustion should park the pending send") {
      let sent = await harness.session.sendChannelMessageInvocations.count
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      let parked = await harness.queue.parkedDrainCountForTesting
      return sent == harness.poolAttempts && message?.status == .pending && parked > 0
    }

    #expect(try await harness.dataStore.hasPendingSend(messageID: harness.messageID))
    #expect(await harness.session.getChannelIndices.isEmpty)
    await harness.queue.shutdown()
  }

  @Test
  func `persistent channel fetch failures stop at the configured cap`() async throws {
    let failureCap = 2
    let harness = try await Self.setUpChannelCapHarness(
      attemptCount: 7,
      queueConfig: ChatSendQueueConfig(maxConsecutiveFetchChannelFailures: failureCap)
    )
    defer { Task { await harness.queue.shutdown() } }
    let fetchError = MeshCoreError.deviceError(code: ErrorCode.unsupportedCommand.rawValue)
    let readResults = (0..<failureCap).flatMap { _ in harness.channelReads(endingWith: fetchError) }
    await harness.session.setGetChannelResults(readResults)

    await harness.queue.hydrate()
    try await waitUntil("first failed channel fetch should park") {
      let fetches = await harness.session.getChannelIndices.count
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      let parked = await harness.queue.parkedDrainCountForTesting
      return fetches == 1 && message?.status == .pending && parked > 0
    }
    #expect(try await harness.dataStore.hasPendingSend(messageID: harness.messageID))

    harness.queue.transportDidOpen()
    try await waitUntil("the fetch failure cap should drain the pending send") {
      let exists = try? await harness.dataStore.hasPendingSend(messageID: harness.messageID)
      return exists == false
    }
    await harness.queue.awaitDrainCompletion()

    #expect(await harness.session.getChannelIndices == Array(repeating: 0, count: failureCap))
    #expect(await harness.session.sendChannelMessageInvocations.count == failureCap * harness.poolAttempts)
    #expect(try await harness.dataStore.fetchMessage(id: harness.messageID)?.status == .failed)
    await harness.queue.shutdown()
  }

  // MARK: - Channel-cap helpers

  private struct ChannelCapHarness {
    let queue: ChatSendQueueService
    let session: MockMeshCoreSession
    let dataStore: PersistenceStore
    let messageID: UUID
    let channel: ChannelInfo
    let poolAttempts: Int

    func channelReads(endingWith error: Error) -> [Result<ChannelInfo, Error>] {
      [.failure(error)]
    }
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
    let channelInfo = ChannelInfo(index: channelIndex, name: channel.name, secret: channel.secret)
    await session.setStubbedChannels([channelIndex: channelInfo])
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
      messageID: pending.id,
      channel: channelInfo,
      poolAttempts: messageConfig.poolBackoff.attemptCap + 1
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

  /// A transient firmware code such as `directMessageTableFull` stays queued.
  /// The drain must not broadcast `.failed` while that row is parked.
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

  @Test
  func `a table-full rejection keeps the attempt byte for the next drain`() async throws {
    let messageConfig = MessageServiceConfig(
      poolBackoff: PoolBackoffConfig(attemptCap: 1, baseDelay: 0.01)
    )
    let harness = try await Self.setUpDMHarness(messageConfig: messageConfig)
    defer { Task { await harness.tearDown() } }

    await harness.session.setSendMessageResult(.failure(
      MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull)
    ))
    try await harness.drainUntilParked()

    let parked = try await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
      .first { $0.messageID == harness.messageID }
    #expect(parked?.nextWireAttempt == 0)
    let firstCount = await harness.session.sendMessageInvocations.count
    #expect(await harness.session.sendMessageInvocations.allSatisfy { $0.attempt == 0 })

    harness.queue.observeConnectionState(initial: .ready, events: AsyncStream { $0.finish() })
    try await waitUntil(timeout: .seconds(10), "the parked drain should retry the same byte") {
      await harness.session.sendMessageInvocations.count > firstCount
    }
    #expect(await harness.session.sendMessageInvocations.allSatisfy { $0.attempt == 0 })
    let stillParked = try await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
      .first { $0.messageID == harness.messageID }
    #expect(stillParked?.nextWireAttempt == 0)
  }

  @Test
  func `a host timeout consumes the attempt byte`() async throws {
    let harness = try await Self.setUpDMHarness(
      messageConfig: MessageServiceConfig(minTimeout: 0, poolBackoff: PoolBackoffConfig(attemptCap: 0, baseDelay: 0))
    )
    defer { Task { await harness.tearDown() } }

    await harness.session.setSendMessageResult(.failure(MeshCoreError.timeout))
    await harness.queue.hydrate()
    try await waitUntil("a timed-out command should park with the next byte stored") {
      let row = try? await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .first { $0.messageID == harness.messageID }
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      let parked = await harness.queue.parkedDrainCountForTesting
      return row?.nextWireAttempt == 1 && message?.status == .pending && parked > 0
    }
    let timedOut = await harness.session.sendMessageInvocations
    #expect(timedOut.map(\.attempt) == [0])
    let timestamp = try #require(timedOut.first?.timestamp)

    await harness.session.setSendMessageResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 10)
    ))
    harness.queue.observeConnectionState(initial: .ready, events: AsyncStream { $0.finish() })
    try await waitUntil(timeout: .seconds(10), "the next drain should submit the unused byte") {
      await harness.session.sendMessageInvocations.contains { $0.attempt == 1 }
    }
    let invocations = await harness.session.sendMessageInvocations
    #expect(invocations.filter { $0.attempt == 0 }.count == 1)
    #expect(invocations.first { $0.attempt == 1 }?.timestamp == timestamp)
  }

  @Test
  func `an acknowledgement during resume stops the next attempt`() async throws {
    let harness = try await Self.setUpDMHarness(
      messageConfig: MessageServiceConfig(minTimeout: 0, poolBackoff: PoolBackoffConfig(attemptCap: 0, baseDelay: 0))
    )
    defer { Task { await harness.tearDown() } }

    await harness.session.setSendMessageResult(.failure(MeshCoreError.timeout))
    await harness.queue.hydrate()
    try await waitUntil("a timed-out command should park before the resume") {
      let row = try? await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .first { $0.messageID == harness.messageID }
      return row?.nextWireAttempt == 1
    }

    let service = harness.messageService
    let store = harness.dataStore
    let messageID = harness.messageID
    await service.setDirectMessageSubmitGateForTesting { attempt in
      guard attempt == 1 else { return }
      let message = try? await store.fetchMessage(id: messageID)
      guard let message else { return }
      let code = AckCodeBuilder.expectedAck(
        timestamp: message.timestamp,
        attempt: 0,
        text: message.text,
        senderPublicKey: Data(repeating: 0xAB, count: 32)
      )
      await service.handleAcknowledgement(code: code, tripTime: 40)
    }
    await harness.session.setSendMessageResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 10)
    ))
    harness.queue.observeConnectionState(initial: .ready, events: AsyncStream { $0.finish() })
    try await waitUntil(timeout: .seconds(10), "the resume should record delivery without another command") {
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      let parked = await harness.queue.parkedDrainCountForTesting
      return message?.status == .delivered && parked == 0
    }
    let invocations = await harness.session.sendMessageInvocations
    #expect(invocations.map(\.attempt) == [0])
  }

  @Test
  func `a missed attempt-byte save does not record delivery`() async throws {
    let harness = try await Self.setUpDMHarness(
      messageConfig: MessageServiceConfig(minTimeout: 0, poolBackoff: PoolBackoffConfig(attemptCap: 0, baseDelay: 0))
    )
    defer { Task { await harness.tearDown() } }

    await harness.dataStore.setPendingSendWireAttemptSaveFaultInjection { () async throws in
      throw ForcedSaveFailure()
    }
    await harness.session.setSendMessageResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 10)
    ))
    await harness.queue.hydrate()
    try await waitUntil(timeout: .seconds(10), "the accepted command should stay an acknowledgement owner") {
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      return message?.status == .sent || message?.status == .delivered
    }
    let message = try #require(try await harness.dataStore.fetchMessage(id: harness.messageID))
    #expect(message.status == .sent)
    let owner = try #require(
      try await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .first { $0.messageID == harness.messageID }
    )
    #expect(owner.ackDeadline != nil)
    #expect(await harness.session.sendMessageInvocations.map(\.attempt) == [0])
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
    continuation.yield(.ready)
    try await waitUntil(timeout: .seconds(10), "ready should wake the parked send") {
      let sent = await harness.session.sendMessageInvocations.count
      let row = try? await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .first(where: { $0.messageID == harness.messageID })
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      let parked = await harness.queue.parkedDrainCountForTesting
      return sent >= 4 && row?.attemptCount == 2 && message?.status == .pending && parked > 0
    }
    continuation.yield(.ready)
    continuation.finish()
    try await Task.sleep(for: .seconds(1))
    let settledRow = try await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
      .first(where: { $0.messageID == harness.messageID })
    #expect(settledRow?.attemptCount == 2, "duplicate ready states must not fire another trigger")
  }

  @Test
  func `a delivery during the timeout consume sends no further command`() async throws {
    let harness = try await Self.setUpDMHarness(
      messageConfig: MessageServiceConfig(minTimeout: 0, poolBackoff: PoolBackoffConfig(attemptCap: 0, baseDelay: 0))
    )
    defer { Task { await harness.tearDown() } }

    let service = harness.messageService
    let store = harness.dataStore
    let messageID = harness.messageID
    let sender = Data(repeating: 0xAB, count: 32)
    await store.setPendingSendWireAttemptSaveFaultInjection {
      guard let message = try await store.fetchMessage(id: messageID) else { return }
      let code = AckCodeBuilder.expectedAck(
        timestamp: message.timestamp,
        attempt: 0,
        text: message.text,
        senderPublicKey: sender
      )
      await service.handleAcknowledgement(code: code, tripTime: 40)
    }
    await harness.session.setSendMessageResult(.failure(MeshCoreError.timeout))
    await harness.queue.hydrate()
    try await waitUntil(timeout: .seconds(10), "delivery during the consume should finish the row") {
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      let remaining = try? await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .filter { $0.messageID == harness.messageID }
      let parked = await harness.queue.parkedDrainCountForTesting
      return message?.status == .delivered && remaining?.isEmpty == true && parked == 0
    }
    let deliveredCount = await harness.session.sendMessageInvocations.count
    #expect(await harness.session.sendMessageInvocations.map(\.attempt) == [0])

    await harness.session.setSendMessageResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 10)
    ))
    harness.queue.observeConnectionState(initial: .ready, events: AsyncStream { $0.finish() })
    try await waitUntil(timeout: .seconds(10), "a second drain should find nothing to send") {
      await harness.queue.parkedDrainCountForTesting == 0
    }
    #expect(await harness.session.sendMessageInvocations.count == deliveredCount)
  }

  @Test
  func `a not submitted resend stays delivered and retries when the transport opens`() async throws {
    let harness = try await Self.setUpDMHarness(
      messageConfig: MessageServiceConfig(minTimeout: 0, poolBackoff: PoolBackoffConfig(attemptCap: 0, baseDelay: 0))
    )
    defer { Task { await harness.tearDown() } }
    let original: UInt32 = 1_700_000_000
    try await prepareDeliveredResend(harness, timestamp: original)
    let statusEvents = harness.messageService.statusEvents()
    await harness.session.setSendMessageResult(.failure(MeshCoreError.notConnected))
    await harness.queue.hydrate()
    try await waitUntil("a not submitted resend should park still delivered") {
      let message = try? await harness.dataStore.fetchMessage(id: harness.messageID)
      let row = try? await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .first { $0.messageID == harness.messageID }
      let parked = await harness.queue.parkedDrainCountForTesting
      return message?.status == .delivered
        && message?.timestamp == original
        && row?.attemptCount == PersistenceStore.initialAttemptCount
        && row?.nextWireAttempt == 0
        && parked > 0
    }
    let parkedCommands = await harness.session.sendMessageInvocations
    #expect(parkedCommands.map(\.attempt) == [0])
    #expect(await harness.messageService.drainStatusEvents(statusEvents).failedIDs.isEmpty)

    await harness.session.setSendMessageResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 10)
    ))
    harness.queue.observeConnectionState(initial: .ready, events: AsyncStream { $0.finish() })
    try await waitUntil(timeout: .seconds(10), "the parked resend should submit attempt zero again") {
      await harness.session.sendMessageInvocations.count > parkedCommands.count
    }
    let retried = await harness.session.sendMessageInvocations.dropFirst(parkedCommands.count)
    #expect(retried.first?.attempt == 0)
    let stamped = try UInt32(#require(retried.first).timestamp.timeIntervalSince1970)
    #expect(stamped != original)
    #expect(try await harness.dataStore.hasPendingSend(messageID: harness.messageID))
  }

  @Test
  func `a new packet commits its timestamp with the command`() async throws {
    let harness = try await Self.setUpDMHarness()
    defer { Task { await harness.tearDown() } }
    let original: UInt32 = 1_700_000_000
    try await prepareDeliveredResend(harness, timestamp: original)
    await harness.session.setSendMessageResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 10)
    ))
    _ = try await sendQueued(harness, preserveTimestamp: false)
    let command = try #require(await harness.session.sendMessageInvocations.first)
    let stamped = UInt32(command.timestamp.timeIntervalSince1970)
    let stored = try #require(try await harness.dataStore.fetchMessage(id: harness.messageID))
    #expect(stamped != original)
    #expect(stored.timestamp == stamped)
    #expect(stored.status != .delivered)
  }

  @Test
  func `a new packet keeps the old timestamp when the command is not submitted`() async throws {
    let harness = try await Self.setUpDMHarness()
    defer { Task { await harness.tearDown() } }
    let original: UInt32 = 1_700_000_000
    try await prepareDeliveredResend(harness, timestamp: original)
    await harness.session.setCurrentSelfInfo(nil)
    await #expect(throws: CancellationError.self) {
      try await sendQueued(harness, preserveTimestamp: false)
    }
    #expect(await harness.session.sendMessageInvocations.isEmpty)
    let stored = try #require(try await harness.dataStore.fetchMessage(id: harness.messageID))
    #expect(stored.timestamp == original)
    #expect(stored.status == .delivered)
  }

  @Test
  func `a timed out new packet keeps its timestamp for the next byte`() async throws {
    let harness = try await Self.setUpDMHarness(
      messageConfig: MessageServiceConfig(minTimeout: 0, poolBackoff: PoolBackoffConfig(attemptCap: 0, baseDelay: 0))
    )
    defer { Task { await harness.tearDown() } }
    let original: UInt32 = 1_700_000_000
    try await prepareDeliveredResend(harness, timestamp: original)
    await harness.session.setSendMessageResult(.failure(MeshCoreError.timeout))
    await #expect(throws: MessageServiceError.self) {
      try await sendQueued(harness, preserveTimestamp: false)
    }
    let first = await harness.session.sendMessageInvocations
    #expect(first.map(\.attempt) == [0])
    let stamped = try UInt32(#require(first.first).timestamp.timeIntervalSince1970)
    #expect(stamped != original)
    let stored = try #require(try await harness.dataStore.fetchMessage(id: harness.messageID))
    #expect(stored.timestamp == stamped)
    let pendingID = try await queuedPendingID(harness)
    #expect(try await harness.dataStore.pendingSendWireAttempt(id: pendingID) == 1)

    await harness.session.setSendMessageResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 10)
    ))
    _ = try await sendQueued(harness, preserveTimestamp: true)
    let next = try #require(await harness.session.sendMessageInvocations.dropFirst(first.count).first)
    #expect(next.attempt == 1)
    #expect(UInt32(next.timestamp.timeIntervalSince1970) == stamped)
    #expect(try await harness.dataStore.fetchMessage(id: harness.messageID)?.timestamp == stamped)
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
      await queue.hydrate()
      try await waitUntil("DM pool exhaustion should park the pending send") {
        let sent = await session.sendMessageInvocations.count
        let row = try? await dataStore.fetchPendingSends(radioID: radioID)
          .first(where: { $0.messageID == messageID })
        let message = try? await dataStore.fetchMessage(id: messageID)
        let parked = await queue.parkedDrainCountForTesting
        return sent >= 2 && (row?.attemptCount ?? 0) >= 1 && message?.status == .pending && parked > 0
      }
    }

    func tearDown() async {
      await queue.shutdown()
    }
  }

  private func prepareDeliveredResend(_ harness: DMHarness, timestamp: UInt32) async throws {
    try await harness.dataStore.updateMessageTimestamp(id: harness.messageID, timestamp: timestamp)
    try await harness.dataStore.updateMessageStatus(id: harness.messageID, status: .delivered)
  }

  private func queuedPendingID(_ harness: DMHarness) async throws -> UUID {
    try #require(
      try await harness.dataStore.fetchPendingSends(radioID: harness.radioID)
        .first { $0.messageID == harness.messageID }?.id
    )
  }

  private func sendQueued(_ harness: DMHarness, preserveTimestamp: Bool) async throws -> MessageDTO {
    let contact = try #require(
      try await harness.dataStore.fetchContact(
        radioID: harness.radioID,
        publicKey: Data(repeating: 0x33, count: 32)
      )
    )
    return try await harness.messageService.performQueuedDirectMessageSend(
      messageID: harness.messageID,
      to: contact,
      preserveTimestamp: preserveTimestamp,
      isResend: true,
      isSingleAttempt: false,
      pendingSendID: queuedPendingID(harness),
      sendLease: nil
    )
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

private struct ForcedSaveFailure: Error {}
