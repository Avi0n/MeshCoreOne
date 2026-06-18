import Testing
import SwiftUI
import CoreLocation
@testable import MC1Services
@testable import MC1

@Suite("MessageText Tests")
@MainActor
struct MessageTextTests {

    // MARK: - URL in Mention Tests

    @Test("URL-like text in mention should carry the mention link, not a parsed URL")
    func urlInMentionShouldNotBeParsedAsLink() {
        let text = "Hey @[Ferret PocketMesh WCMesh.com], check this out!"
        let messageText = MessageText(text)
        let formatted = messageText.testableFormattedText

        let content = String(formatted.characters)
        guard let wcMeshRange = content.range(of: "WCMesh.com"),
              let attrRange = Range(wcMeshRange, in: formatted) else {
            Issue.record("Could not find 'WCMesh.com' in formatted text")
            return
        }

        let link = formatted[attrRange].link
        #expect(link?.scheme == "meshcoreone")
        #expect(link?.host == "mention")
        #expect(link?.scheme != "http")
        #expect(link?.scheme != "https")

        let underline = formatted[attrRange].underlineStyle
        #expect(underline == .single, "WCMesh.com should have mention styling (underline)")
    }

    @Test("URL-like text in mention with IP address should carry the mention link, not a parsed URL")
    func ipAddressInMentionShouldNotBeParsedAsLink() {
        let text = "Message from @[Node 192.168.1.100]"
        let messageText = MessageText(text)
        let formatted = messageText.testableFormattedText

        let content = String(formatted.characters)
        guard let ipRange = content.range(of: "192.168.1.100"),
              let attrRange = Range(ipRange, in: formatted) else {
            Issue.record("Could not find IP address in formatted text")
            return
        }

        let link = formatted[attrRange].link
        #expect(link?.scheme == "meshcoreone")
        #expect(link?.host == "mention")
        #expect(link?.scheme != "http")
        #expect(link?.scheme != "https")
    }

    @Test("Regular URL outside mention should still be parsed as link")
    func regularUrlShouldStillBeParsedAsLink() {
        let text = "Check https://example.com for details"
        let messageText = MessageText(text)
        let formatted = messageText.testableFormattedText

        let content = String(formatted.characters)
        guard let urlRange = content.range(of: "https://example.com") else {
            Issue.record("Could not find URL in formatted text")
            return
        }

        guard let attrRange = Range(urlRange, in: formatted) else {
            Issue.record("Could not convert range to AttributedString range")
            return
        }

        // URL should have link attribute
        let linkValue = formatted[attrRange].link
        #expect(linkValue != nil, "Regular URL should be parsed as a link")
        #expect(linkValue?.absoluteString == "https://example.com", "Link URL should match")
    }

    @Test("Message with both mention containing URL-like text and real URL")
    func mentionWithUrlLikeTextAndRealUrl() {
        let text = "@[Server node.example.com] says check https://docs.example.com"
        let messageText = MessageText(text)
        let formatted = messageText.testableFormattedText

        let content = String(formatted.characters)

        if let nodeRange = content.range(of: "node.example.com"),
           let attrRange = Range(nodeRange, in: formatted) {
            let link = formatted[attrRange].link
            #expect(link?.scheme == "meshcoreone", "node.example.com inside the mention carries the mention link")
            #expect(link?.host == "mention")
            #expect(link?.scheme != "https", "node.example.com inside the mention is not parsed as an https URL")
        } else {
            Issue.record("Could not find node.example.com in formatted text")
        }

        if let docsRange = content.range(of: "https://docs.example.com"),
           let attrRange = Range(docsRange, in: formatted) {
            let link = formatted[attrRange].link
            #expect(link?.scheme == "https", "Real URL outside the mention is parsed as an https link")
        } else {
            Issue.record("Could not find https://docs.example.com in formatted text")
        }
    }

    @Test("A simple mention carries a meshcoreone://mention/<percent-encoded-name> link")
    func mentionRangeCarriesMentionLink() {
        let text = "Hey @[Alice Smith], how are you?"
        let formatted = MessageText(text).testableFormattedText

        let content = String(formatted.characters)
        guard let mentionRange = content.range(of: "@Alice Smith"),
              let attrRange = Range(mentionRange, in: formatted) else {
            Issue.record("Could not locate mention substring")
            return
        }

        let link = formatted[attrRange].link
        #expect(link?.scheme == "meshcoreone")
        #expect(link?.host == "mention")
        let decoded = link.flatMap(MentionDeeplinkSupport.name(from:))
        #expect(decoded == "Alice Smith")
    }

    // MARK: - Coordinate Detection

