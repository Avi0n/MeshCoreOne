import Testing
import SwiftUI
@testable import MC1Services
@testable import MC1

@Suite("MessageText Contact Share Tests")
@MainActor
struct MessageTextContactShareTests {

    /// Fixed valid 32-byte public key rendered as 64 hex characters.
    private static let validPublicKeyHex = String(repeating: "AB", count: 32)

    private static let contactName = "Field Base"

    private func publicKey() -> Data {
        guard let key = Data(hexString: Self.validPublicKeyHex) else {
            Issue.record("Fixed public key hex must decode to Data")
            return Data()
        }
        return key
    }

    private func expectedContactURL(name: String) -> URL? {
        URL(string: ContactService.exportContactURI(
            name: name,
            publicKey: publicKey(),
            type: .chat
        ))
    }

    // MARK: - Valid token

    @Test("Valid contact share token renders the contact name as a tappable add-contact link")
    func validTokenRendersNameAsContactLink() {
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: Self.contactName
        )
        let formatted = MessageText("Add \(token) now").testableFormattedText

        let content = String(formatted.characters)
        #expect(content.contains(Self.contactName), "Visible text should contain the contact name")
        #expect(!content.contains(token), "Raw share token should be replaced, not visible")

        let expectedURL = expectedContactURL(name: Self.contactName)
        var linkedText: String?
        for run in formatted.runs where run.link == expectedURL {
            linkedText = String(formatted[run.range].characters)
        }

