import Testing
import SwiftUI
import UIKit
@testable import MC1Services
@testable import MC1

/// Proves the render-time UIKit string `MessageBodyTextView` derives from the single authored
/// SwiftUI `AttributedString` carries the attributes the renderer is responsible for overlaying or
/// resolving, in both a light and a dark/high-contrast trait collection. The SwiftUI-side
/// equivalence guard does not cover this; the renderer's user-visible correctness rests on this test
/// plus the manual matrix.
///
/// Driven from the same corpus the equivalence test uses, so any SwiftUI run with a `.link` that
/// fails to bridge into the UIKit string fails here.
@Suite("MessageBody Attribute Bridging Tests")
@MainActor
struct MessageBodyAttributeBridgingTests {

    // MARK: - Style inputs

    private static let gamut = IdentityGamut(
        hueAnchors: [18, 25, 44, 77, 120, 180, 215, 255, 307, 343],
        saturation: 0.45...0.70
    )
    private static let luminances: [Double] = [0.2, 0.8]

    private static let light = UITraitCollection { traits in
        traits.userInterfaceStyle = .light
        traits.accessibilityContrast = .normal
    }

    private static let darkHighContrast = UITraitCollection { traits in
        traits.userInterfaceStyle = .dark
        traits.accessibilityContrast = .high
    }

    /// The base text color slot for incoming bubbles, matching `BaseColorSlot.incoming.swiftUIColor`.
    private static let incomingBaseColor: Color = .primary

    private func swiftUIString(
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

    private func bridged(
        _ swiftUI: AttributedString,
        baseColor: Color = MessageBodyAttributeBridgingTests.incomingBaseColor,
        traitCollection: UITraitCollection
    ) -> NSAttributedString {
        MessageBodyTextView.makeAttributedText(
            from: swiftUI,
            baseColor: baseColor,
            traitCollection: traitCollection
        )
    }

    // MARK: - Shared assertions

    /// Every character carries `.font` of the body text style; runs whose SwiftUI source carried
    /// `inlinePresentationIntent == .stronglyEmphasized` carry the bold symbolic trait, and no other
    /// run does.
    private func assertFonts(_ swiftUI: AttributedString, _ uikit: NSAttributedString, _ label: Comment) {
        #expect(uikit.length == swiftUI.characters.count, label)

        let boldOffsets = boldCharacterOffsets(in: swiftUI)
        let bodyMetrics = UIFontMetrics(forTextStyle: .body)

        uikit.enumerateAttribute(.font, in: NSRange(location: 0, length: uikit.length)) { value, range, _ in
            let font = value as? UIFont
            #expect(font != nil, "missing font at \(range) for \(label)")
            guard let font else { return }

            // Same point size family as the scaled body style (proves `.body`, not a stray size).
            let expectedBody = bodyMetrics.scaledFont(for: .preferredFont(forTextStyle: .body))
            #expect(
                abs(font.pointSize - expectedBody.pointSize) < 0.01,
                "non-body font size \(font.pointSize) at \(range) for \(label)"
            )

            let isBold = font.fontDescriptor.symbolicTraits.contains(.traitBold)
            for offset in range.location..<(range.location + range.length) {
                #expect(isBold == boldOffsets.contains(offset), "bold mismatch at \(offset) for \(label)")
            }
        }
    }

    /// Every character carries a non-nil resolved `.foregroundColor`: no run is left to fall through
    /// to the system placeholder grey. The `.primary` base slot's resolution to `UIColor.label`
    /// specifically is proven by `incomingBaseResolvesToLabel`; a fully-styled message (mention- or
    /// hashtag-only) legitimately has no base-colored run, and outgoing messages use white, so this
    /// corpus-wide assertion checks only that every character is colored.
    private func assertForegroundColors(
        _ uikit: NSAttributedString,
        traitCollection: UITraitCollection,
        _ label: Comment
    ) {
        let placeholderGrey = UIColor.placeholderText.resolvedColor(with: traitCollection)
        var coveredLength = 0

        uikit.enumerateAttribute(.foregroundColor, in: NSRange(location: 0, length: uikit.length)) { value, range, _ in
            let color = value as? UIColor
            #expect(color != nil, "missing foregroundColor at \(range) for \(label)")
            #expect(color != placeholderGrey, "grey fallthrough at \(range) for \(label)")
            if color != nil {
                coveredLength += range.length
            }
        }

        #expect(coveredLength == uikit.length, "foregroundColor does not cover the whole string for \(label)")
    }

