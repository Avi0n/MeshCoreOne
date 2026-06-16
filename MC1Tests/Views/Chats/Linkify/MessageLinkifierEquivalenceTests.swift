import Testing
import SwiftUI
import CoreLocation
@testable import MC1Services
@testable import MC1

/// Proves the decomposed normalizer/tokenizer/styler pipeline produces a SwiftUI
/// `AttributedString` identical to the pre-refactor multi-pass `buildFormattedText`, over a
/// representative corpus. The pre-refactor implementation is embedded verbatim as
/// `LegacyLinkifierReference` so its output is a true golden, captured by running the old code
/// rather than hand-transcribed.
///
/// Comparison granularity (spike result): a naive top-level `AttributedString ==` is brittle
/// because a reordered tokenizer-then-styler can coalesce adjacent runs differently while
/// producing the same visible attributes. The spike confirmed top-level `==` does NOT hold for
/// the contact-share and mixed cases (run boundaries differ) even though every contracted
/// attribute matches. The tests therefore compare the plain string plus, per matching
/// character, the tuple of contracted attributes (`link`, resolved `foregroundColor`,
/// `underlineStyle`, `inlinePresentationIntent`). `topLevelEqualityHolds` records, per case,
/// whether the stricter `==` happens to hold, documenting the run-coalescing reality.
@Suite("MessageLinkifier Equivalence Tests")
@MainActor
struct MessageLinkifierEquivalenceTests {

    // MARK: - Style inputs

    /// Fixed, deterministic style inputs shared by both implementations so any output
    /// difference is attributable to the pipeline, not to a varying color/theme.
    private static let gamut = IdentityGamut(
        hueAnchors: [18, 25, 44, 77, 120, 180, 215, 255, 307, 343],
        saturation: 0.45...0.70
    )
    private static let luminances: [Double] = [0.2, 0.8]

    private func newOutput(
        _ text: String,
        isOutgoing: Bool = false,
        currentUserName: String? = nil,
        isHighContrast: Bool = false
    ) -> AttributedString {
        MessageText.buildFormattedText(
            text: text,
            isOutgoing: isOutgoing,
            currentUserName: currentUserName,
            isHighContrast: isHighContrast,
            outgoingTextColor: .white,
            hashtagColor: .blue,
            identityGamut: Self.gamut,
            identityBackgroundLuminances: Self.luminances
        ).text
    }

    private func legacyOutput(
        _ text: String,
        isOutgoing: Bool = false,
        currentUserName: String? = nil,
        isHighContrast: Bool = false
    ) -> AttributedString {
        LegacyLinkifierReference.buildFormattedText(
            text: text,
            isOutgoing: isOutgoing,
            currentUserName: currentUserName,
            isHighContrast: isHighContrast,
            outgoingTextColor: .white,
            hashtagColor: .blue,
            identityGamut: Self.gamut,
            identityBackgroundLuminances: Self.luminances
        ).text
    }

