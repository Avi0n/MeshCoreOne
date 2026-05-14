import SwiftUI
import UIKit
import MC1Services
@testable import MC1

/// Shared fixtures for `UnifiedMessageBubble` tests. Pure constructors —
/// `MessageItem` carries fragments with `ImageReference` handles; the
/// resolver closure returned by `messageItem` resolves those handles back to
/// the UIImages supplied at construction.
enum MessageBubbleTestData {

    // MARK: - Stable identifiers

    static let radioID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    static let contactID = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    static let outgoingMessageID = UUID(uuidString: "33333333-3333-3333-3333-333333333333")!
    static let incomingMessageID = UUID(uuidString: "44444444-4444-4444-4444-444444444444")!

    /// Fixed reference instant so timestamp-driven rendering stays deterministic.
    static let referenceDate = Date(timeIntervalSince1970: 1_700_000_000)

    static let defaultSenderKeyPrefix = Data([0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45])

    // MARK: - MessageDTO

    static func outgoingDM(
        text: String = "Hello world",
        status: MessageStatus = .sent,
        heardRepeats: Int = 0,
        sendCount: Int = 1,
        retryAttempt: Int = 0,
        maxRetryAttempts: Int = 0,
        reactionSummary: String? = nil
    ) -> MessageDTO {
        MessageDTO(
            id: outgoingMessageID,
            radioID: radioID,
            contactID: contactID,
            channelIndex: nil,
            text: text,
            timestamp: UInt32(referenceDate.timeIntervalSince1970),
            createdAt: referenceDate,
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
            sendCount: sendCount,
            retryAttempt: retryAttempt,
            maxRetryAttempts: maxRetryAttempts,
            reactionSummary: reactionSummary
        )
    }

    static func incomingChannel(
        text: String = "Hello channel",
        senderNodeName: String? = "Alice",
        channelIndex: UInt8 = 1,
        pathLength: UInt8 = 0x03,
        pathNodes: Data? = Data([0xA3, 0x7F, 0x42]),
        regionScope: String? = nil,
        containsSelfMention: Bool = false,
        reactionSummary: String? = nil
    ) -> MessageDTO {
        MessageDTO(
            id: incomingMessageID,
            radioID: radioID,
            contactID: nil,
            channelIndex: channelIndex,
            text: text,
            timestamp: UInt32(referenceDate.timeIntervalSince1970),
            createdAt: referenceDate,
            direction: .incoming,
            status: .delivered,
            textType: .plain,
            ackCode: nil,
            pathLength: pathLength,
            snr: nil,
            pathNodes: pathNodes,
            senderKeyPrefix: defaultSenderKeyPrefix,
            senderNodeName: senderNodeName,
            isRead: true,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0,
            containsSelfMention: containsSelfMention,
            reactionSummary: reactionSummary,
            regionScope: regionScope
        )
    }

    // MARK: - MessageItem + imageResolver

    /// Bundle returned by `messageItem` for tests that need both the structural
    /// `MessageItem` and a synchronous image resolver for fragment rendering.
    struct ItemBundle {
        let item: MessageItem
        let imageResolver: (ImageReference) -> UIImage?
    }

    /// Build a `MessageItem` via the production `MessageFragmentBuilder` from
    /// test inputs that mirror today's `displayState(...)` factory signature.
    /// Decoded UIImages provided here are stashed in an in-memory map keyed by
    /// `(message.id, role)` so the returned `imageResolver` closure resolves
    /// fragment-side `ImageReference` handles back to the supplied UIImages.
    @MainActor
    static func messageItem(
        message: MessageDTO,
        showTimestamp: Bool = false,
        showDirectionGap: Bool = false,
        showSenderName: Bool = true,
        showNewMessagesDivider: Bool = false,
        previewState: PreviewLoadState = .idle,
        loadedPreview: LinkPreviewDataDTO? = nil,
        detectedURL: URL? = nil,
        formattedText: AttributedString? = nil,
        decodedImage: UIImage? = nil,
        decodedPreviewImage: UIImage? = nil,
        decodedPreviewIcon: UIImage? = nil,
        isGIF: Bool = false,
        showInlineImages: Bool = false,
        autoPlayGIFs: Bool = true,
        previewsEnabled: Bool = true,
        currentUserName: String = "Me",
        formattedPath: String? = nil,
        showIncomingHopCount: Bool = false,
        showIncomingRegion: Bool = false,
        senderResolution: NodeNameResolution = NodeNameResolution(displayName: "Unknown", matchKind: .unresolved)
    ) -> ItemBundle {
        let inputs = MessageBuildInputs(
            messageID: message.id,
            previewState: previewState,
            loadedPreview: loadedPreview,
            cachedURL: detectedURL,
            hasInlineImageRef: decodedImage != nil,
            hasPreviewImageRef: decodedPreviewImage != nil,
            hasPreviewIconRef: decodedPreviewIcon != nil,
            imageIsGIF: isGIF,
            formattedText: formattedText,
            baseColor: message.isOutgoing ? .outgoing : .incoming,
            formattedPath: formattedPath,
            senderResolution: senderResolution,
            showTimestamp: showTimestamp,
            showDirectionGap: showDirectionGap,
            showSenderName: showSenderName,
            showNewMessagesDivider: showNewMessagesDivider
        )
        let envInputs = EnvInputs(
            showInlineImages: showInlineImages,
            autoPlayGIFs: autoPlayGIFs,
            showIncomingPath: formattedPath != nil,
            showIncomingHopCount: showIncomingHopCount,
            showIncomingRegion: showIncomingRegion,
            previewsEnabled: previewsEnabled,
            isHighContrast: false,
            currentUserName: currentUserName
        )
        let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: envInputs)

        let resolver: (ImageReference) -> UIImage? = { ref in
            guard ref.cacheKey == message.id else { return nil }
            switch ref.role {
            case .inline: return decodedImage
            case .linkPreviewImage: return decodedPreviewImage
            case .linkPreviewIcon: return decodedPreviewIcon
            }
        }
        return ItemBundle(item: item, imageResolver: resolver)
    }

}
