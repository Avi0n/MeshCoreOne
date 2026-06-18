import Testing
import SwiftUI
import Foundation
@testable import MC1
@testable import MC1Services

/// Proves the VoiceOver link-action list the passive body renderer relies on: every link kind in a
/// message body becomes a distinctly-named, openable action, deduplicated and capped, with the
/// preview card's URL surfaced first. The list is pure logic over the precomputed `AttributedString`,
/// so it is tested off-screen like `FragmentLayoutTests`.
@Suite("MessageLinkAccessibility actions")
@MainActor
struct MessageLinkAccessibilityTests {

    // MARK: - Builders

    /// Builds an `AttributedString` from `(text, optionalLinkURL)` segments, linking each segment
    /// directly so the test controls link placement without relying on substring search.
    private static func attributed(_ segments: [(text: String, url: String?)]) -> AttributedString {
        var result = AttributedString("")
        for segment in segments {
            var piece = AttributedString(segment.text)
            if let urlString = segment.url, let url = URL(string: urlString) {
                piece.link = url
            }
            result += piece
        }
        return result
    }

    private static let gamut = IdentityGamut(
        hueAnchors: [18, 25, 44, 77, 120, 180, 215, 255, 307, 343],
        saturation: 0.45...0.70
    )

    private static func formatted(_ text: String) -> AttributedString {
        MessageText.buildFormattedText(
            text: text,
            isOutgoing: false,
            currentUserName: nil,
            isHighContrast: false,
            outgoingTextColor: .white,
            hashtagColor: .blue,
            identityGamut: gamut,
            identityBackgroundLuminances: [0.2, 0.8]
        ).text
    }

    // MARK: - Per-kind naming (through the real linkifier)

    @Test("Each body-text link kind yields a distinctly-named action in document order")
    func perKindNaming() {
        let actions = MessageLinkAccessibility.actions(
            previewURL: nil,
            formatted: Self.formatted("@[Bob] see https://example.com #ops at 37.7749, -122.4194")
        )

        #expect(actions.count == 4)

        #expect(actions[0].url.absoluteString == "meshcoreone://mention/Bob")
        #expect(actions[0].name == "Mention: Bob")

        #expect(actions[1].url.scheme == "https")
        #expect(actions[1].name == "Open Link: example.com")

        #expect(actions[2].url.absoluteString == "meshcoreone://hashtag/ops")
        #expect(actions[2].name == "Open #ops")

        #expect(actions[3].url.host() == "map")
        #expect(actions[3].name == "Open Map")
    }

    @Test("A shared contact link is named from its parsed contact name")
    func contactShareNaming() {
        let key = String(repeating: "AB", count: 32)
        let uri = "meshcore://contact/add?name=Alice&public_key=\(key)&type=1"
        let actions = MessageLinkAccessibility.actions(
            previewURL: nil,
            formatted: Self.attributed([("Alice", uri)])
        )
        #expect(actions.count == 1)
        #expect(actions[0].name == "Add Contact: Alice")
    }

    @Test("A shared channel link is named from its parsed channel name")
    func channelNaming() {
        let secret = String(repeating: "CD", count: 16)
        let uri = "meshcore://channel/add?name=Ops&secret=\(secret)"
        let actions = MessageLinkAccessibility.actions(
            previewURL: nil,
            formatted: Self.attributed([("Ops", uri)])
        )
        #expect(actions.count == 1)
        #expect(actions[0].name == "Open Channel: Ops")
    }

    // MARK: - Ordering, dedup, cap

    @Test("The preview URL is surfaced before body-text links")
    func previewURLFirst() {
        let preview = URL(string: "https://preview.example")!
        let actions = MessageLinkAccessibility.actions(
            previewURL: preview,
            formatted: Self.attributed([("go ", nil), ("body", "https://body.example")])
        )
        #expect(actions.count == 2)
        #expect(actions[0].url == preview)
        #expect(actions[1].url.absoluteString == "https://body.example")
    }

    @Test("Duplicate URLs collapse to a single action")
    func deduplicatesByURL() {
        let shared = "https://dup.example"
        let actions = MessageLinkAccessibility.actions(
            previewURL: URL(string: shared),
            formatted: Self.attributed([("a", shared), (" ", nil), ("b", shared)])
        )
        #expect(actions.count == 1)
        #expect(actions[0].url.absoluteString == shared)
    }

    @Test("The action count is capped")
    func capsActionCount() {
        let segments = (0..<(MessageLinkAccessibility.maxActions + 4)).map {
            (text: "link\($0) ", url: "https://e\($0).example" as String?)
        }
        let actions = MessageLinkAccessibility.actions(
            previewURL: nil,
            formatted: Self.attributed(segments)
        )
        #expect(actions.count == MessageLinkAccessibility.maxActions)
    }

    @Test("No links yields no actions")
    func noLinks() {
        #expect(MessageLinkAccessibility.actions(previewURL: nil, formatted: Self.attributed([("plain", nil)])).isEmpty)
        #expect(MessageLinkAccessibility.actions(previewURL: nil, formatted: nil).isEmpty)
    }
}
