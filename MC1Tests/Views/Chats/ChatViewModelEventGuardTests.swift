import Testing
import Foundation
@testable import MC1
@testable import MC1Services

/// Verifies that event-stream handling either applies status updates in place
/// (`messageStatusResolved`) or routes through the coordinator's `enqueueReload`
/// chokepoint for events that need a fresh DTO read (`messageResent`,
/// `messageFailed`, `heardRepeatRecorded`, `reactionReceived`, `messageRetrying`).
/// `ChatCoordinator` coalesces concurrent IDs into one load cycle so no
/// ack / retry / fail / heard-repeat / reaction event is dropped during an
/// off-main timeline build.
@Suite("ChatViewModel event-stream guards")
@MainActor
struct ChatViewModelEventGuardTests {

    private func makeMessage(status: MessageStatus = .sending) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: UUID(),
            channelIndex: nil,
            text: "hello",
            timestamp: 1_000,
            createdAt: Date(timeIntervalSince1970: 1_000),
            direction: .outgoing,
            status: status,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: nil,
            isRead: false,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
    }

    @Test("messageStatusResolved applies status in place via the coordinator")
    func messageStatusResolved_appliesStatusInPlace() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        let message = makeMessage()
        _ = coordinator.append(message)

        await viewModel.handle(.messageStatusResolved(messageID: message.id, status: .delivered))

        #expect(coordinator.messagesByID[message.id]?.status == .delivered)
        #expect(coordinator.reloadInFlight == false,
                "messageStatusResolved no longer schedules a reload — it mutates the DTO in place.")
    }

    @Test("messageStatusResolved with no coordinator is a no-op")
    func messageStatusResolved_skipsWithoutCoordinator() async {
        let viewModel = ChatViewModel()
        // Intentionally leave coordinator unbound to mirror the
        // conversation-list view-model lifecycle.

        await viewModel.handle(.messageStatusResolved(messageID: UUID(), status: .sent))
        // No assertion needed beyond reaching this line without a crash:
        // the handler must guard on `coordinator != nil`.
    }

    @Test("messageResent schedules a reload via the coordinator")
    func messageResent_enqueuesReload() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        let message = makeMessage(status: .failed)
        _ = coordinator.append(message)

        #expect(!coordinator.reloadInFlight)
        await viewModel.handle(.messageResent(messageID: message.id))
        #expect(coordinator.reloadInFlight,
                "messageResent must schedule a reload so heardRepeats / sendCount / status refresh together")
    }

    @Test("messageFailed schedules a reload via the coordinator")
    func messageFailed_enqueuesReload() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        let message = makeMessage()
        _ = coordinator.append(message)

        #expect(!coordinator.reloadInFlight)
        await viewModel.handle(.messageFailed(messageID: message.id))
        #expect(coordinator.reloadInFlight,
                "messageFailed must schedule a reload so the bubble re-reads the failed status")
    }

    @Test("heardRepeatRecorded schedules a reload via the coordinator")
    func heardRepeatRecorded_enqueuesReload() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        let message = makeMessage(status: .sent)
        _ = coordinator.append(message)

        #expect(!coordinator.reloadInFlight)
        await viewModel.handle(.heardRepeatRecorded(messageID: message.id, count: 2))
        #expect(coordinator.reloadInFlight,
                "heardRepeatRecorded must schedule a reload so the bubble re-reads heardRepeats")
    }

    @Test("reactionReceived schedules a reload via the coordinator")
    func reactionReceived_enqueuesReload() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        let message = makeMessage(status: .delivered)
        _ = coordinator.append(message)

        #expect(!coordinator.reloadInFlight)
        await viewModel.handle(.reactionReceived(messageID: message.id, summary: "👍 1"))
        #expect(coordinator.reloadInFlight,
                "reactionReceived must schedule a reload so the reaction badge appears")
    }

    @Test("messageStatusResolved does not downgrade .delivered to .sent")
    func messageStatusResolved_doesNotDowngradeDelivered() async {
        let viewModel = ChatViewModel()
        let coordinator = ChatCoordinator.makeForTesting()
        viewModel.coordinator = coordinator

        let message = makeMessage()
        _ = coordinator.append(message)

        await viewModel.handle(.messageStatusResolved(messageID: message.id, status: .delivered, roundTripTime: 1_500))
        #expect(coordinator.messagesByID[message.id]?.status == .delivered)

        await viewModel.handle(.messageStatusResolved(messageID: message.id, status: .sent))
        #expect(coordinator.messagesByID[message.id]?.status == .delivered, "Late .sent must not downgrade .delivered")
        #expect(coordinator.messagesByID[message.id]?.roundTripTime == 1_500, "Late .sent must not clobber RTT")
    }
}
