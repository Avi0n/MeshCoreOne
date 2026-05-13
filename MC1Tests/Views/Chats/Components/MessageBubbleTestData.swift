import SwiftUI
import UIKit
import MC1Services
@testable import MC1

/// Value-type builders for `UnifiedMessageBubble` snapshot tests. Pure
/// constructors — `MessageDisplayState` already stores pre-decoded
/// images and `AttributedString`s, so no service layer is involved.
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

    static func outgoingChannel(
        text: String = "Hello channel",
        channelIndex: UInt8 = 1,
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
            contactID: nil,
            channelIndex: channelIndex,
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

    static func incomingDM(
        text: String = "Hi there",
        senderKeyPrefix: Data? = defaultSenderKeyPrefix,
        pathLength: UInt8 = 0xFF,
        pathNodes: Data? = nil,
        containsSelfMention: Bool = false,
        reactionSummary: String? = nil
    ) -> MessageDTO {
        MessageDTO(
            id: incomingMessageID,
            radioID: radioID,
            contactID: contactID,
            channelIndex: nil,
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
            senderKeyPrefix: senderKeyPrefix,
            senderNodeName: nil,
            isRead: true,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0,
            containsSelfMention: containsSelfMention,
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

    // MARK: - MessageDisplayState

    static func displayState(
        showTimestamp: Bool = false,
        showSenderName: Bool = true,
        showNewMessagesDivider: Bool = false,
        previewState: PreviewLoadState = .idle,
        loadedPreview: LinkPreviewDataDTO? = nil,
        detectedURL: URL? = nil,
        isImageURL: Bool = false,
        formattedText: AttributedString? = nil,
        decodedImage: UIImage? = nil,
        decodedPreviewImage: UIImage? = nil,
        decodedPreviewIcon: UIImage? = nil,
        isGIF: Bool = false,
        showInlineImages: Bool = false,
        autoPlayGIFs: Bool = true,
        formattedPath: String? = nil,
        showIncomingHopCount: Bool = false,
        showIncomingRegion: Bool = false
    ) -> MessageDisplayState {
        MessageDisplayState(
            showTimestamp: showTimestamp,
            showDirectionGap: false,
            showSenderName: showSenderName,
            showNewMessagesDivider: showNewMessagesDivider,
            detectedURL: detectedURL,
            previewState: previewState,
            loadedPreview: loadedPreview,
            isImageURL: isImageURL,
            decodedImage: decodedImage,
            decodedPreviewImage: decodedPreviewImage,
            decodedPreviewIcon: decodedPreviewIcon,
            isGIF: isGIF,
            showInlineImages: showInlineImages,
            autoPlayGIFs: autoPlayGIFs,
            showIncomingHopCount: showIncomingHopCount,
            showIncomingRegion: showIncomingRegion,
            formattedPath: formattedPath,
            formattedText: formattedText
        )
    }

    // MARK: - MessageBubbleConfiguration

    static func directMessageConfig() -> MessageBubbleConfiguration {
        .directMessage
    }

    static func channelConfig(
        isPublic: Bool = true,
        accentColor: Color? = nil
    ) -> MessageBubbleConfiguration {
        MessageBubbleConfiguration(
            accentColor: accentColor ?? (isPublic ? .green : .blue),
            showSenderName: true,
            isChannel: true,
            senderNameResolver: nil
        )
    }

    // MARK: - UIImage

    /// Deterministic flat-color image for snapshots that exercise image rendering.
    static func solidImage(
        _ color: UIColor,
        size: CGSize = CGSize(width: 16, height: 16)
    ) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            color.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
        }
    }
}
