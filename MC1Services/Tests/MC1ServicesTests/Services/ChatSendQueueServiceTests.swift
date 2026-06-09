import Testing
import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport

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
        return MessageService(session: session, dataStore: dataStore)
    }

    private static func makeChannelService(dataStore: PersistenceStore) async -> ChannelService {
        let transport = SimulatorMockTransport()
        let session = MeshCoreSession(transport: transport)
        return ChannelService(session: session, dataStore: dataStore)
    }

    /// PendingSend row pointing at a contact that was deleted between
    /// enqueue and hydrate. The send closure's contact lookup fails,
    /// which is the "drop envelope" path — the row is purged without
    /// calling `sendPendingDirectMessage`. This exercises hydrate's queue
    /// loading and the queue's drain → onError → row-cleanup path on
    /// the simplest available transport-independent surface.
    @Test("hydrate replays persisted PendingSend rows and drains them")
    func hydrateReplaysRows() async throws {
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
    @Test("transient send error preserves the persisted row while parked on the trigger")
    func transientErrorPreservesRow() async throws {
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
            name: "Test Contact"
        )
        container.mainContext.insert(contact)
        try container.mainContext.save()
        let contactDTO = try #require(try await store.fetchContact(id: contact.id))

        let transport = SimulatorMockTransport()
        let session = MeshCoreSession(transport: transport)
        let messageService = MessageService(session: session, dataStore: store)
        let message = try await messageService.createPendingMessage(text: "Hello", to: contactDTO)

        let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contactDTO.id)
        _ = try await store.insertPendingSendAssigningSequence(
            PendingSendDTO(envelope: envelope, radioID: radioID)
        )

        let channelService = ChannelService(session: session, dataStore: store)
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
    @Test("hydrate processes only the service's own radio rows")
    func hydrateScopesByRadioID() async throws {
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
    @Test("hydrate fetch returns rows in sequence order")
    func hydrateFetchOrdersBySequence() async throws {
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
    @Test("isTransientDirectMessageError unwraps sessionError on deviceError(3)")
    func directMessageClassifierUnwrapsSessionErrorOnCode3() {
        let wrapped = MessageServiceError.sessionError(.deviceError(code: 3))
        #expect(ChatSendQueueService.isTransientDirectMessageError(wrapped) == true)
    }

    /// Symmetric regression for the channel path: code 2 (NOT_FOUND pool
    /// exhaustion / stale channel index) is transient when wrapped in
    /// MessageServiceError.sessionError.
    @Test("isTransientChannelMessageError unwraps sessionError on deviceError(2)")
    func channelMessageClassifierUnwrapsSessionErrorOnCode2() {
        let wrapped = MessageServiceError.sessionError(.deviceError(code: 2))
        #expect(ChatSendQueueService.isTransientChannelMessageError(wrapped) == true)
    }

    /// A DM-path classifier must not park on code 2 — only code 3 is the DM
    /// pool-exhaustion signal. This locks in the asymmetry between paths.
    @Test("isTransientDirectMessageError treats deviceError(2) as terminal")
    func directMessageClassifierTreatsCode2AsTerminal() {
        let wrapped = MessageServiceError.sessionError(.deviceError(code: 2))
        #expect(ChatSendQueueService.isTransientDirectMessageError(wrapped) == false)
    }

    /// Symmetric guard for the channel classifier: code 3 is the DM signal,
    /// not the channel one.
    @Test("isTransientChannelMessageError treats deviceError(3) as terminal")
    func channelMessageClassifierTreatsCode3AsTerminal() {
        let wrapped = MessageServiceError.sessionError(.deviceError(code: 3))
        #expect(ChatSendQueueService.isTransientChannelMessageError(wrapped) == false)
    }

    /// Regression: the channel-cap helper recognises the raw
    /// `MeshCoreError.deviceError(2)` shape produced by `withPoolBackoff`'s
    /// re-throw before `MessageService` wraps it.
    @Test("isChannelMessageNotFound matches raw MeshCoreError.deviceError(2)")
    func isChannelMessageNotFoundMatchesRawDeviceError() {
        let raw: Error = MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.channelMessageNotFound)
        #expect(ChatSendQueueService.isChannelMessageNotFound(raw) == true)
    }

    /// Regression: the helper unwraps the `MessageServiceError.sessionError`
    /// shape produced by `failMessageAndRethrow` in `MessageService`.
    @Test("isChannelMessageNotFound unwraps MessageServiceError.sessionError(deviceError(2))")
    func isChannelMessageNotFoundUnwrapsSessionError() {
        let wrapped: Error = MessageServiceError.sessionError(.deviceError(code: FirmwareDeviceErrorCode.channelMessageNotFound))
        #expect(ChatSendQueueService.isChannelMessageNotFound(wrapped) == true)
    }

    /// Regression: only firmware code 2 maps to the cap. Other firmware codes
    /// (e.g. code 3 — the DM TABLE_FULL signal) must not be classified as
    /// NOT_FOUND, since the channel cap should not trigger on DM-pool exhaustion.
    @Test("isChannelMessageNotFound rejects non-NOT_FOUND device errors")
    func isChannelMessageNotFoundRejectsOtherCodes() {
        let wrongCode: Error = MeshCoreError.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull)
        let wrappedWrongCode: Error = MessageServiceError.sessionError(.deviceError(code: FirmwareDeviceErrorCode.directMessageTableFull))
        let timeout: Error = MeshCoreError.timeout
        #expect(ChatSendQueueService.isChannelMessageNotFound(wrongCode) == false)
        #expect(ChatSendQueueService.isChannelMessageNotFound(wrappedWrongCode) == false)
        #expect(ChatSendQueueService.isChannelMessageNotFound(timeout) == false)
    }

    /// End-to-end channel-cap behaviour on a high-attempt envelope. A channel
    /// envelope with `attemptCount = 7` pre-loaded on its PendingSend row
    /// reaches `postBumpCount = 8` on its next drain. When the channel send
    /// throws `deviceError(channelMessageNotFound)` the cap branch fires: the
    /// catch re-throws (rather than parking), the SendQueue's `onError` deletes
    /// the PendingSend row, and the message stays `.failed` (the `.failed`
    /// write made by `failMessageAndRethrow` is not remapped back to `.pending`).
    ///
    /// Drives the channel-send failure by dispatching `.error(code: 2)` events
    /// in a tight loop so every `withPoolBackoff` retry sees the firmware
    /// `NOT_FOUND` signal. Pool backoff exhausts after 3 in-loop attempts and
    /// re-throws as `MessageServiceError.sessionError(.deviceError(2))`, which
    /// reaches the queue catch.
    @Test(
        "channel drain treats deviceError(2) as terminal when fetchChannel confirms the channel is gone",
        .disabled("""
            The previous shape exercised the maxChannelNotFoundRetries cap by driving \
            NOT_FOUND through resendChannelMessage. The new behaviour disambiguates by \
            calling ChannelService.fetchChannel(index:); that requires either a mockable \
            ChannelService or a session test harness that resolves getChannel(index:) \
            against the dispatched NOT_FOUND event. Re-enable once that harness lands.
            """)
    )
    func testChannelDrain_StaleChannelIndex_DropsEnvelopeWhenFetchChannelReturnsNil() async throws {
        let harness = try await Self.setUpChannelCapHarness(attemptCount: 7)
        let dispatchTask = Self.startDeviceErrorDispatch(service: harness.messageService, code: FirmwareDeviceErrorCode.channelMessageNotFound)
        defer { dispatchTask.cancel() }

        await harness.queueService.hydrate()
        await Self.waitForPendingSendDrained(messageID: harness.messageID, store: harness.store)

        let postDrainMessage = try await harness.store.fetchMessage(id: harness.messageID)
        #expect(postDrainMessage?.status == .failed,
                "cap branch must re-throw so .failed is not remapped to .pending")
        let postDrainRows = try await harness.store.fetchPendingSends(radioID: harness.radioID)
        #expect(postDrainRows.isEmpty,
                "cap branch terminal re-throw must route through onError, deleting the PendingSend row")

        dispatchTask.cancel()
        await harness.queueService.shutdown()
    }

    /// Negative case for the disambiguation gate. A channel envelope with
    /// `attemptCount = 0` reaches `postBumpCount = 1` on its first drain. Even
    /// with the same `deviceError(channelMessageNotFound)` failure the
    /// disambiguation does not fire (1 < disambiguateAfterAttempts). The
    /// transient branch instead remaps the status back to `.pending` and parks
    /// the envelope.
    @Test("channel drain parks deviceError(2) below disambiguateAfterAttempts")
    func testChannelDrain_PoolExhaustion_ChannelStillExists_ParksEnvelope() async throws {
        let harness = try await Self.setUpChannelCapHarness(
            attemptCount: 0,
            messageConfig: MessageServiceConfig(
                poolBackoff: PoolBackoffConfig(attemptCap: 2, baseDelay: 0.01)
            )
        )
        let dispatchTask = Self.startDeviceErrorDispatch(service: harness.messageService, code: FirmwareDeviceErrorCode.channelMessageNotFound)
        defer { dispatchTask.cancel() }

        await harness.queueService.hydrate()
        // The shrunk pool-backoff lets the first drain bump attemptCount,
        // exhaust backoff in tens of milliseconds, then the transient catch
        // branch remaps the `.failed` write back to `.pending` and suspends in
        // waitForTransportOpen with no further trigger. Poll for that parked
        // steady-state — gated on attemptCount >= 1 so it cannot match the
        // initial pre-drain `.pending` — rather than sleeping a fixed interval
        // that races a loaded runner against the backoff schedule.
        try await Self.waitForCondition(timeout: .seconds(15)) {
            let message = try await harness.store.fetchMessage(id: harness.messageID)
            let row = try await harness.store.fetchPendingSends(radioID: harness.radioID)
                .first(where: { $0.messageID == harness.messageID })
            return (row?.attemptCount ?? 0) >= 1 && message?.status == .pending
        }

        let postDrainMessage = try await harness.store.fetchMessage(id: harness.messageID)
        #expect(postDrainMessage?.status == .pending,
                "transient branch must remap .failed back to .pending so the bubble does not flicker")
        let postDrainRows = try await harness.store.fetchPendingSends(radioID: harness.radioID)
        #expect(postDrainRows.contains(where: { $0.messageID == harness.messageID }),
                "park branch must preserve the PendingSend row for the next transport-open retry")
        let postRow = postDrainRows.first(where: { $0.messageID == harness.messageID })
        #expect((postRow?.attemptCount ?? 0) >= 1,
                "top-of-drain bump must persist attemptCount >= 1 before the channel send fails")

        dispatchTask.cancel()
        await harness.queueService.shutdown()
    }

    /// Regression: the channel `fetchChannel` failure counter must be scoped
    /// per envelope, not shared across the service. When envelope A drains
    /// itself through the cap (consecutive `fetchChannel` throws), the
    /// counter must not strand a value that causes the next envelope's
    /// first disambiguate-path throw to immediately exceed the cap.
    ///
    /// With a service-wide `channelFetchFailureCounter` (single
    /// `FailureCounter`), envelope A leaves it at the cap, then envelope B's
    /// first drain enters disambiguate (postBumpCount >= 3), fetchChannel
    /// throws, counter bumps past the cap, `failures >= cap` is true → B
    /// terminal-fails on its first attempt.
    ///
    /// With a per-messageID counter, envelope B starts at 0, bumps to 1 on its
    /// first throw, well below the cap → B parks.
    ///
    /// The harness uses a shrunk cap and tight pool-backoff to keep runtime
    /// bounded; the behaviour under test is per-envelope counter scoping,
    /// not the cap's absolute value.
    @Test("Channel fetchChannel failure counter is per-envelope and does not cascade")
    func channelFetchFailureCounter_IsPerEnvelope_DoesNotCascade() async throws {
        let queueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures: 2)
        let messageConfig = MessageServiceConfig(
            poolBackoff: PoolBackoffConfig(attemptCap: 2, baseDelay: 0.01)
        )

        // attemptCount=7 → postBumpCount=8 on first drain, well above
        // disambiguateAfterAttempts (3), so every drain attempt routes through
        // fetchChannel and increments the counter.
        let harness = try await Self.setUpChannelCapHarness(
            attemptCount: 7,
            queueConfig: queueConfig,
            messageConfig: messageConfig
        )

        // attemptCount=2 → postBumpCount=3 on first drain, which is exactly
        // at disambiguateAfterAttempts. Envelope B's first drain therefore
        // enters the disambiguate path and exercises the counter.
        let envelopeBSequence = 2
        let dtoB = PendingSendDTO(
            id: UUID(),
            radioID: harness.radioID,
            messageID: harness.envelopeB.messageID,
            kind: .channel,
            contactID: nil,
            channelIndex: harness.envelopeB.channelIndex,
            isResend: harness.envelopeB.isResend,
            messageText: harness.envelopeB.messageText,
            messageTimestamp: harness.envelopeB.messageTimestamp,
            localNodeName: harness.envelopeB.localNodeName,
            sequence: envelopeBSequence,
            enqueuedAt: Date(),
            attemptCount: 2
        )
        try await harness.store.upsertPendingSend(dtoB)

        // Continuous .error(channelMessageNotFound) dispatching drives both
        // envelopes' pool-backoff loops and never lets the counter reset via
        // a successful fetchChannel. The cap path runs.
        let dispatchTask = Self.startDeviceErrorDispatch(
            service: harness.messageService,
            code: FirmwareDeviceErrorCode.channelMessageNotFound
        )
        defer { dispatchTask.cancel() }

        // Fire the transport-open trigger continuously so the 30s park after
        // each drain attempt completes immediately. Without this, 16 drain
        // attempts for envelope A would take >8 minutes; with it, each
        // attempt is bounded by pool-backoff (~3.5s) + fetchChannel (~1.9s).
        let triggerSpammer = Task.detached { [queue = harness.queueService] in
            while !Task.isCancelled {
                await queue.transportDidOpen()
                try? await Task.sleep(for: .milliseconds(50))
            }
        }
        defer { triggerSpammer.cancel() }

        await harness.queueService.hydrate()

        // Envelope A cycles through `cap` fetchChannel throws and terminal-fails.
        // True terminal-fail is observable as "PendingSend row deleted by
        // SendQueue.onError" — the per-cycle `.failed → .pending` oscillation
        // inside `failMessageAndRethrow` + park remap is not a terminal signal.
        try await Self.waitForCondition(timeout: .seconds(30)) {
            let exists = try await harness.store.hasPendingSend(messageID: harness.envelopeA.messageID)
            return exists == false
        }

        let statusA = try await harness.store.fetchMessage(id: harness.envelopeA.messageID)?.status
        #expect(statusA == .failed,
                "Envelope A should be terminal-failed after persistent fetchChannel throws (cap reached)")

        // Envelope B should park (.pending) under the per-envelope counter; a
        // service-wide counter would terminal-fail it on its first drain
        // because the value is stranded at the cap. Wait long enough for at
        // least one full B drain cycle to elapse so steady-state is observable.
        try await Task.sleep(for: .seconds(3))

        let statusB = try await harness.store.fetchMessage(id: harness.envelopeB.messageID)?.status
        let existsB = try await harness.store.hasPendingSend(messageID: harness.envelopeB.messageID)
        // True terminal-fail deletes the PendingSend row via SendQueue.onError
        // and leaves the message at .failed. Park preserves the row and remaps
        // .failed → .pending. Asserting on the row presence is the unambiguous
        // signal — status oscillates within each drain cycle.
        #expect(existsB == true,
                "Envelope B's PendingSend row must survive (parked); the stranded counter caused it to be deleted as a terminal-fail. status=\(String(describing: statusB)) rowExists=\(existsB)")

        dispatchTask.cancel()
        triggerSpammer.cancel()
        await harness.queueService.shutdown()
    }

    /// When the transport-open trigger never fires, the drain must time out
    /// via `transportWaitTimeout` rather than waiting forever. Verifies the
    /// message stays `.pending` and the PendingSend row survives across the
    /// wait. Disabled by default because the production constant is 30 seconds
    /// — exercise locally by uncommenting `.enabled` or by reducing the
    /// timeout via a `#if DEBUG` hook.
    @Test(
        "drain bounded wait re-attempts after transportWaitTimeout expires",
        .disabled("Real-time test depends on the 30s transportWaitTimeout default; enable when reducing the constant via a #if DEBUG hook.")
    )
    func testDrain_TimeoutWithoutFire_LogsAndRequeues() async throws {
        let harness = try await Self.setUpChannelCapHarness(attemptCount: 0)
        let dispatchTask = Self.startDeviceErrorDispatch(service: harness.messageService, code: FirmwareDeviceErrorCode.channelMessageNotFound)
        defer { dispatchTask.cancel() }

        await harness.queueService.hydrate()
        try await Self.waitForCondition(timeout: .seconds(60)) {
            let rows = try await harness.store.fetchPendingSends(radioID: harness.radioID)
            return (rows.first(where: { $0.messageID == harness.messageID })?.attemptCount ?? 0) >= 2
        }

        let postDrainMessage = try await harness.store.fetchMessage(id: harness.messageID)
        #expect(postDrainMessage?.status == .pending)
        let postDrainRows = try await harness.store.fetchPendingSends(radioID: harness.radioID)
        #expect(postDrainRows.contains(where: { $0.messageID == harness.messageID }))

        dispatchTask.cancel()
        await harness.queueService.shutdown()
    }

    // MARK: - Channel-cap helpers

    /// In-memory test harness exercising the channel drain end-to-end.
    ///
    /// `queue` is the canonical accessor used by newer tests; `queueService`
    /// remains a synonym so the existing channel-cap suites keep compiling
    /// without churn.
    ///
    /// `envelopeA` is the channel envelope persisted by `setUpChannelCapHarness`.
    /// `envelopeB` is a second envelope, not yet enqueued — callers that need
    /// to exercise multi-envelope scenarios call `harness.queue.enqueueChannel(harness.envelopeB)`
    /// themselves so each test controls its own drain ordering.
    @MainActor
    private final class ChannelCapHarness {
        let queueService: ChatSendQueueService
        var queue: ChatSendQueueService { queueService }
        let messageService: MessageService
        let store: PersistenceStore
        let messageID: UUID
        let radioID: UUID
        let envelopeA: ChannelMessageEnvelope
        let envelopeB: ChannelMessageEnvelope
        private var dispatchTask: Task<Void, Never>?

        init(
            queueService: ChatSendQueueService,
            messageService: MessageService,
            store: PersistenceStore,
            messageID: UUID,
            radioID: UUID,
            envelopeA: ChannelMessageEnvelope,
            envelopeB: ChannelMessageEnvelope
        ) {
            self.queueService = queueService
            self.messageService = messageService
            self.store = store
            self.messageID = messageID
            self.radioID = radioID
            self.envelopeA = envelopeA
            self.envelopeB = envelopeB
        }

        /// Drive `fetchChannel(index:)` failures by streaming `.error(code:)`
        /// events at the dispatcher's fixed cadence. The same event stream
        /// also throws `MeshCoreError.deviceError(code)` on the channel send
        /// path, so callers asserting the disambiguation step should set
        /// `count` high enough to cover the in-loop pool-backoff retries
        /// plus the trailing `fetchChannel` round-trips.
        ///
        /// `count == Int.max` dispatches indefinitely until `tearDown()` is
        /// called. A finite count emits up to that many events and then
        /// stops, allowing later attempts to surface their own outcomes
        /// (timeouts or the channel-still-exists branch).
        func setFetchChannelFailureCount(_ count: Int) async {
            dispatchTask?.cancel()
            let messageService = self.messageService
            let target = count
            dispatchTask = Task.detached {
                let session = await messageService.sessionForTest
                var emitted = 0
                while !Task.isCancelled && emitted < target {
                    await session.dispatchForTesting(.error(code: FirmwareDeviceErrorCode.channelMessageNotFound))
                    if target != Int.max {
                        emitted += 1
                    }
                    try? await Task.sleep(for: .milliseconds(20))
                }
            }
        }

        /// Run one drain cycle to completion: call `hydrate()` if it has not
        /// yet run, then wait for the message to reach a terminal status
        /// (`.failed` or `.sent`) or for the PendingSend row to be deleted.
        /// Bounded at 8 seconds to keep test runtime predictable; the
        /// pool-backoff loop exhausts in ~3.5s under steady error dispatch.
        ///
        /// A parked envelope (row preserved with bumped `attemptCount`) is
        /// not treated as terminal here — callers that expect park as a
        /// success outcome should poll the row directly after this returns.
        func drainOnce(timeout: Duration = .seconds(8)) async {
            await queueService.hydrate()
            let deadline = ContinuousClock.now.advanced(by: timeout)
            while ContinuousClock.now < deadline {
                if let message = try? await store.fetchMessage(id: messageID),
                   message.status == .failed || message.status == .sent {
                    return
                }
                if let exists = try? await store.hasPendingSend(messageID: messageID), exists == false {
                    return
                }
                try? await Task.sleep(for: .milliseconds(50))
            }
        }

        func tearDown() async {
            dispatchTask?.cancel()
            dispatchTask = nil
            await queueService.shutdown()
        }
    }

    /// Build a Device + Channel + Message + PendingSend with the requested
    /// `attemptCount`. Returns the connected `ChatSendQueueService` (not yet
    /// hydrated) and the supporting fixtures so callers can drive the drain.
    ///
    /// The PendingSend row is built with `isResend = true` so the drain
    /// closure calls `MessageService.resendChannelMessage`, which routes
    /// through `withPoolBackoff` and `failMessageAndRethrow` — matching the
    /// retry path the cap is intended to bound.
    private static func setUpChannelCapHarness(
        attemptCount: Int?,
        queueConfig: ChatSendQueueConfig = .default,
        messageConfig: MessageServiceConfig = .default
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

        // 200ms session timeout so each pool-backoff attempt rejects quickly
        // if the test's dispatcher loop misses its window. The transport is
        // connected so `transport.send` does not throw `notConnected` and the
        // resulting error path runs through the dispatcher / matcher flow
        // the cap is intended to exercise.
        let transport = SimulatorMockTransport()
        try await transport.connect()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.2)
        )
        let messageService = MessageService(session: session, dataStore: store, config: messageConfig)

        let pending = try await messageService.createPendingChannelMessage(
            text: "Hello channel",
            channelIndex: channelIndex,
            radioID: radioID
        )

        let envelopeA = ChannelMessageEnvelope(
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
            messageID: envelopeA.messageID,
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

        // Second envelope for the same channel — never persisted at setup
        // time so consuming tests own the enqueue and observe a clean
        // `attemptCount = 0` row when they trigger it themselves.
        let pendingB = try await messageService.createPendingChannelMessage(
            text: "Second message",
            channelIndex: channelIndex,
            radioID: radioID
        )
        let envelopeB = ChannelMessageEnvelope(
            messageID: pendingB.id,
            channelIndex: channelIndex,
            isResend: false,
            messageText: pendingB.text,
            messageTimestamp: pendingB.timestamp,
            localNodeName: "Test Radio"
        )

        let channelService = ChannelService(session: session, dataStore: store)
        let queueService = ChatSendQueueService(
            radioID: radioID,
            dataStore: store,
            messageService: messageService,
            channelService: channelService,
            reactionService: ReactionService(),
            config: queueConfig
        )

        return ChannelCapHarness(
            queueService: queueService,
            messageService: messageService,
            store: store,
            messageID: pending.id,
            radioID: radioID,
            envelopeA: envelopeA,
            envelopeB: envelopeB
        )
    }

    /// Background dispatcher that broadcasts `.error(code:)` events at a
    /// fixed cadence so every `withPoolBackoff` subscribe-and-wait window
    /// in `session.sendChannelMessage` lands on a NOT_FOUND signal. Caller
    /// cancels the returned task once the test reaches its assertion.
    private static func startDeviceErrorDispatch(
        service: MessageService,
        code: UInt8,
        interval: Duration = .milliseconds(20)
    ) -> Task<Void, Never> {
        Task.detached {
            let session = await service.sessionForTest
            while !Task.isCancelled {
                await session.dispatchForTesting(.error(code: code))
                try? await Task.sleep(for: interval)
            }
        }
    }

    /// Polls `block` until it returns true or the timeout elapses; otherwise
    /// records a Swift Testing issue. Lighter than spinning a deadline loop
    /// inside each test body.
    private static func waitForCondition(
        timeout: Duration,
        pollEvery: Duration = .milliseconds(50),
        _ block: () async throws -> Bool
    ) async throws {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while ContinuousClock.now < deadline {
            if try await block() { return }
            try await Task.sleep(for: pollEvery)
        }
        Issue.record("waitForCondition timed out after \(timeout)")
    }

    /// Polls until the PendingSend row for `messageID` is gone — i.e. the
    /// cap branch (or any terminal `onError`) has deleted it.
    private static func waitForPendingSendDrained(
        messageID: UUID,
        store: PersistenceStore,
        timeout: Duration = .seconds(10)
    ) async {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while ContinuousClock.now < deadline {
            if let exists = try? await store.hasPendingSend(messageID: messageID), exists == false {
                return
            }
            try? await Task.sleep(for: .milliseconds(50))
        }
        Issue.record("PendingSend row for \(messageID) was not drained within \(timeout)")
    }

    @Test("hydrate runs once per service instance")
    func hydrateRunsOncePerInstance() async throws {
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

    @Test("ChatSendQueueServiceError.notConnected description is non-empty")
    func notConnectedErrorHasDescription() {
        let error = ChatSendQueueServiceError.notConnected
        #expect(error.errorDescription?.isEmpty == false)
    }

    /// Regression: when a DM send fails with a transient firmware code
    /// (e.g. `directMessageTableFull`), `failMessageAndRethrow` writes
    /// `.failed` and fires `messageFailedHandler` before the queue's
    /// catch reclassifies the error and remaps the status back to
    /// `.pending`. The handler fire propagates an in-memory `.failed`
    /// snapshot to the UI even though the persisted state is `.pending`,
    /// causing the bubble to flicker "Failed" while the queue is parked.
    /// Transient errors must not fire the failure handler at all.
    @Test("Transient DM send error must not fire messageFailedHandler")
    func transientDMError_DoesNotFireFailedHandler() async throws {
        // Tight pool-backoff so the failure surfaces fast. The invariant
        // under test is independent of backoff duration.
        let messageConfig = MessageServiceConfig(
            poolBackoff: PoolBackoffConfig(attemptCap: 2, baseDelay: 0.01)
        )
        let harness = try await Self.setUpDMHarness(messageConfig: messageConfig)
        defer { Task { await harness.tearDown() } }

        actor Collector {
            var ids: [UUID] = []
            func record(_ id: UUID) { ids.append(id) }
            func snapshot() -> [UUID] { ids }
        }
        let collector = Collector()
        await harness.messageService.setMessageFailedHandler { messageID in
            await collector.record(messageID)
        }

        // Configure session to throw a transient error code on send.
        // The persisted PendingSend row is drained by hydrate() inside
        // drainOnceAllowingPark; the drain should park the envelope on
        // waitForTransportOpen rather than terminal-fail it.
        await harness.setSendDirectMessageFailureMode(
            .deviceError(FirmwareDeviceErrorCode.directMessageTableFull)
        )

        await harness.drainOnceAllowingPark()
        // drainOnceAllowingPark returns as soon as attemptCount >= 1
        // (the top-of-drain bump). The transient failure does not fire
        // until withPoolBackoff exhausts. Wait past the backoff window so
        // failMessageAndRethrow has definitively run before we assert.
        try await Task.sleep(for: .milliseconds(300))

        let observed = await collector.snapshot()
        #expect(observed.count == 0, "Transient errors must not fire messageFailedHandler; got \(observed.count) calls")
    }

    /// Complement to `transientDMError_DoesNotFireFailedHandler`. A truly
    /// terminal DM send error must still surface to the UI via the failure
    /// handler — exactly once. Guards against accidentally suppressing the
    /// fire on the queue-routed path now that `failMessageAndRethrow` no
    /// longer fires the handler itself.
    @Test("Terminal DM send error fires messageFailedHandler exactly once")
    func terminalDMError_FiresFailedHandlerExactlyOnce() async throws {
        let harness = try await Self.setUpDMHarness()
        defer { Task { await harness.tearDown() } }

        actor Collector {
            var ids: [UUID] = []
            func record(_ id: UUID) { ids.append(id) }
            func snapshot() -> [UUID] { ids }
        }
        let collector = Collector()
        await harness.messageService.setMessageFailedHandler { messageID in
            await collector.record(messageID)
        }

        // Non-transient firmware code so the drain's classifier treats the
        // error as terminal and rethrows from the inner catch, hitting the
        // outer catch in the queue closure that fires `notifyMessageFailed`.
        await harness.setSendDirectMessageFailureMode(.invalidInput)

        await harness.drainOnce()

        let ids = await collector.snapshot()
        #expect(ids.count == 1, "Terminal errors must fire handler exactly once; got \(ids.count)")
        #expect(ids.first == harness.messageID, "Handler must fire for the failed envelope's messageID")
    }

    // MARK: - DM drain helpers

    /// Distinct DM send-failure modes the harness can simulate. Each maps
    /// to a different code path inside the drain closure:
    ///
    /// - `.normal`: no events dispatched. `session.sendMessage` waits past
    ///   the session's `defaultTimeout` and throws `MeshCoreError.timeout`,
    ///   classified transient → park branch.
    /// - `.deviceError(code)`: dispatches `.error(code:)` continuously. The
    ///   classifier treats `FirmwareDeviceErrorCode.directMessageTableFull`
    ///   as transient and every other code as terminal.
    /// - `.connectionLost`: tears down the transport so the next send fails
    ///   with `MeshCoreError.notConnected` (which `isTransientError`
    ///   classifies as transient, parking the envelope). One-way: the mock
    ///   transport's AsyncStream continuation is finished on disconnect and
    ///   cannot be revived, so `.connectionLost` must be the final mode set
    ///   before `tearDown()`.
    /// - `.invalidInput`: dispatches an illegal-argument firmware code so the
    ///   send fails with a non-transient `deviceError`, exercising the
    ///   terminal-error path without touching MeshCore internals.
    enum DirectSendFailureMode {
        case normal
        case deviceError(UInt8)
        case connectionLost
        case invalidInput
    }

    /// DM-drain harness mirroring `ChannelCapHarness` for the
    /// `ChatSendQueueService` direct-message path. Owns the transport and
    /// dispatcher task so tests can express failure modes declaratively.
    @MainActor
    private final class DMHarness {
        let queue: ChatSendQueueService
        let messageService: MessageService
        let dataStore: PersistenceStore
        let session: MeshCoreSession
        let envelope: DirectMessageEnvelope
        let radioID: UUID
        let messageID: UUID
        private let transport: SimulatorMockTransport
        private var dispatchTask: Task<Void, Never>?

        init(
            queue: ChatSendQueueService,
            messageService: MessageService,
            dataStore: PersistenceStore,
            session: MeshCoreSession,
            envelope: DirectMessageEnvelope,
            radioID: UUID,
            transport: SimulatorMockTransport
        ) {
            self.queue = queue
            self.messageService = messageService
            self.dataStore = dataStore
            self.session = session
            self.envelope = envelope
            self.radioID = radioID
            self.messageID = envelope.messageID
            self.transport = transport
        }

        /// Configure the next `sendDirectMessage` to resolve in a specific
        /// failure shape. Stops any previously-running dispatcher first so
        /// modes do not stack. Subsequent drain attempts inherit whatever
        /// mode is active until `.normal` is reapplied or the harness is
        /// torn down. `.connectionLost` is one-way: once the transport is
        /// disconnected, no subsequent mode (including `.normal`) can revive
        /// it — callers must treat `.connectionLost` as the final mode before
        /// `tearDown()`.
        func setSendDirectMessageFailureMode(_ mode: DirectSendFailureMode) async {
            dispatchTask?.cancel()
            dispatchTask = nil
            switch mode {
            case .normal:
                break
            case .deviceError(let code):
                let session = self.session
                dispatchTask = Task.detached {
                    while !Task.isCancelled {
                        await session.dispatchForTesting(.error(code: code))
                        try? await Task.sleep(for: .milliseconds(20))
                    }
                }
            case .connectionLost:
                await transport.disconnect()
            case .invalidInput:
                let session = self.session
                dispatchTask = Task.detached {
                    while !Task.isCancelled {
                        await session.dispatchForTesting(.error(code: ProtocolError.illegalArgument.rawValue))
                        try? await Task.sleep(for: .milliseconds(20))
                    }
                }
            }
        }

        /// Drive one drain cycle: `hydrate()` (or `enqueueDM`) plus a bounded
        /// wait for either a terminal status (`.failed` / `.sent`) or for
        /// the PendingSend row to be deleted. Records a Swift Testing issue
        /// if the envelope parks instead — callers that expect park as a
        /// success outcome should use `drainOnceAllowingPark()`.
        func drainOnce(timeout: Duration = .seconds(8)) async {
            await queue.hydrate()
            let deadline = ContinuousClock.now.advanced(by: timeout)
            while ContinuousClock.now < deadline {
                if let message = try? await dataStore.fetchMessage(id: messageID) {
                    if message.status == .failed || message.status == .sent {
                        return
                    }
                }
                if let exists = try? await dataStore.hasPendingSend(messageID: messageID), exists == false {
                    return
                }
                try? await Task.sleep(for: .milliseconds(50))
            }
            Issue.record("DM drain did not reach a terminal outcome within \(timeout)")
        }

        /// Variant of `drainOnce` for transient-failure scenarios where a
        /// parked envelope (`attemptCount > 0`, row still present, status
        /// remapped to `.pending`) is the expected outcome. Returns as soon
        /// as the row is observed parked or terminal.
        func drainOnceAllowingPark(timeout: Duration = .seconds(8)) async {
            await queue.hydrate()
            let deadline = ContinuousClock.now.advanced(by: timeout)
            while ContinuousClock.now < deadline {
                let rows = (try? await dataStore.fetchPendingSends(radioID: radioID)) ?? []
                if let row = rows.first(where: { $0.messageID == messageID }),
                   (row.attemptCount ?? 0) >= 1 {
                    return
                }
                if let exists = try? await dataStore.hasPendingSend(messageID: messageID), exists == false {
                    return
                }
                try? await Task.sleep(for: .milliseconds(50))
            }
        }

        func tearDown() async {
            dispatchTask?.cancel()
            dispatchTask = nil
            await queue.shutdown()
        }
    }

    /// Builds a DM-side counterpart to `setUpChannelCapHarness`. Inserts a
    /// `Device` + `Contact` + pending `Message`, persists a single
    /// `PendingSend` row, wires the real session/queue/services together,
    /// and returns the harness with no hydration yet performed so the test
    /// can configure the failure mode before triggering the drain.
    ///
    /// The session is configured with a 200ms `defaultTimeout` so each
    /// `withPoolBackoff` cycle in `MessageService.sendDirectMessage` resolves
    /// quickly when the dispatcher loop misses its window.
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
            name: "Test Contact"
        )
        container.mainContext.insert(contact)
        try container.mainContext.save()
        let contactDTO = try #require(try await dataStore.fetchContact(id: contact.id))

        let transport = SimulatorMockTransport()
        try await transport.connect()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.2)
        )
        let messageService = MessageService(session: session, dataStore: dataStore, config: messageConfig)
        await messageService.installSelfInfoForTest()
        let pending = try await messageService.createPendingMessage(text: "Hello", to: contactDTO)

        let envelope = DirectMessageEnvelope(messageID: pending.id, contactID: contactDTO.id)
        _ = try await dataStore.insertPendingSendAssigningSequence(
            PendingSendDTO(envelope: envelope, radioID: radioID)
        )

        let channelService = ChannelService(session: session, dataStore: dataStore)
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
            envelope: envelope,
            radioID: radioID,
            transport: transport
        )
    }
}