    /// The set of `.link` values in the UIKit string matches the SwiftUI string URL-for-URL.
    private func assertLinkParity(_ swiftUI: AttributedString, _ uikit: NSAttributedString, _ label: Comment) {
        let swiftLinks = Set(swiftUI.runs.compactMap { $0.link })
        var uikitLinks: Set<URL> = []
        uikit.enumerateAttribute(.link, in: NSRange(location: 0, length: uikit.length)) { value, _, _ in
            if let url = value as? URL {
                uikitLinks.insert(url)
            } else if let string = value as? String, let url = URL(string: string) {
                uikitLinks.insert(url)
            }
        }
        #expect(uikitLinks == swiftLinks, "link parity mismatch for \(label): swiftUI=\(swiftLinks) uikit=\(uikitLinks)")
    }

    /// The self-mention `.backgroundColor` carries onto exactly the characters the SwiftUI string
    /// backgrounded, resolved to a non-nil `UIColor` for the trait collection. SwiftUI's
    /// `.backgroundColor` does not survive the default bridge, so a regression that drops it leaves
    /// the highlighted offsets uncovered and fails here.
    private func assertSelfMentionBackground(
        _ swiftUI: AttributedString,
        _ uikit: NSAttributedString,
        _ label: Comment
    ) {
        let backgroundOffsets = backgroundColorOffsets(in: swiftUI)
        #expect(!backgroundOffsets.isEmpty, "expected a self-mention background run for \(label)")

        var uikitBackgrounded: Set<Int> = []
        uikit.enumerateAttribute(.backgroundColor, in: NSRange(location: 0, length: uikit.length)) { value, range, _ in
            guard let color = value as? UIColor else { return }
            #expect(color != nil, "nil background at \(range) for \(label)")
            for offset in range.location..<(range.location + range.length) {
                uikitBackgrounded.insert(offset)
            }
        }
        #expect(uikitBackgrounded == backgroundOffsets, "self-mention background mismatch for \(label)")
    }

    /// Underline carries onto exactly the characters the SwiftUI string underlined.
    private func assertUnderline(_ swiftUI: AttributedString, _ uikit: NSAttributedString, _ label: Comment) {
        let underlinedOffsets = underlinedCharacterOffsets(in: swiftUI)
        var uikitUnderlined: Set<Int> = []
        uikit.enumerateAttribute(.underlineStyle, in: NSRange(location: 0, length: uikit.length)) { value, range, _ in
            guard let raw = value as? Int, raw != 0 else { return }
            for offset in range.location..<(range.location + range.length) {
                uikitUnderlined.insert(offset)
            }
        }
        #expect(uikitUnderlined == underlinedOffsets, "underline mismatch for \(label)")
    }

    /// Runs the full assertion set in one trait collection.
    private func assertBridged(
        _ text: String,
        isOutgoing: Bool = false,
        currentUserName: String? = nil,
        traitCollection: UITraitCollection,
        _ label: Comment
    ) {
        let swiftUI = swiftUIString(text, isOutgoing: isOutgoing, currentUserName: currentUserName)
        let baseColor: Color = isOutgoing ? .white : Self.incomingBaseColor
        let uikit = bridged(swiftUI, baseColor: baseColor, traitCollection: traitCollection)

        assertFonts(swiftUI, uikit, label)
        assertForegroundColors(uikit, traitCollection: traitCollection, label)
        assertLinkParity(swiftUI, uikit, label)
        assertUnderline(swiftUI, uikit, label)
    }

    /// Runs the full assertion set in both a light and a dark/high-contrast trait collection.
    private func assertBridgedAllTraits(
        _ text: String,
        isOutgoing: Bool = false,
        currentUserName: String? = nil,
        _ label: Comment
    ) {
        assertBridged(text, isOutgoing: isOutgoing, currentUserName: currentUserName, traitCollection: Self.light, "\(label) [light]")
        assertBridged(text, isOutgoing: isOutgoing, currentUserName: currentUserName, traitCollection: Self.darkHighContrast, "\(label) [dark-hc]")
    }

    // MARK: - Corpus (mirrors MessageLinkifierEquivalenceTests)

    @Test("URL-only message bridges")
    func urlOnly() {
        assertBridgedAllTraits("https://example.com", "url-only")
    }

    @Test("Coordinate-only message bridges")
    func coordinateOnly() {
        assertBridgedAllTraits("37.334900, -122.009020", "coordinate-only")
    }

    @Test("Mention-only message bridges")
    func mentionOnly() {
        assertBridgedAllTraits("@[Alice]", "mention-only")
    }

    @Test("Hashtag-only message bridges with bold")
    func hashtagOnly() {
        assertBridgedAllTraits("#general", "hashtag-only")
    }

