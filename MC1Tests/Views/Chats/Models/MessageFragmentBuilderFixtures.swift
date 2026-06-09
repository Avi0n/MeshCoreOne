import Foundation
@testable import MC1
@testable import MC1Services

/// Shared minimal fixtures for the off-main and benchmark suites of
/// `MessageFragmentBuilder`. The richer fixtures in
/// `MessageFragmentBuilderTests` stay local to that file because they take
/// many parameter overrides.
enum MessageFragmentBuilderFixtures {
    static let radioID = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
    static let contactID = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!
    static let referenceDate = Date(timeIntervalSince1970: 1_700_000_000)

    static func makePlainTextMessage(index: Int) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: radioID,
            contactID: contactID,
            channelIndex: nil,
            text: "Message \(index)",
            timestamp: UInt32(referenceDate.timeIntervalSince1970),
            createdAt: referenceDate,
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

    static func makeMinimalInputs(messageID: UUID) -> MessageBuildInputs {
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
            baseColor: .incoming,
            formattedPath: nil,
            senderResolution: NodeNameResolution(displayName: "Sender", matchKind: .exact),
            showTimestamp: false,
            showDirectionGap: false,
            showSenderName: false,
            showNewMessagesDivider: false
        )
    }

    static func makeMessage(
        id: UUID = UUID(),
        text: String = "hello",
        status: MessageStatus = .sent,
        heardRepeats: Int = 0,
        sendCount: Int = 1,
        retryAttempt: Int = 0,
        maxRetryAttempts: Int = 0
    ) -> MessageDTO {
        MessageDTO(
            id: id,
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
            maxRetryAttempts: maxRetryAttempts
        )
    }

    static func makeInputs(messageID: UUID) -> MessageBuildInputs {
        makeMinimalInputs(messageID: messageID)
    }

    static func makeInputs(
        for message: MessageDTO,
        mapPreviewLatitude: Double? = nil,
        mapPreviewLongitude: Double? = nil,
        isMapPreviewReady: Bool = false
    ) -> MessageBuildInputs {
        MessageBuildInputs(
            messageID: message.id,
            previewState: .idle,
            loadedPreview: nil,
            cachedURL: nil,
            hasInlineImageRef: false,
            hasPreviewImageRef: false,
            hasPreviewIconRef: false,
            imageIsGIF: false,
            mapPreviewLatitude: mapPreviewLatitude,
            mapPreviewLongitude: mapPreviewLongitude,
            isMapPreviewReady: isMapPreviewReady,
            formattedText: nil,
            baseColor: .incoming,
            formattedPath: nil,
            senderResolution: NodeNameResolution(displayName: "Sender", matchKind: .exact),
            showTimestamp: false,
            showDirectionGap: false,
            showSenderName: false,
            showNewMessagesDivider: false
        )
    }

    static func makeEnvInputs(isOutgoing: Bool = true) -> EnvInputs {
        EnvInputs(
            showInlineImages: true,
            autoPlayGIFs: true,
            showIncomingPath: false,
            showIncomingHopCount: false,
            showIncomingRegion: false,
            showIncomingSendTime: false,
            previewsEnabled: false,
            isHighContrast: false,
            isDark: false,
            showMapPreviews: true,
            isOffline: false,
            currentUserName: isOutgoing ? "Me" : "Sender",
            themeID: "default"
        )
    }
}
