import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("MessageBubbleConfiguration")
struct MessageBubbleConfigurationTests {
    private func createContact(prefix: [UInt8], name: String, lastAdvertTimestamp: UInt32) -> ContactDTO {
        ContactDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data(prefix + Array(repeating: UInt8(0), count: 32 - prefix.count)),
            name: name,
            typeRawValue: ContactType.chat.rawValue,
            flags: 0,
            outPathLength: 0,
            outPath: Data(),
            lastAdvertTimestamp: lastAdvertTimestamp,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0
        )
    }

    private func createMessage(senderKeyPrefix: Data?, senderNodeName: String? = nil) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: nil,
            channelIndex: 0,
            text: "Test",
            timestamp: 0,
            createdAt: Date(),
            direction: .incoming,
            status: .delivered,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: senderKeyPrefix,
            senderNodeName: senderNodeName,
            isRead: true,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
    }

    @Test("channel sender resolver marks short prefix match as fallback")
    func channelSenderResolverMarksShortPrefixMatchAsFallback() {
        let older = createContact(prefix: [0xAA, 0x01], name: "Older", lastAdvertTimestamp: 100)
        let newer = createContact(prefix: [0xAA, 0x02], name: "Newer", lastAdvertTimestamp: 200)

        let result = MessageBubbleConfiguration.resolveSenderName(
            for: createMessage(senderKeyPrefix: Data([0xAA])),
            contacts: [older, newer]
        )

        #expect(result.displayName == "Newer")
        #expect(result.matchKind == .fallback)
    }

    @Test("channel sender resolver marks unique short prefix match as exact")
    func channelSenderResolverMarksUniqueShortPrefixMatchAsExact() {
        let contact = createContact(prefix: [0xAA, 0x01], name: "Alpha", lastAdvertTimestamp: 100)

        let result = MessageBubbleConfiguration.resolveSenderName(
            for: createMessage(senderKeyPrefix: Data([0xAA])),
            contacts: [contact]
        )

        #expect(result.displayName == "Alpha")
        #expect(result.matchKind == .exact)
    }
}
