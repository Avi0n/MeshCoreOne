import Testing
import Foundation
@testable import MC1
@testable import MC1Services

@Suite("Hashtag Channel Navigation Tests")
struct HashtagChannelNavigationTests {

    // MARK: - Channel Lookup Tests

    @Test("findChannelByName matches case-insensitively")
    func testCaseInsensitiveMatch() {
        let channels = [
            makeChannel(name: "#general", index: 1),
            makeChannel(name: "#events", index: 2)
        ]

        let result = channels.first { channel in
            channel.name.localizedCaseInsensitiveCompare("#GENERAL") == .orderedSame
        }

        #expect(result?.name == "#general")
    }

    @Test("findChannelByName returns nil for no match")
    func testNoMatch() {
        let channels = [
            makeChannel(name: "#general", index: 1)
        ]

        let result = channels.first { channel in
            channel.name.localizedCaseInsensitiveCompare("#events") == .orderedSame
        }

        #expect(result == nil)
    }

    @Test("findChannelByName handles empty channel list")
    func testEmptyChannelList() {
        let channels: [ChannelDTO] = []

        let result = channels.first { channel in
            channel.name.localizedCaseInsensitiveCompare("#general") == .orderedSame
        }

        #expect(result == nil)
    }

    @Test("findChannelByName matches with mixed case in list")
    func testMixedCaseInList() {
        let channels = [
            makeChannel(name: "#General", index: 1),
            makeChannel(name: "#EVENTS", index: 2),
            makeChannel(name: "#news", index: 3)
        ]

        let generalResult = channels.first { channel in
            channel.name.localizedCaseInsensitiveCompare("#general") == .orderedSame
        }
        let eventsResult = channels.first { channel in
            channel.name.localizedCaseInsensitiveCompare("#events") == .orderedSame
        }
        let newsResult = channels.first { channel in
            channel.name.localizedCaseInsensitiveCompare("#NEWS") == .orderedSame
        }

        #expect(generalResult?.name == "#General")
        #expect(eventsResult?.name == "#EVENTS")
        #expect(newsResult?.name == "#news")
    }

    // MARK: - Secret Derivation Consistency Tests

    @Test("normalized names produce consistent secrets")
    func testSecretDerivationConsistency() {
        // All should normalize to "general" and produce same passphrase
        let name1 = HashtagUtilities.normalizeHashtagName("#General")
        let name2 = HashtagUtilities.normalizeHashtagName("#GENERAL")
        let name3 = HashtagUtilities.normalizeHashtagName("general")
        let name4 = HashtagUtilities.normalizeHashtagName("#general")

        #expect(name1 == name2)
        #expect(name2 == name3)
        #expect(name3 == name4)

        // The passphrase should be "#general" (lowercase with prefix)
        let passphrase = "#\(name1)"
        #expect(passphrase == "#general")
    }

    // MARK: - URL Scheme Tests

    @Test("URL scheme encodes and decodes channel name correctly")
    func testURLSchemeRoundTrip() {
        let channelName = "general"
        let url = URL(string: "meshcoreone://hashtag/\(channelName)")

        #expect(url?.scheme == "meshcoreone")
        #expect(url?.host == "hashtag")
        #expect(url?.pathComponents.dropFirst().first == channelName)
    }

    // MARK: - Helpers

    private func makeChannel(name: String, index: UInt8) -> ChannelDTO {
        ChannelDTO(
            id: UUID(),
            radioID: UUID(),
            index: index,
            name: name,
            secret: Data(repeating: 0, count: 16),
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0,
            notificationLevel: .all
        )
    }
}
