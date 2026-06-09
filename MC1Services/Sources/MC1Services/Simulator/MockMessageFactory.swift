import Foundation

/// Builds a `MessageDTO` with demo defaults so each seed message specifies only
/// what differs from a plain, read, single-hop message. `timestamp` derives from
/// `createdAt`; a clock-corrected message instead passes its own `senderTimestamp`.
enum MockMessageFactory {
    static func message(
        id: UUID,
        createdAt: Date,
        text: String,
        direction: MessageDirection,
        status: MessageStatus = .delivered,
        contactID: UUID? = nil,
        channelIndex: UInt8? = nil,
        textType: TextType = .plain,
        ackCode: UInt32? = nil,
        pathLength: UInt8 = 1,
        snr: Double? = nil,
        pathNodes: Data? = nil,
        senderKeyPrefix: Data? = nil,
        senderNodeName: String? = nil,
        isRead: Bool = true,
        replyToID: UUID? = nil,
        roundTripTime: UInt32? = nil,
        heardRepeats: Int = 0,
        retryAttempt: Int = 0,
        maxRetryAttempts: Int = 3,
        containsSelfMention: Bool = false,
        mentionSeen: Bool = false,
        timestampCorrected: Bool = false,
        senderTimestamp: UInt32? = nil,
        routeType: RouteType? = nil,
        regionScope: String? = nil
    ) -> MessageDTO {
        MessageDTO(
            id: id,
            radioID: MockDataProvider.simulatorDeviceID,
            contactID: contactID,
            channelIndex: channelIndex,
            text: text,
            timestamp: UInt32(createdAt.timeIntervalSince1970),
            createdAt: createdAt,
            direction: direction,
            status: status,
            textType: textType,
            ackCode: ackCode,
            pathLength: pathLength,
            snr: snr,
            pathNodes: pathNodes,
            senderKeyPrefix: senderKeyPrefix,
            senderNodeName: senderNodeName,
            isRead: isRead,
            replyToID: replyToID,
            roundTripTime: roundTripTime,
            heardRepeats: heardRepeats,
            retryAttempt: retryAttempt,
            maxRetryAttempts: maxRetryAttempts,
            containsSelfMention: containsSelfMention,
            mentionSeen: mentionSeen,
            timestampCorrected: timestampCorrected,
            senderTimestamp: senderTimestamp,
            routeType: routeType,
            regionScope: regionScope
        )
    }
}
