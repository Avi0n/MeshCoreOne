import Foundation
import Testing
@testable import MC1
@testable import MC1Services

struct ChatConversationTypeTests {

    // MARK: - Test Helpers

    private func makeContact(
        id: UUID = UUID(),
        name: String = "TestUser",
        nickname: String? = nil,
        outPathLength: UInt8 = 2
    ) -> ContactDTO {
        ContactDTO(
            id: id,
            radioID: UUID(),
            publicKey: Data(),
            name: name,
            typeRawValue: 0,
            flags: 0,
            outPathLength: outPathLength,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nickname,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }

    private func makeChannel(
        id: UUID = UUID(),
        index: UInt8 = 1,
        name: String = "General"
    ) -> ChannelDTO {
        ChannelDTO(
            id: id,
            radioID: UUID(),
            index: index,
            name: name,
            secret: Data(repeating: 0, count: 16),
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0
        )
    }

    // MARK: - navigationTitle

    @Test("DM navigationTitle returns contact displayName")
    func dmNavigationTitleUsesDisplayName() {
        let contact = makeContact(name: "Alice")
        let sut = ChatConversationType.dm(contact)
        #expect(sut.navigationTitle == "Alice")
    }

    @Test("DM navigationTitle prefers nickname when set")
    func dmNavigationTitlePrefersNickname() {
        let contact = makeContact(name: "Alice", nickname: "Ally")
        let sut = ChatConversationType.dm(contact)
        #expect(sut.navigationTitle == "Ally")
    }

    @Test("Channel navigationTitle returns channel name")
    func channelNavigationTitleUsesChannelName() {
        let channel = makeChannel(name: "General")
        let sut = ChatConversationType.channel(channel)
        #expect(sut.navigationTitle == "General")
    }

    @Test("Channel navigationTitle returns default name when empty")
    func channelNavigationTitleFallback() {
        let channel = makeChannel(index: 3, name: "")
        let sut = ChatConversationType.channel(channel)
        #expect(sut.navigationTitle == L10n.Chats.Chats.Channel.defaultName(3))
    }

    // MARK: - navigationSubtitle

    @Test("DM subtitle shows flood routing when flood routed")
    func dmSubtitleFloodRouting() {
        let contact = makeContact(outPathLength: 0xFF)
        let sut = ChatConversationType.dm(contact)
        #expect(sut.navigationSubtitle == L10n.Chats.Chats.ConnectionStatus.floodRouting)
    }

    @Test("DM subtitle shows direct path with hop count")
    func dmSubtitleDirectPath() {
        let contact = makeContact(outPathLength: 2)
        let sut = ChatConversationType.dm(contact)
        #expect(sut.navigationSubtitle == L10n.Chats.Chats.ConnectionStatus.direct(contact.pathHopCount))
    }

    @Test("Channel subtitle shows public for public channel")
    func channelSubtitlePublic() {
        let channel = makeChannel(index: 0, name: "Public")
        let sut = ChatConversationType.channel(channel)
        #expect(sut.navigationSubtitle == L10n.Chats.Chats.Channel.typePublic)
    }

    @Test("Channel subtitle shows hashtag for hash-prefixed channel")
    func channelSubtitleHashPrefixed() {
        let channel = makeChannel(index: 5, name: "#random")
        let sut = ChatConversationType.channel(channel)
        #expect(sut.navigationSubtitle == L10n.Chats.Chats.ChannelInfo.ChannelType.hashtag)
    }

    @Test("Channel subtitle shows private for private channel")
    func channelSubtitlePrivate() {
        let channel = makeChannel(index: 3, name: "Secret")
        let sut = ChatConversationType.channel(channel)
        #expect(sut.navigationSubtitle == L10n.Chats.Chats.Channel.typePrivate)
    }

    // MARK: - conversationID

    @Test("DM conversationID returns contact ID")
    func dmConversationID() {
        let id = UUID()
        let contact = makeContact(id: id)
        let sut = ChatConversationType.dm(contact)
        #expect(sut.conversationID == id)
    }

    @Test("Channel conversationID returns channel ID")
    func channelConversationID() {
        let id = UUID()
        let channel = makeChannel(id: id)
        let sut = ChatConversationType.channel(channel)
        #expect(sut.conversationID == id)
    }

    // MARK: - isPublicStyleChannel

    @Test("DM isPublicStyleChannel is false")
    func dmIsNotPublicStyleChannel() {
        let sut = ChatConversationType.dm(makeContact())
        #expect(sut.isPublicStyleChannel == false)
    }

    @Test("Public channel (index 0) isPublicStyleChannel is true")
    func publicChannelIndex0() {
        let channel = makeChannel(index: 0, name: "Public")
        let sut = ChatConversationType.channel(channel)
        #expect(sut.isPublicStyleChannel == true)
    }

    @Test("Hash-prefixed channel isPublicStyleChannel is true")
    func hashPrefixedChannel() {
        let channel = makeChannel(index: 5, name: "#general")
        let sut = ChatConversationType.channel(channel)
        #expect(sut.isPublicStyleChannel == true)
    }

    @Test("Private channel isPublicStyleChannel is false")
    func privateChannel() {
        let channel = makeChannel(index: 3, name: "Secret")
        let sut = ChatConversationType.channel(channel)
        #expect(sut.isPublicStyleChannel == false)
    }

    // MARK: - replacingContact(_:)

    @Test("replacingContact returns DM with updated contact")
    func replacingContactUpdatesDM() {
        let original = makeContact(name: "Alice")
        let sut = ChatConversationType.dm(original)
        let updated = makeContact(name: "Bob")
        let result = sut.replacingContact(updated)

        #expect(result.navigationTitle == "Bob")
    }

    @Test("replacingContact returns self for channel")
    func replacingContactNoOpForChannel() {
        let channel = makeChannel(name: "General")
        let sut = ChatConversationType.channel(channel)
        let contact = makeContact(name: "Alice")
        let result = sut.replacingContact(contact)

        #expect(result.navigationTitle == "General")
    }

}