    @Test("Outgoing hashtag message bridges")
    func outgoingHashtag() {
        assertBridgedAllTraits("Join #general now", isOutgoing: true, "outgoing-hashtag")
    }

    @Test("Contact-share message bridges")
    func contactShare() {
        let token = Self.shareToken(name: "Alice")
        assertBridgedAllTraits("Add \(token) please", "contact-share")
    }

    @Test("MeshCore contact link message bridges")
    func meshcoreLink() {
        assertBridgedAllTraits("Open meshcore://contact/add?name=Bob now", "meshcore-link")
    }

    @Test("Mixed mention, url, hashtag, coordinate bridges")
    func mixed() {
        assertBridgedAllTraits("@[Bob] see https://a.com #ops at 37.7749, -122.4194", "mixed")
    }

    @Test("Self-mention message bridges")
    func selfMention() {
        assertBridgedAllTraits("Hey @[Me] there", currentUserName: "Me", "self-mention")
    }

    @Test("RTL text with a URL bridges")
    func rightToLeft() {
        assertBridgedAllTraits("مرحبا https://example.com شكرا", "rtl")
    }

    @Test("Comma-decimal locale coordinate text bridges")
    func commaDecimalLocale() {
        assertBridgedAllTraits("Meet 48.858400, 2.294500 ok", "comma-decimal")
    }

    @Test("Plain text with no links bridges")
    func plainText() {
        assertBridgedAllTraits("Just a normal sentence.", "plain")
    }

    // MARK: - Focused assertions

    @Test("Incoming base color resolves to UIColor.label, not grey")
    func incomingBaseResolvesToLabel() {
        let swiftUI = swiftUIString("Just a normal sentence.")
        for traits in [Self.light, Self.darkHighContrast] {
            let uikit = bridged(swiftUI, baseColor: Self.incomingBaseColor, traitCollection: traits)
            let color = uikit.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? UIColor
            #expect(color == UIColor.label.resolvedColor(with: traits))
            // UIColor.label is never the placeholder grey; this guards against a `.primary` that
            // failed to bridge and fell through.
            #expect(color != UIColor.placeholderText.resolvedColor(with: traits))
        }
    }

    @Test("Self-mention background highlight bridges in light and dark/high-contrast")
    func selfMentionBackgroundBridges() {
        let swiftUI = swiftUIString("Hey @[Me] there", currentUserName: "Me")
        for traits in [Self.light, Self.darkHighContrast] {
            let uikit = bridged(swiftUI, traitCollection: traits)
            assertSelfMentionBackground(swiftUI, uikit, "self-mention-background")
        }
    }