        #expect(linkedText == Self.contactName, "Linked run text should equal the contact name")
    }

    // MARK: - Malformed tokens are no-ops

    @Test("Out-of-range contact type leaves the literal token untouched with no contact link")
    func malformedTypeIsNoOp() {
        // Matches the share-token regex (`\d+`) but the type 300 exceeds a UInt8, so parseShare rejects it.
        let literal = "<\(Self.validPublicKeyHex):300:x>"
        let formatted = MessageText(literal).testableFormattedText

        let content = String(formatted.characters)
        #expect(content.contains(literal), "Malformed token text should remain unchanged")

        let hasContactLink = formatted.runs.contains { $0.link?.host == "contact" }
        #expect(!hasContactLink, "Malformed token must not produce a contact link")
    }

    @Test("Stray opening bracket is a no-op and does not crash")
    func strayBracketIsNoOp() {
        let formatted = MessageText("a < b").testableFormattedText

        let content = String(formatted.characters)
        #expect(content == "a < b", "Non-token text should be unchanged")

        let hasContactLink = formatted.runs.contains { $0.link?.host == "contact" }
        #expect(!hasContactLink, "Stray bracket must not produce a contact link")
    }

    // MARK: - Pass ordering regression

    @Test("Token adjacent to a URL styles both the contact link and the URL link")
    func tokenAdjacentToURLStylesBoth() {
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: Self.contactName
        )
        let formatted = MessageText("\(token) https://example.com").testableFormattedText

        let expectedURL = expectedContactURL(name: Self.contactName)
        let hasContactLink = formatted.runs.contains { $0.link == expectedURL }
        #expect(hasContactLink, "Replacing pass must produce the contact deep link")

        let hasWebLink = formatted.runs.contains { $0.link?.absoluteString == "https://example.com" }
        #expect(hasWebLink, "URL pass must still style the adjacent web URL after token replacement")
    }

    @Test("Hashtag after a token is still linked, proving the replacing pass runs before the snapshot")
    func hashtagAfterTokenStaysLinked() {
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: Self.contactName
        )
        // The hashtag pass styles ranges from the snapshot captured inside the URL pass. If the
        // replacing pass ran after that snapshot, the shorter replacement would shift the hashtag
        // range and this link would map to stale offsets, dropping or mis-styling it.
        let formatted = MessageText("\(token) #general").testableFormattedText

        let expectedURL = expectedContactURL(name: Self.contactName)
        let hasContactLink = formatted.runs.contains { $0.link == expectedURL }
        #expect(hasContactLink, "Replacing pass must produce the contact deep link")

        let hashtagURL = URL(string: "meshcoreone://hashtag/general")
        let hasHashtagLink = formatted.runs.contains { $0.link == hashtagURL }
        #expect(hasHashtagLink, "Hashtag after the token must remain correctly linked")
    }

    // MARK: - Bidi sanitization reaches the link URL

    @Test("Bidi control in the name is stripped from both the chip and the add-contact link URL")
    func bidiControlStrippedFromChipAndURL() {
        let strippedName = "evil"
        let rawName = "\u{202E}\(strippedName)"
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: rawName
        )
        let formatted = MessageText("Add \(token)").testableFormattedText

        let expectedURL = expectedContactURL(name: strippedName)
        var linkedText: String?
        for run in formatted.runs where run.link == expectedURL {
            linkedText = String(formatted[run.range].characters)
        }

        #expect(linkedText == strippedName, "Chip text should be the bidi-stripped name")

        let hasBidiControl = String(formatted.characters).unicodeScalars.contains { $0.properties.isBidiControl }
        #expect(!hasBidiControl, "Visible text must contain no bidi control scalars")

        // The link URL must carry the stripped name, not the raw override, so the confirmation
        // sheet and the persisted contact are bidi-clean for inbound tokens.
        let rawNameURL = expectedContactURL(name: rawName)
        let hasRawNameLink = formatted.runs.contains { $0.link == rawNameURL }
        #expect(!hasRawNameLink, "Link URL must not carry the raw bidi-override name")
    }

    @Test("Zero-width, format, and control scalars are stripped from the chip name and link URL")
    func invisibleScalarsStrippedFromChipAndURL() {
        let visible = "Base"
        // ZWSP, ZWNJ, ZWJ, newline, and tab interleaved; all must be removed.
        let rawName = "B\u{200B}a\u{200C}s\u{200D}e\u{000A}\u{0009}"
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: rawName
        )
        let formatted = MessageText("Add \(token)").testableFormattedText

        let expectedURL = expectedContactURL(name: visible)
        var linkedText: String?
        for run in formatted.runs where run.link == expectedURL {
            linkedText = String(formatted[run.range].characters)
        }
        #expect(linkedText == visible, "Chip text should be the sanitized name with invisibles removed")

        let hasInvisible = String(formatted.characters).unicodeScalars.contains {
            $0.properties.isDefaultIgnorableCodePoint
                || $0.properties.generalCategory == .control
                || $0.properties.generalCategory == .format
        }
        #expect(!hasInvisible, "Visible text must contain no zero-width, format, or control scalars")
    }

    @Test("A contact token whose name sanitizes to empty leaves the literal token untouched")
    func emptySanitizedNameLeavesLiteralToken() {
        let rawName = "\u{202E}"  // a lone right-to-left override sanitizes to an empty string
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: rawName
        )
        let formatted = MessageText(token).testableFormattedText

        // With an empty sanitized name the pass must skip the token rather than delete it
        // (an empty replacement would silently drop the token from the message).
        let content = String(formatted.characters)
        #expect(content == token, "An empty sanitized name must leave the literal token in place")

        let hasContactLink = formatted.runs.contains { $0.link?.host() == "contact" }
        #expect(!hasContactLink, "An empty sanitized name must not produce a contact link")
    }

    // MARK: - Adversarial names inside a token must not be re-processed by later passes

    @Test("A mention pattern inside a token name is not rewritten by the mention pass")
    func mentionInsideTokenNamePreserved() {
        let name = "Ops @[Bob] base"
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: name
        )
        let formatted = MessageText(token).testableFormattedText

        let expectedURL = expectedContactURL(name: name)
        var linkedText: String?
        for run in formatted.runs where run.link == expectedURL {
            linkedText = String(formatted[run.range].characters)
        }
        #expect(linkedText == name, "Chip must preserve the literal @[Bob], not collapse it to @Bob")
    }

    @Test("A hashtag inside a token name keeps the chip link instead of becoming a hashtag link")
    func hashtagInsideTokenNameNotRelinked() {
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: "Base #general"
        )
        let formatted = MessageText(token).testableFormattedText

        let hashtagURL = URL(string: "meshcoreone://hashtag/general")
        let hasHashtagLink = formatted.runs.contains { $0.link == hashtagURL }
        #expect(!hasHashtagLink, "A hashtag inside a contact chip must not be re-linked")
    }

    @Test("A meshcore URL inside a token name is not re-linked by the meshcore pass")
    func meshcoreURLInsideTokenNameNotRelinked() {
        let token = ContactShareUtilities.formatShare(
            publicKey: publicKey(),
            type: .chat,
            name: "join meshcore://channel/add?name=x&secret=00112233445566778899AABBCCDDEEFF"
        )
        let formatted = MessageText(token).testableFormattedText

        let hasChannelLink = formatted.runs.contains { $0.link?.host() == "channel" }
        #expect(!hasChannelLink, "A meshcore channel URL inside a contact chip must not be re-linked")
    }
}
