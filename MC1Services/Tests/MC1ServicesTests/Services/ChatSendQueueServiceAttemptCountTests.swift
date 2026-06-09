import Testing
import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport

/// Acceptance suite for the persistent `PendingSend.attemptCount` surface.
/// Covers three cases:
/// 1. Fresh send: enqueue then drain bumps 0 → 1, `preserveTimestamp == false`.
/// 2. Process restart: a row left at `attemptCount = 1` on disk (prior process
///    bumped then died before deleting the row) rehydrates, drains, bumps to
///    `2`, and uses `preserveTimestamp == true` so the recipient's mesh dedup
///    catches a duplicate landing.
/// 3. Bump failure: `incrementPendingSendAttemptCount` is forced to throw, the
///    envelope parks on the transport-open trigger, the row survives, and no
///    wire send happens.
@Suite("ChatSendQueueService.attemptCount")
@MainActor
struct ChatSendQueueServiceAttemptCountTests {

    /// Helper: build a Device + Contact + Message + PendingSend with the
    /// requested attemptCount, returning the service, the store it shares,
    /// the messageID, the radioID, and the message's original wire timestamp.
    /// The service is not hydrated — the caller decides whether to hydrate or
    /// to first run `store.warmUp()`.
    ///
    /// The message's `timestamp` is pinned to one hour ago so a "fresh wire
    /// timestamp" stamp by `updateMessageTimestamp` (preserveTimestamp=false)
    /// is observably different from a "preserved" timestamp
    /// (preserveTimestamp=true). UInt32 epoch-second granularity would
    /// otherwise collide on a fast test run.
    ///
    /// `radioID` comes from `Device.radioID` (not `Device.id`) so the
    /// in-progress radio survives `purgeOrphanPendingSends`, which keys on
    /// `Device.radioID`.
    private struct QueueHarness {
        let service: ChatSendQueueService
        let store: PersistenceStore
        let messageID: UUID
        let radioID: UUID
        let originalTimestamp: UInt32
    }

    private static func setupQueueWithRow(
        attemptCount: Int?
    ) async throws -> QueueHarness {
        let device = Device(
            publicKey: Data(repeating: 1, count: 32),
            nodeName: "Test Device"
        )
        let container = try PersistenceStore.createContainer(inMemory: true)
        container.mainContext.insert(device)
        try container.mainContext.save()
        let store = PersistenceStore(modelContainer: container)
        let radioID = device.radioID

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
        let pending = try await messageService.createPendingMessage(text: "Hello", to: contactDTO)

        let pinnedTimestamp = UInt32(Date().addingTimeInterval(-3600).timeIntervalSince1970)
        try await store.updateMessageTimestamp(id: pending.id, timestamp: pinnedTimestamp)
        let message = try #require(try await store.fetchMessage(id: pending.id))

        let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contactDTO.id)
        let dto = PendingSendDTO(
            id: UUID(),
            radioID: radioID,
            messageID: envelope.messageID,
            kind: .dm,
            contactID: envelope.contactID,
            channelIndex: nil,
            isResend: false,
            messageText: "",
            messageTimestamp: 0,
            localNodeName: nil,
            sequence: 1,
            enqueuedAt: Date(),
            attemptCount: attemptCount
        )
        try await store.upsertPendingSend(dto)

        let channelService = ChannelService(session: session, dataStore: store)
        let service = ChatSendQueueService(
            radioID: radioID,
            dataStore: store,
            messageService: messageService,
            channelService: channelService,
            reactionService: ReactionService()
        )