    @Test("Hashtag bold run is derived from the body text style, not a fixed size")
    func hashtagBoldScalesWithBodyTextStyle() {
        let swiftUI = swiftUIString("ping #news end")
        let uikit = bridged(swiftUI, traitCollection: Self.light)

        let bold = boldCharacterOffsets(in: swiftUI)
        #expect(!bold.isEmpty, "expected a bold hashtag run")

        // A bold run built from the scaled `.body` text style shares the body point size; a fixed-size
        // bold font would not rescale with Dynamic Type and would drift from this expected value.
        let expectedBody = UIFontMetrics(forTextStyle: .body).scaledFont(for: .preferredFont(forTextStyle: .body))
        uikit.enumerateAttribute(.font, in: NSRange(location: 0, length: uikit.length)) { value, range, _ in
            guard let font = value as? UIFont,
                  font.fontDescriptor.symbolicTraits.contains(.traitBold) else { return }
            #expect(
                abs(font.pointSize - expectedBody.pointSize) < 0.01,
                "bold run not derived from body text style at \(range): \(font.pointSize) vs \(expectedBody.pointSize)"
            )
        }
    }

    @Test("Hashtag run is the only bold run and carries a link")
    func hashtagBoldAndLinked() {
        let swiftUI = swiftUIString("ping #news end")
        let uikit = bridged(swiftUI, traitCollection: Self.light)

        let bold = boldCharacterOffsets(in: swiftUI)
        #expect(!bold.isEmpty, "expected a bold hashtag run")

        uikit.enumerateAttribute(.font, in: NSRange(location: 0, length: uikit.length)) { value, range, _ in
            guard let font = value as? UIFont else { return }
            let isBold = font.fontDescriptor.symbolicTraits.contains(.traitBold)
            for offset in range.location..<(range.location + range.length) {
                #expect(isBold == bold.contains(offset))
            }
        }
        assertLinkParity(swiftUI, uikit, "hashtag-bold-link")
    }

    // MARK: - Passive renderer policy

    /// The view empties `linkTextAttributes` so `.link` runs keep their authored `.foregroundColor`
    /// instead of the view's `tintColor`. The bridging assertions above pass even with this missing
    /// (they inspect the `NSAttributedString`, not the live view), so this is the bug-5 guard.
    @Test("configureForBubble empties linkTextAttributes")
    func linkTextAttributesEmptied() {
        let textView = BubbleBodyTextView(usingTextLayoutManager: false)
        textView.configureForBubble()
        #expect(textView.linkTextAttributes.isEmpty)
    }

    /// A tap on a linked glyph resolves the URL; a tap on non-link text, or in the trailing slack
    /// past the end of the line, resolves nil rather than over-hitting the nearest glyph.
    @Test("linkURL(at:) resolves linked glyphs and rejects non-link and trailing taps")
    func linkHitTesting() {
        let url = URL(string: "https://example.com")!
        let textView = makeLaidOutTextView("tap https://example.com end", linkURL: url, linkSubstring: "https://example.com")

        let linkRect = glyphRect(in: textView, of: "https://example.com")
        #expect(textView.linkURL(at: CGPoint(x: linkRect.midX, y: linkRect.midY)) == url)

        let leadingRect = glyphRect(in: textView, of: "tap")
        #expect(textView.linkURL(at: CGPoint(x: leadingRect.midX, y: leadingRect.midY)) == nil)

        #expect(textView.linkURL(at: CGPoint(x: 990, y: linkRect.midY)) == nil)
    }

    /// A link at the very end of the line is not opened by a tap in the empty space to its right.
    @Test("linkURL(at:) does not over-hit a trailing link from a tap past it")
    func linkHitTestingTrailingLink() {
        let url = URL(string: "https://example.com")!
        let textView = makeLaidOutTextView("see https://example.com", linkURL: url, linkSubstring: "https://example.com")

        let linkRect = glyphRect(in: textView, of: "https://example.com")
        #expect(textView.linkURL(at: CGPoint(x: linkRect.midX, y: linkRect.midY)) == url)
        #expect(textView.linkURL(at: CGPoint(x: 995, y: linkRect.midY)) == nil)
    }

    private func makeLaidOutTextView(
        _ text: String,
        linkURL: URL,
        linkSubstring: String
    ) -> BubbleBodyTextView {
        let textView = BubbleBodyTextView(usingTextLayoutManager: false)
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.frame = CGRect(x: 0, y: 0, width: 1000, height: 200)

        let attributed = NSMutableAttributedString(string: text)
        attributed.addAttribute(
            .font,
            value: UIFont.preferredFont(forTextStyle: .body),
            range: NSRange(location: 0, length: attributed.length)
        )
        attributed.addAttribute(.link, value: linkURL, range: (text as NSString).range(of: linkSubstring))
        textView.attributedText = attributed
        textView.layoutManager.ensureLayout(for: textView.textContainer)
        return textView
    }

    private func glyphRect(in textView: BubbleBodyTextView, of substring: String) -> CGRect {
        let characterRange = ((textView.text ?? "") as NSString).range(of: substring)
        let glyphRange = textView.layoutManager.glyphRange(forCharacterRange: characterRange, actualCharacterRange: nil)
        return textView.layoutManager.boundingRect(forGlyphRange: glyphRange, in: textView.textContainer)
    }

    // MARK: - Offset helpers

    private func boldCharacterOffsets(in attributedString: AttributedString) -> Set<Int> {
        characterOffsets(in: attributedString) {
            $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true
        }
    }

    private func underlinedCharacterOffsets(in attributedString: AttributedString) -> Set<Int> {
        characterOffsets(in: attributedString) {
            $0.underlineStyle != nil
        }
    }

    private func backgroundColorOffsets(in attributedString: AttributedString) -> Set<Int> {
        characterOffsets(in: attributedString) {
            $0.backgroundColor != nil
        }
    }

    private func characterOffsets(
        in attributedString: AttributedString,
        where predicate: (AttributedString.Runs.Run) -> Bool
    ) -> Set<Int> {
        var offsets: Set<Int> = []
        var cursor = 0
        for run in attributedString.runs {
            let length = attributedString.characters.distance(
                from: run.range.lowerBound,
                to: run.range.upperBound
            )
            if predicate(run) {
                for offset in cursor..<(cursor + length) {
                    offsets.insert(offset)
                }
            }
            cursor += length
        }
        return offsets
    }

    // MARK: - Helpers

    private static func shareToken(name: String) -> String {
        guard let key = Data(hexString: String(repeating: "AB", count: 32)) else {
            return ""
        }
        return ContactShareUtilities.formatShare(publicKey: key, type: .chat, name: name)
    }
}