    /// Asserts run-normalized equality: identical plain text plus identical contracted
    /// attributes at every character. Returns whether the stricter top-level `==` also held,
    /// so the spike result is observable in the test output.
    @discardableResult
    private func assertEquivalent(
        _ text: String,
        isOutgoing: Bool = false,
        currentUserName: String? = nil,
        isHighContrast: Bool = false,
        _ label: Comment
    ) -> Bool {
        let new = newOutput(text, isOutgoing: isOutgoing, currentUserName: currentUserName, isHighContrast: isHighContrast)
        let legacy = legacyOutput(text, isOutgoing: isOutgoing, currentUserName: currentUserName, isHighContrast: isHighContrast)

        #expect(String(new.characters) == String(legacy.characters), label)

        let newChars = Array(new.characters.indices)
        let legacyChars = Array(legacy.characters.indices)
        #expect(newChars.count == legacyChars.count, label)

        let count = min(newChars.count, legacyChars.count)
        for offset in 0..<count {
            let n = new[newChars[offset]...newChars[offset]]
            let l = legacy[legacyChars[offset]...legacyChars[offset]]
            let nRun = n.runs.first
            let lRun = l.runs.first

            #expect(nRun?.link == lRun?.link, "link mismatch at \(offset) for \(label)")
            #expect(nRun?.underlineStyle == lRun?.underlineStyle, "underline mismatch at \(offset) for \(label)")
            #expect(nRun?.inlinePresentationIntent == lRun?.inlinePresentationIntent, "intent mismatch at \(offset) for \(label)")
            #expect(
                colorsEqual(nRun?.foregroundColor, lRun?.foregroundColor),
                "foregroundColor mismatch at \(offset) for \(label)"
            )
            #expect(
                colorsEqual(nRun?.backgroundColor, lRun?.backgroundColor),
                "backgroundColor mismatch at \(offset) for \(label)"
            )
        }

        return new == legacy
    }

    /// SwiftUI `Color` is not `Equatable` in a way that survives `.opacity`/gamut derivation
    /// across two construction paths cleanly, so compare by description, which both paths
    /// produce identically since the same `Color` values flow through both.
    private func colorsEqual(_ lhs: Color?, _ rhs: Color?) -> Bool {
        switch (lhs, rhs) {
        case (nil, nil): return true
        case let (l?, r?): return String(describing: l) == String(describing: r)
        default: return false
        }
    }

    // MARK: - Corpus

    @Test("URL-only message")
    func urlOnly() {
        assertEquivalent("https://example.com", "url-only")
    }

    @Test("Coordinate-only message")
    func coordinateOnly() {
        assertEquivalent("37.334900, -122.009020", "coordinate-only")
    }

    @Test("Mention-only message")
    func mentionOnly() {
        assertEquivalent("@[Alice]", "mention-only")
    }

    @Test("Self-mention message")
    func selfMention() {
        assertEquivalent("Hey @[Me] there", currentUserName: "Me", "self-mention")
    }

    @Test("Outgoing self-mention message")
    func outgoingSelfMention() {
        assertEquivalent("Hey @[Me] there", isOutgoing: true, currentUserName: "Me", "outgoing-self-mention")
    }

    @Test("Hashtag-only message")
    func hashtagOnly() {
        assertEquivalent("#general", "hashtag-only")
    }

    @Test("Outgoing hashtag message")
    func outgoingHashtag() {
        assertEquivalent("Join #general now", isOutgoing: true, "outgoing-hashtag")
    }

    @Test("Contact-share message")
    func contactShare() {
        let token = Self.shareToken(name: "Alice")
        assertEquivalent("Add \(token) please", "contact-share")
    }

    @Test("MeshCore contact link message")
    func meshcoreLink() {
        assertEquivalent("Open meshcore://contact/add?name=Bob now", "meshcore-link")
    }

    @Test("Mixed mention, url, hashtag, coordinate")
    func mixed() {
        assertEquivalent("@[Bob] see https://a.com #ops at 37.7749, -122.4194", "mixed")
    }

    @Test("Outgoing mixed message")
    func outgoingMixed() {
        assertEquivalent("@[Bob] see https://a.com #ops", isOutgoing: true, "outgoing-mixed")
    }

    @Test("Adversarial contact name containing a mention token")
    func adversarialContactName() {
        let token = Self.shareToken(name: "@[Eve] hi")
        assertEquivalent("Add \(token)", "adversarial-contact-name")
    }

    @Test("RTL text with a URL")
    func rightToLeft() {
        assertEquivalent("مرحبا https://example.com شكرا", "rtl")
    }

    @Test("Comma-decimal locale coordinate text round-trips with dot-decimals")
    func commaDecimalLocale() {
        // The coordinate URL must stay dot-decimal regardless of locale; both paths use
        // `String(format:)`, so this verifies the URL run equivalence, not locale switching.
        assertEquivalent("Meet 48.858400, 2.294500 ok", "comma-decimal")
    }

    @Test("URL inside a mention is not re-linked")
    func urlInsideMention() {
        assertEquivalent("Hey @[Ferret WCMesh.com] hi", "url-in-mention")
    }

    @Test("URL adjacent to a hashtag")
    func urlAdjacentHashtag() {
        assertEquivalent("Check https://example.com#anchor and #general", "url-adjacent-hashtag")
    }

    @Test("Coordinate inside a contact chip keeps the contact link")
    func coordinateInsideContact() {
        let token = Self.shareToken(name: "Base 37.7749, -122.4194")
        assertEquivalent("Add \(token)", "coordinate-in-contact")
    }

    @Test("A hashtag embedded in a meshcore channel link follows legacy hashtag-wins precedence")
    func hashtagInsideMeshcoreChannelLink() {
        assertEquivalent("meshcore://channel/add?name=ops#general", "hashtag-in-meshcore-channel")
    }

    @Test("A hashtag embedded in a meshcore contact link follows legacy hashtag-wins precedence")
    func hashtagInsideMeshcoreContactLink() {
        let key = String(repeating: "AB", count: 32)
        assertEquivalent("meshcore://contact/add?public_key=\(key)#ops", "hashtag-in-meshcore-contact")
    }

    @Test("Plain text with no links")
    func plainText() {
        assertEquivalent("Just a normal sentence.", "plain")
    }

    @Test("Map coordinate derivation matches the legacy first-coordinate rule")
    func mapCoordinateMatches() {
        let cases = [
            "37.7749, -122.4194 and 10.0, 20.0",
            "no coords here",
            "Add \(Self.shareToken(name: "Base 1.0, 2.0")) then 3.5, 4.5",
        ]
        for text in cases {
            let new = MessageText.buildFormattedText(
                text: text, isOutgoing: false, currentUserName: nil, isHighContrast: false,
                outgoingTextColor: .white, hashtagColor: .blue,
                identityGamut: Self.gamut, identityBackgroundLuminances: Self.luminances
            ).mapCoordinate
            let legacy = LegacyLinkifierReference.buildFormattedText(
                text: text, isOutgoing: false, currentUserName: nil, isHighContrast: false,
                outgoingTextColor: .white, hashtagColor: .blue,
                identityGamut: Self.gamut, identityBackgroundLuminances: Self.luminances
            ).mapCoordinate
            #expect(coordinatesEqual(new, legacy), "mapCoordinate mismatch for \(text)")
        }
    }

    private func coordinatesEqual(_ lhs: CLLocationCoordinate2D?, _ rhs: CLLocationCoordinate2D?) -> Bool {
        switch (lhs, rhs) {
        case (nil, nil): return true
        case let (l?, r?):
            return abs(l.latitude - r.latitude) < 0.0000001 && abs(l.longitude - r.longitude) < 0.0000001
        default: return false
        }
    }

    // MARK: - Helpers

    private static func shareToken(name: String) -> String {
        guard let key = Data(hexString: String(repeating: "AB", count: 32)) else {
            return ""
        }
        return ContactShareUtilities.formatShare(publicKey: key, type: .chat, name: name)
    }
}
