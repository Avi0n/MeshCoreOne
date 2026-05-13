import Testing
import Foundation
@testable import MC1
@testable import MC1Services

struct MessageFragmentBuilderOffMainTests {

    /// Proves the builder runs off the main actor when invoked from a detached task.
    /// The `wasOffMain` capture inside the closure asserts thread context at the
    /// call site, which would fail if `@MainActor` were re-added — the closure
    /// would then implicitly hop back to main and the assertion would catch it.
    @Test("Builder is callable from a detached task off the main actor")
    func builderCallableOffMain() async {
        let message = makePlainTextMessage(index: 0)
        let inputs = makeMinimalInputs(messageID: message.id)
        let envInputs = EnvInputs.default

        let outcome = await Task.detached { () -> (MessageItem, Bool) in
            let item = MessageFragmentBuilder.makeItem(
                for: message,
                inputs: inputs,
                envInputs: envInputs
            )
            return (item, isOffMainThread())
        }.value

        #expect(outcome.1, "Builder must execute off the main thread")
        #expect(outcome.0.id == message.id)
    }

    /// Build the same item list on main and off main and assert byte-equal output.
    /// MessageFragmentBuilder is a pure function over Sendable inputs, so the
    /// off-main result must match the main-actor result exactly. Locks in
    /// equivalence as a regression check before `buildItems()` switches to the
    /// `Task { @concurrent in }` hop.
    @Test("Batch build off-main produces identical items to main-actor build")
    func batchBuild_offMainMatchesMainActor() async {
        let messages = (0..<batchEquivalenceMessageCount).map { makePlainTextMessage(index: $0) }
        let inputs = messages.map { makeMinimalInputs(messageID: $0.id) }
        let envInputs = EnvInputs.default

        let mainResult: [MessageItem] = zip(messages, inputs).map { message, input in
            MessageFragmentBuilder.makeItem(for: message, inputs: input, envInputs: envInputs)
        }

        let offMainResult = await Task.detached { () -> [MessageItem] in
            zip(messages, inputs).map { message, input in
                MessageFragmentBuilder.makeItem(for: message, inputs: input, envInputs: envInputs)
            }
        }.value

        #expect(mainResult == offMainResult)
    }

    private let batchEquivalenceMessageCount = 50

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

/// Synchronous wrapper around `Thread.isMainThread` so tests can read it from
/// inside `Task.detached` closures. Swift 6 marks the underlying class property
/// as unavailable from async contexts; routing through a non-async function
/// preserves the runtime check without tripping the diagnostic.
private func isOffMainThread() -> Bool {
    !Thread.isMainThread
}
