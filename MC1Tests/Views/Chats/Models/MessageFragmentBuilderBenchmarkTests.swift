import Testing
import Foundation
import OSLog
@testable import MC1
@testable import MC1Services

@MainActor
struct MessageFragmentBuilderBenchmarkTests {

    /// Build 1,000 message items on the main actor and report elapsed time.
    /// Not a pass/fail gate — informational baseline for the off-main migration.
    /// Logger output surfaces in Console.app and Xcode test results;
    /// `print()` would be swallowed by Swift Testing's per-test capture.
    @Test("Baseline: build 1000 items on main actor")
    func baseline_buildOnMainActor() async {
        let messages = (0..<1000).map { i in makePlainTextMessage(index: i) }
        let envInputs = EnvInputs.default
        let inputs = messages.map { makeMinimalInputs(messageID: $0.id) }

        let start = ContinuousClock.now
        var items: [MessageItem] = []
        items.reserveCapacity(messages.count)
        for (message, perMessageInputs) in zip(messages, inputs) {
            items.append(MessageFragmentBuilder.makeItem(
                for: message,
                inputs: perMessageInputs,
                envInputs: envInputs
            ))
        }
        let elapsed = ContinuousClock.now - start

        #expect(items.count == 1000)
        Self.benchmarkLogger.notice("Baseline build on main actor: \(String(describing: elapsed), privacy: .public)")
    }

    private static let benchmarkLogger = Logger(
        subsystem: "com.meshcoreone.tests",
        category: "MessageFragmentBuilderBenchmark"
    )

    // MARK: - Fixtures

    private static let radioID = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
    private static let contactID = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!
    private static let referenceDate = Date(timeIntervalSince1970: 1_700_000_000)

    private func makePlainTextMessage(index: Int) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: Self.radioID,
            contactID: Self.contactID,
            channelIndex: nil,
            text: "Message \(index)",
            timestamp: UInt32(Self.referenceDate.timeIntervalSince1970),
            createdAt: Self.referenceDate,
            direction: .outgoing,
            status: .sent,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: nil,
            isRead: true,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
    }

    private func makeMinimalInputs(messageID: UUID) -> MessageBuildInputs {
        MessageBuildInputs(
            messageID: messageID,
            previewState: .idle,
            loadedPreview: nil,
            cachedURL: nil,
            hasInlineImageRef: false,
            hasPreviewImageRef: false,
            hasPreviewIconRef: false,
            imageIsGIF: false,
            formattedText: nil,
            baseColor: .primary,
            formattedPath: nil,
            senderResolution: NodeNameResolution(displayName: "Sender", matchKind: .exact),
            showTimestamp: false,
            showDirectionGap: false,
            showSenderName: false,
            showNewMessagesDivider: false
        )
    }
}