        return QueueHarness(
            service: service,
            store: store,
            messageID: message.id,
            radioID: radioID,
            originalTimestamp: message.timestamp
        )
    }

    /// Case 1 / Fresh send: a row with `attemptCount = 0` (current-build
    /// race-window row that persisted but never progressed past the
    /// top-of-drain bump) drains with `preserveTimestamp = false` because
    /// `postBumpCount = 1 > 1` is false. The recipient never saw the packet,
    /// so a fresh wire timestamp is correct. Observable side effect:
    /// `Message.timestamp` changes (updateMessageTimestamp was called by
    /// `sendPendingDirectMessage`).
    @Test("fresh send: row with attemptCount=0 first drain uses fresh timestamp")
    func freshSendBumpsToOneAndUsesFreshTimestamp() async throws {
        let harness = try await Self.setupQueueWithRow(attemptCount: 0)

        await harness.service.hydrate()
        try? await Task.sleep(for: .milliseconds(500))

        let rows = try await harness.store.fetchPendingSends(radioID: harness.radioID)
        let bumped = rows.first(where: { $0.messageID == harness.messageID })?.attemptCount
        #expect(bumped == 1, "first drain attempt must bump 0 → 1")

        let postDrainMessage = try await harness.store.fetchMessage(id: harness.messageID)
        #expect(postDrainMessage?.timestamp != harness.originalTimestamp,
                "preserveTimestamp=false: updateMessageTimestamp must stamp a fresh wire timestamp")

        await harness.service.shutdown()
    }

    /// Case 2 / Process restart: a row left at `attemptCount = 1` simulates
    /// "prior process bumped before sending then died before
    /// deletePendingSendsForMessage". On rehydrate the drain bumps to 2 and
    /// preserves the wire timestamp so mesh dedup catches a duplicate
    /// landing if the wire send completed.
    @Test("process restart: row with attemptCount=1 rehydrates with preserveTimestamp=true")
    func processRestartPreservesTimestampOnRehydrate() async throws {
        let harness = try await Self.setupQueueWithRow(attemptCount: 1)

        await harness.service.hydrate()
        try? await Task.sleep(for: .milliseconds(500))

        let postDrain = try await harness.store.fetchPendingSends(radioID: harness.radioID)
        #expect(postDrain.first(where: { $0.messageID == harness.messageID })?.attemptCount == 2,
                "rehydrate drain bumps 1 → 2")

        let postDrainMessage = try await harness.store.fetchMessage(id: harness.messageID)
        #expect(postDrainMessage?.timestamp == harness.originalTimestamp,
                "rehydrate must preserve original wire timestamp so mesh dedup catches duplicate landing")

        await harness.service.shutdown()
    }

    /// Case 3 / Bump failure: when `incrementPendingSendAttemptCount` throws,
    /// the drain closure must park the envelope on the transport-open trigger
    /// (status reverts to `.pending`), leave the PendingSend row intact, and
    /// must not call `sendPendingDirectMessage`. Without this guarantee a
    /// SwiftData failure during the bump could either drop the envelope
    /// silently or double-send on retry.
    @Test("bump failure parks envelope, preserves row, does not call sendPendingDirectMessage")
    func bumpFailureParksWithoutSending() async throws {
        let harness = try await Self.setupQueueWithRow(attemptCount: 0)

        // Force every subsequent incrementPendingSendAttemptCount call to throw
        // so the drain closure takes the bump-failure park branch.
        struct FakeSaveFailure: Error {}
        await harness.store.setIncrementPendingSendAttemptCountFaultInjection {
            throw FakeSaveFailure()
        }

        await harness.service.hydrate()
        try? await Task.sleep(for: .milliseconds(500))

        let rows = try await harness.store.fetchPendingSends(radioID: harness.radioID)
        #expect(rows.count == 1,
                "PendingSend row must survive the bump-failure park so the next transport-open can retry")
        #expect(rows.first?.attemptCount == 0,
                "attemptCount must not advance when the bump throws — the next drain bumps to the same target")

        let postDrainMessage = try await harness.store.fetchMessage(id: harness.messageID)
        #expect(postDrainMessage?.timestamp == harness.originalTimestamp,
                "bump-failure park must run before any wire-affecting work — wire timestamp untouched")
        #expect(postDrainMessage?.status == .pending,
                "park branch must remap status back to .pending so the bubble does not flicker .failed")

        await harness.service.shutdown()
    }
}