    /// Reads the `.link` attribute on the first occurrence of `substring`.
    private func link(for substring: String, in attributed: AttributedString) -> URL? {
        let content = String(attributed.characters)
        guard let range = content.range(of: substring),
              let attrRange = Range(range, in: attributed) else { return nil }
        return attributed[attrRange].link
    }

    @Test("A decimal coordinate pair is linkified as a meshcore map link")
    func coordinatePairLinkified() {
        let formatted = MessageText("Meet at 37.334900, -122.009020 tonight").testableFormattedText
        let url = link(for: "37.334900, -122.009020", in: formatted)
        #expect(url?.scheme == "meshcore")
        #expect(url?.host() == "map")
    }

    @Test("An integer pair is not linkified")
    func integerPairNotLinkified() {
        let formatted = MessageText("ratio is 3, 4 today").testableFormattedText
        #expect(link(for: "3, 4", in: formatted) == nil)
    }

    @Test("An out-of-range pair is not linkified")
    func outOfRangePairNotLinkified() {
        let formatted = MessageText("bad 200.0, 400.0 coord").testableFormattedText
        #expect(link(for: "200.0, 400.0", in: formatted) == nil)
    }

    @Test("A coordinate embedded in text linkifies only the coordinate substring")
    func embeddedCoordinateLinkifiesSubstringOnly() {
        let formatted = MessageText("here: 37.7749, -122.4194 ok").testableFormattedText
        #expect(link(for: "37.7749, -122.4194", in: formatted)?.host() == "map")
        #expect(link(for: "here", in: formatted) == nil)
        #expect(link(for: "ok", in: formatted) == nil)
    }

    @Test("Multiple coordinates in one message each linkify")
    func multipleCoordinatesEachLinkify() {
        let formatted = MessageText("A 10.0, 20.0 and B 30.0, 40.0").testableFormattedText
        #expect(link(for: "10.0, 20.0", in: formatted)?.host() == "map")
        #expect(link(for: "30.0, 40.0", in: formatted)?.host() == "map")
    }

    @Test("A three-number decimal list is treated as a list, not a coordinate")
    func decimalListNotLinkified() {
        let formatted = MessageText("values 1.0, 2.0, 3.0 here").testableFormattedText
        #expect(link(for: "1.0, 2.0", in: formatted) == nil)
    }

    @Test("A version-like string is not linkified")
    func versionLikeNotLinkified() {
        let formatted = MessageText("v1.2, 3.4 release").testableFormattedText
        #expect(link(for: "1.2, 3.4", in: formatted) == nil)
    }

    @Test("A coordinate inside an existing link range keeps the original link")
    func coordinateInsideExistingLinkSkipped() {
        guard let key = Data(hexString: String(repeating: "AB", count: 32)) else {
            Issue.record("public key hex must decode")
            return
        }
        let token = ContactShareUtilities.formatShare(publicKey: key, type: .chat, name: "Base 37.7749, -122.4194")
        let formatted = MessageText("Add \(token)").testableFormattedText
        // The coordinate substring sits inside the contact chip's link, so it must keep
        // the contact host, not be re-linked as a map link.
        #expect(link(for: "37.7749, -122.4194", in: formatted)?.host() == "contact")
    }

    @Test("A linkified coordinate round-trips through parseMapURL with dot-decimal values")
    func coordinateLinkRoundTrips() throws {
        let formatted = MessageText("37.334900, -122.009020").testableFormattedText
        let url = try #require(link(for: "37.334900, -122.009020", in: formatted))
        let coordinate = try #require(MeshCoreURLParser.parseMapURL(url.absoluteString))
        #expect(abs(coordinate.latitude - 37.3349) < 0.000001)
        #expect(abs(coordinate.longitude - (-122.00902)) < 0.000001)
        // W2: link values are locale-independent dot-decimals, never comma-decimals.
        #expect(url.absoluteString.contains("lat=37.334900"))
        #expect(url.absoluteString.contains("lon=-122.009020"))
    }

    @Test("A coordinate ending a sentence is linkified, with the period left as plain text")
    func coordinateWithTrailingPeriodLinkified() {
        let formatted = MessageText("Meet at 37.7749, -122.4194.").testableFormattedText
        #expect(link(for: "37.7749, -122.4194", in: formatted)?.host() == "map")
    }

    @Test("A coordinate followed by other trailing punctuation is linkified")
    func coordinateWithTrailingPunctuationLinkified() {
        let formatted = MessageText("Here: 37.7749, -122.4194!").testableFormattedText
        #expect(link(for: "37.7749, -122.4194", in: formatted)?.host() == "map")
    }
}
