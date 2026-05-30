import Foundation
import SwiftUI
import Testing
@testable import MC1
@testable import MC1Services

@Suite("MessageFragmentBuilder")
@MainActor
struct MessageFragmentBuilderTests {

    @Test("plain text produces a single text fragment")
    func plainText_producesTextFragmentOnly() {
        let message = makeMessage(text: "hello")
        let inputs = makeInputs(messageID: message.id)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.content.count == 1)
        guard case .text(let text) = item.content[0] else {
            Issue.record("expected .text fragment")
            return
        }
        #expect(text.raw == "hello")
    }

    @Test("reaction summary appears after the text fragment")
    func reactions_appendAfterText() {
        let message = makeMessage(text: "hi", reactionSummary: "👍:1")
        let inputs = makeInputs(messageID: message.id)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.content.count == 2)
        guard case .text = item.content[0] else {
            Issue.record("expected .text fragment at index 0")
            return
        }
        guard case .reactionSummary(let summary) = item.content[1] else {
            Issue.record("expected .reactionSummary fragment at index 1")
            return
        }
        #expect(summary == "👍:1")
    }

    /// Adding a reaction must flip `MessageItem.hashValue` so the diffable
    /// data source reloads the cell. Without this, an in-progress reaction
    /// add would not visibly update until another property (status, etc.)
    /// changes. Covers the rebuild-trigger contract for the reaction path.
    @Test("adding a reaction flips the item hash")
    func reactionSummaryChange_flipsItemHash() {
        let messageID = UUID()
        let messageWithoutReaction = makeMessage(id: messageID, text: "hi")
        let messageWithReaction = makeMessage(id: messageID, text: "hi", reactionSummary: "👍:1")
        let inputs = makeInputs(messageID: messageID)
        let itemA = MessageFragmentBuilder.makeItem(for: messageWithoutReaction, inputs: inputs, envInputs: makeEnvInputs())
        let itemB = MessageFragmentBuilder.makeItem(for: messageWithReaction, inputs: inputs, envInputs: makeEnvInputs())
        #expect(itemA.hashValue != itemB.hashValue)
        #expect(itemA != itemB)
    }

    @Test("malware warning replaces preview and inline image fragments")
    func malwareWarning_replacesPreviewAndImage() {
        let message = makeMessage(text: "click me")
        let inputs = makeInputs(
            messageID: message.id,
            previewState: .malwareWarning,
            cachedURL: URL(string: "https://bad.example")!,
            hasCachedURLEntry: true
        )
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        let kinds: [FragmentKind] = item.content.map(Self.kind(of:))
        #expect(kinds == [.text, .malwareWarning])
    }

    @Test("legacy link preview surfaces with persisted fields when state is idle")
    func legacyLinkPreview_idleStateWithPersistedFields() {
        let message = makeMessage(
            text: "see",
            linkPreviewURL: "https://example.com",
            linkPreviewTitle: "Example"
        )
        let inputs = makeInputs(
            messageID: message.id,
            previewState: .idle,
            previewsEnabled: true
        )
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs(previewsEnabled: true))
        guard case .linkPreview(let state) = item.content.last,
              case .legacy(let url, let title, _, _) = state.mode else {
            Issue.record("expected .legacy preview")
            return
        }
        #expect(url.absoluteString == "https://example.com")
        #expect(title == "Example")
    }

    @Test("legacy link preview carries image reference when inputs say so")
    func legacyLinkPreview_imageReferenceProducedWhenInputsSayHasImageRef() {
        let message = makeMessage(
            text: "see",
            linkPreviewURL: "https://example.com",
            linkPreviewTitle: "Example"
        )
        let inputs = makeInputs(
            messageID: message.id,
            previewState: .idle,
            hasPreviewImageRef: true,
            previewsEnabled: true
        )
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs(previewsEnabled: true))
        guard case .linkPreview(let state) = item.content.last,
              case .legacy(_, _, let imageRef, _) = state.mode else {
            Issue.record("expected .legacy preview")
            return
        }
        #expect(imageRef == ImageReference(cacheKey: message.id, role: .linkPreviewImage))
    }

    @Test("same inputs produce equal items and equal hashes")
    func hashStability_sameInputsProduceSameItem() {
        let message = makeMessage(text: "hello")
        let inputs = makeInputs(messageID: message.id)
        let a = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        let b = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(a == b)
        #expect(a.hashValue == b.hashValue)
    }

    @Test("shouldRequestPreviewFetch is true on idle with URL and no legacy fields")
    func shouldRequestPreviewFetch_trueOnIdleWithURLAndNoLegacy() {
        let message = makeMessage(text: "hi")
        let inputs = makeInputs(
            messageID: message.id,
            previewState: .idle,
            cachedURL: URL(string: "https://example.com")!,
            hasCachedURLEntry: true
        )
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.shouldRequestPreviewFetch == true)
    }

    @Test("shouldRequestPreviewFetch is false on legacy message")
    func shouldRequestPreviewFetch_falseOnLegacyMessage() {
        let message = makeMessage(text: "hi", linkPreviewURL: "https://example.com")
        let inputs = makeInputs(
            messageID: message.id,
            previewState: .idle,
            cachedURL: URL(string: "https://example.com")!,
            hasCachedURLEntry: true
        )
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.shouldRequestPreviewFetch == false)
    }

    @Test("envelope captures containsSelfMention from message")
    func envelope_capturesContainsSelfMention() {
        let message = makeMessage(text: "hi", containsSelfMention: true)
        let inputs = makeInputs(messageID: message.id)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.envelope.containsSelfMention == true)
    }

    @Test("envelope captures mentionSeen from message")
    func envelope_capturesMentionSeen() {
        let message = makeMessage(text: "hi", containsSelfMention: true, mentionSeen: true)
        let inputs = makeInputs(messageID: message.id)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.envelope.mentionSeen == true)
    }

    @Test("envelope date is the message send time, not its drain time")
    func envelope_dateIsSendTime() {
        // A drained backlog row sent three days before it was received. The centered
        // divider is the sole time surface, so it must read send time — otherwise a
        // days-old message gets relabeled "Today" at the block's delivery time.
        let drainTime = Self.referenceDate
        let sendTime = Self.referenceDate.addingTimeInterval(-3 * 24 * 60 * 60)
        let message = MessageDTO(
            id: UUID(),
            radioID: Self.radioID,
            contactID: Self.contactID,
            channelIndex: nil,
            text: "older message just arrived",
            timestamp: UInt32(sendTime.timeIntervalSince1970),
            createdAt: drainTime,
            direction: .incoming,
            status: .delivered,
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
        let inputs = makeInputs(messageID: message.id)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())

        #expect(item.envelope.date == message.senderDate)
        #expect(message.senderDate != message.date, "test must distinguish send time from drain time")
    }

    @Test("footer captures heardRepeats from message")
    func footer_capturesHeardRepeats() {
        let message = makeMessage(text: "hi", heardRepeats: 3)
        let inputs = makeInputs(messageID: message.id)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.footer.heardRepeats == 3)
    }

    @Test("footer captures retryAttempt from message")
    func footer_capturesRetryAttempt() {
        let message = makeMessage(text: "hi", retryAttempt: 2)
        let inputs = makeInputs(messageID: message.id)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.footer.retryAttempt == 2)
    }

    @Test("footer captures maxRetryAttempts from message")
    func footer_capturesMaxRetryAttempts() {
        let message = makeMessage(text: "hi", maxRetryAttempts: 5)
        let inputs = makeInputs(messageID: message.id)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(item.footer.maxRetryAttempts == 5)
    }

    // MARK: - Hash propagation regression

    @Test("previewState change flips the item hash")
    func previewStateChange_flipsHash() {
        let messageID = UUID()
        let message = makeMessage(id: messageID, text: "hi")
        let url = URL(string: "https://example.com")!
        let idle = makeInputs(
            messageID: messageID,
            previewState: .idle,
            cachedURL: url,
            previewsEnabled: true
        )
        let loaded = makeInputs(
            messageID: messageID,
            previewState: .loaded,
            cachedURL: url,
            previewsEnabled: true
        )
        let env = makeEnvInputs(previewsEnabled: true)
        let a = MessageFragmentBuilder.makeItem(for: message, inputs: idle, envInputs: env)
        let b = MessageFragmentBuilder.makeItem(for: message, inputs: loaded, envInputs: env)
        #expect(a.hashValue != b.hashValue)
    }

    @Test("loadedPreview change flips the item hash")
    func loadedPreviewChange_flipsHash() {
        let messageID = UUID()
        let message = makeMessage(id: messageID, text: "hi")
        let preview = LinkPreviewDataDTO(
            url: "https://example.com",
            title: "Example",
            fetchedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let withoutPreview = makeInputs(
            messageID: messageID,
            previewState: .loaded,
            previewsEnabled: true
        )
        let withPreview = makeInputs(
            messageID: messageID,
            previewState: .loaded,
            loadedPreview: preview,
            previewsEnabled: true
        )
        let env = makeEnvInputs(previewsEnabled: true)
        let a = MessageFragmentBuilder.makeItem(for: message, inputs: withoutPreview, envInputs: env)
        let b = MessageFragmentBuilder.makeItem(for: message, inputs: withPreview, envInputs: env)
        #expect(a.hashValue != b.hashValue)
    }

    @Test("MessageDTO field change flips the item hash", arguments: hashFlipScenarios)
    func messageFieldChange_flipsHash(scenario: HashFlipScenario) {
        let messageID = UUID()
        let inputs = makeInputs(messageID: messageID)
        let env = makeEnvInputs()
        let a = MessageFragmentBuilder.makeItem(
            for: makeMessageVariant(id: messageID, factor: scenario.factorA),
            inputs: inputs,
            envInputs: env
        )
        let b = MessageFragmentBuilder.makeItem(
            for: makeMessageVariant(id: messageID, factor: scenario.factorB),
            inputs: inputs,
            envInputs: env
        )
        #expect(a.hashValue != b.hashValue, "\(scenario.name) change must flip the item hash")
    }

    nonisolated static let hashFlipScenarios: [HashFlipScenario] = [
        HashFlipScenario(name: "heardRepeats", factorA: .heardRepeats(0), factorB: .heardRepeats(3)),
        HashFlipScenario(name: "retryAttempt", factorA: .retryAttempt(0), factorB: .retryAttempt(1)),
        HashFlipScenario(name: "maxRetryAttempts", factorA: .maxRetryAttempts(3), factorB: .maxRetryAttempts(5)),
        HashFlipScenario(name: "status", factorA: .status(.sent), factorB: .status(.delivered)),
        HashFlipScenario(name: "containsSelfMention", factorA: .containsSelfMention(false), factorB: .containsSelfMention(true)),
        HashFlipScenario(name: "mentionSeen", factorA: .mentionSeen(false), factorB: .mentionSeen(true)),
    ]

    struct HashFlipScenario: Sendable, CustomStringConvertible {
        let name: String
        let factorA: HashFlipFactor
        let factorB: HashFlipFactor
        var description: String { name }
    }

    enum HashFlipFactor: Sendable {
        case heardRepeats(Int)
        case retryAttempt(Int)
        case maxRetryAttempts(Int)
        case status(MessageStatus)
        case containsSelfMention(Bool)
        /// Implies `containsSelfMention: true` so the hash distinction is the
        /// `mentionSeen` flag alone.
        case mentionSeen(Bool)
    }

    private func makeMessageVariant(id: UUID, factor: HashFlipFactor) -> MessageDTO {
        switch factor {
        case .heardRepeats(let value):
            return makeMessage(id: id, heardRepeats: value)
        case .retryAttempt(let value):
            return makeMessage(id: id, retryAttempt: value)
        case .maxRetryAttempts(let value):
            return makeMessage(id: id, maxRetryAttempts: value)
        case .status(let value):
            return makeMessage(id: id, status: value)
        case .containsSelfMention(let value):
            return makeMessage(id: id, containsSelfMention: value)
        case .mentionSeen(let value):
            return makeMessage(id: id, containsSelfMention: true, mentionSeen: value)
        }
    }

    // MARK: - Helpers

    private static let radioID = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
    private static let contactID = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!
    private static let referenceDate = Date(timeIntervalSince1970: 1_700_000_000)

    private func makeMessage(
        id: UUID = UUID(),
        text: String = "hello",
        status: MessageStatus = .sent,
        reactionSummary: String? = nil,
        linkPreviewURL: String? = nil,
        linkPreviewTitle: String? = nil,
        heardRepeats: Int = 0,
        retryAttempt: Int = 0,
        maxRetryAttempts: Int = 0,
        containsSelfMention: Bool = false,
        mentionSeen: Bool = false
    ) -> MessageDTO {
        MessageDTO(
            id: id,
            radioID: Self.radioID,
            contactID: Self.contactID,
            channelIndex: nil,
            text: text,
            timestamp: UInt32(Self.referenceDate.timeIntervalSince1970),
            createdAt: Self.referenceDate,
            direction: .outgoing,
            status: status,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: nil,
            isRead: true,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: heardRepeats,
            retryAttempt: retryAttempt,
            maxRetryAttempts: maxRetryAttempts,
            linkPreviewURL: linkPreviewURL,
            linkPreviewTitle: linkPreviewTitle,
            containsSelfMention: containsSelfMention,
            mentionSeen: mentionSeen,
            reactionSummary: reactionSummary
        )
    }

    private func makeEnvInputs(
        showInlineImages: Bool = true,
        autoPlayGIFs: Bool = true,
        showIncomingPath: Bool = false,
        showIncomingHopCount: Bool = false,
        showIncomingRegion: Bool = false,
        previewsEnabled: Bool = false,
        isHighContrast: Bool = false,
        isDark: Bool = false,
        showMapPreviews: Bool = true,
        isOffline: Bool = false,
        currentUserName: String = "Me"
    ) -> EnvInputs {
        EnvInputs(
            showInlineImages: showInlineImages,
            autoPlayGIFs: autoPlayGIFs,
            showIncomingPath: showIncomingPath,
            showIncomingHopCount: showIncomingHopCount,
            showIncomingRegion: showIncomingRegion,
            previewsEnabled: previewsEnabled,
            isHighContrast: isHighContrast,
            isDark: isDark,
            showMapPreviews: showMapPreviews,
            isOffline: isOffline,
            currentUserName: currentUserName,
            themeID: "default"
        )
    }

    @Test("A coordinate in build inputs emits a mapPreview fragment")
    func mapPreviewEmittedWhenCoordinatePresent() {
        let message = MessageFragmentBuilderFixtures.makeMessage(text: "Meet at 37.7749, -122.4194")
        let inputs = MessageFragmentBuilderFixtures.makeInputs(
            for: message,
            mapPreviewLatitude: 37.7749,
            mapPreviewLongitude: -122.4194,
            isMapPreviewReady: true
        )
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs(isDark: true))

        guard case .mapPreview(let state) = item.content.last else {
            Issue.record("expected a trailing mapPreview fragment")
            return
        }
        #expect(state.latitude == 37.7749)
        #expect(state.longitude == -122.4194)
        #expect(state.isDark == true)
        #expect(state.isReady == true)
    }

    @Test("No coordinate means no mapPreview fragment")
    func noMapPreviewWhenCoordinateAbsent() {
        let message = MessageFragmentBuilderFixtures.makeMessage(text: "no coordinates here")
        let inputs = MessageFragmentBuilderFixtures.makeInputs(for: message)
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
        #expect(!item.content.contains { if case .mapPreview = $0 { return true } else { return false } })
    }

    private func makeInputs(
        messageID: UUID,
        previewState: PreviewLoadState = .idle,
        loadedPreview: LinkPreviewDataDTO? = nil,
        cachedURL: URL? = nil,
        hasCachedURLEntry: Bool = false,
        hasInlineImageRef: Bool = false,
        hasPreviewImageRef: Bool = false,
        hasPreviewIconRef: Bool = false,
        imageIsGIF: Bool = false,
        formattedText: AttributedString? = nil,
        showInlineImages: Bool = true,
        autoPlayGIFs: Bool = true,
        previewsEnabled: Bool = false,
        currentUserName: String = "Me",
        baseColor: BaseColorSlot = .incoming,
        formattedPath: String? = nil,
        showIncomingHopCount: Bool = false,
        showIncomingRegion: Bool = false,
        configurationShowSenderName: Bool = false,
        senderResolution: NodeNameResolution = NodeNameResolution(displayName: "Sender", matchKind: .exact),
        showTimestamp: Bool = false,
        showDirectionGap: Bool = false,
        showSenderName: Bool = false,
        showNewMessagesDivider: Bool = false
    ) -> MessageBuildInputs {
        MessageBuildInputs(
            messageID: messageID,
            previewState: previewState,
            loadedPreview: loadedPreview,
            cachedURL: cachedURL,
            hasInlineImageRef: hasInlineImageRef,
            hasPreviewImageRef: hasPreviewImageRef,
            hasPreviewIconRef: hasPreviewIconRef,
            imageIsGIF: imageIsGIF,
            formattedText: formattedText,
            baseColor: baseColor,
            formattedPath: formattedPath,
            senderResolution: senderResolution,
            showTimestamp: showTimestamp,
            showDirectionGap: showDirectionGap,
            showSenderName: showSenderName,
            showNewMessagesDivider: showNewMessagesDivider
        )
    }

    private enum FragmentKind: Equatable {
        case text, inlineImage, linkPreview, mapPreview, malwareWarning, reactionSummary
    }

    private static func kind(of fragment: MessageFragment) -> FragmentKind {
        switch fragment {
        case .text: return .text
        case .inlineImage: return .inlineImage
        case .linkPreview: return .linkPreview
        case .mapPreview: return .mapPreview
        case .malwareWarning: return .malwareWarning
        case .reactionSummary: return .reactionSummary
        }
    }
}
