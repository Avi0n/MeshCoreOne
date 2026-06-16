import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("FragmentLayout box-vs-sibling partition")
struct FragmentLayoutTests {

    // MARK: - Sample fragments, one per kind

    private static func textFragment(_ raw: String = "hi") -> MessageFragment {
        .text(MessageTextPayload(
            raw: raw,
            formatted: nil,
            baseColor: .incoming,
            isOutgoing: false,
            currentUserName: "Me"
        ))
    }

    private static func inlineImageFragment(_ urlString: String = "https://example.com/a.png") -> MessageFragment {
        .inlineImage(InlineImage(
            state: .idle(URL(string: urlString)!),
            autoPlayGIFs: false
        ))
    }

    private static let reactionFragment: MessageFragment = .reactionSummary("👍:1")

    private static let malwareFragment: MessageFragment = .malwareWarning(URL(string: "https://bad.example")!)

    private static let linkPreviewFragment: MessageFragment = .linkPreview(
        LinkPreviewFragmentState(mode: .loading(URL(string: "https://example.com")!))
    )

    private static let mapPreviewFragment: MessageFragment = .mapPreview(
        MapPreviewFragmentState(latitude: 37.7749, longitude: -122.4194, isDark: false, isOffline: false, isReady: true)
    )

    // MARK: - Per-kind placement (the rule)

    @Test("text renders in the box, not as a sibling")
    func textIsBoxResident() {
        let layout = FragmentLayout(content: [Self.textFragment("hello")])
        #expect(layout.textPayload?.raw == "hello")
        #expect(layout.inlineImage == nil)
        #expect(layout.siblings.isEmpty)
    }

    @Test("inline image renders in the box, not as a sibling")
    func inlineImageIsBoxResident() {
        let layout = FragmentLayout(content: [Self.inlineImageFragment()])
        #expect(layout.inlineImage != nil)
        #expect(layout.textPayload == nil)
        #expect(layout.siblings.isEmpty)
    }

    @Test("reaction summary renders as a sibling, not in the box")
    func reactionIsSibling() {
        let layout = FragmentLayout(content: [Self.reactionFragment])
        #expect(layout.textPayload == nil)
        #expect(layout.inlineImage == nil)
        #expect(Self.kinds(layout.siblings) == [.reactionSummary])
    }

    @Test("malware warning renders as a sibling, not in the box")
    func malwareIsSibling() {
        let layout = FragmentLayout(content: [Self.malwareFragment])
        #expect(Self.kinds(layout.siblings) == [.malwareWarning])
    }

    @Test("link preview renders as a sibling, not in the box")
    func linkPreviewIsSibling() {
        let layout = FragmentLayout(content: [Self.linkPreviewFragment])
        #expect(Self.kinds(layout.siblings) == [.linkPreview])
    }

    @Test("map preview renders as a sibling, not in the box")
    func mapPreviewIsSibling() {
        let layout = FragmentLayout(content: [Self.mapPreviewFragment])
        #expect(Self.kinds(layout.siblings) == [.mapPreview])
    }

    // MARK: - Ordering and "first wins"

    @Test("siblings preserve document order, excluding box fragments")
    func siblingsPreserveDocumentOrder() {
        // Mirrors the production shape: text, then reaction, then link preview,
        // then map preview, with an inline image interleaved.
        let content: [MessageFragment] = [
            Self.textFragment(),
            Self.reactionFragment,
            Self.inlineImageFragment(),
            Self.linkPreviewFragment,
            Self.mapPreviewFragment
        ]
        let layout = FragmentLayout(content: content)
        #expect(layout.textPayload != nil)
        #expect(layout.inlineImage != nil)
        #expect(Self.kinds(layout.siblings) == [.reactionSummary, .linkPreview, .mapPreview])
    }

    @Test("only the first text and first inline image fill the box")
    func firstBoxFragmentWins() {
        let content: [MessageFragment] = [
            Self.textFragment("first"),
            Self.textFragment("second"),
            Self.inlineImageFragment("https://example.com/1.png"),
            Self.inlineImageFragment("https://example.com/2.png")
        ]
        let layout = FragmentLayout(content: content)
        #expect(layout.textPayload?.raw == "first")
        guard case .idle(let url) = layout.inlineImage?.state else {
            Issue.record("expected idle inline image")
            return
        }
        #expect(url.absoluteString == "https://example.com/1.png")
        #expect(layout.siblings.isEmpty)
    }

    @Test("empty content yields an empty layout")
    func emptyContent() {
        let layout = FragmentLayout(content: [])
        #expect(layout.textPayload == nil)
        #expect(layout.inlineImage == nil)
        #expect(layout.siblings.isEmpty)
    }

    // MARK: - Reproduces the pre-refactor scans

    @Test("partition matches the linear scans it replaces", arguments: kindMatrix)
    func partitionMatchesLinearScans(scenario: KindScenario) {
        let content = scenario.kinds.map(Self.fragment(for:))
        let layout = FragmentLayout(content: content)

        // Box: the first text and first inline image, exactly as the old
        // BubbleFragmentStack `textPayload`/`inlineImageFragment` scans found them.
        let expectedBoxText = content.contains { if case .text = $0 { return true } else { return false } }
        let expectedBoxImage = content.contains { if case .inlineImage = $0 { return true } else { return false } }
        #expect((layout.textPayload != nil) == expectedBoxText)
        #expect((layout.inlineImage != nil) == expectedBoxImage)

        // Siblings: content minus text/inlineImage, in order, exactly as the old
        // UnifiedMessageBubble ForEach + EmptyView arm produced them.
        let expectedSiblings = content.filter {
            switch $0 {
            case .text, .inlineImage: return false
            case .linkPreview, .mapPreview, .malwareWarning, .reactionSummary: return true
            }
        }
        #expect(Self.kinds(layout.siblings) == Self.kinds(expectedSiblings))
    }

    nonisolated static let kindMatrix: [KindScenario] = [
        KindScenario(name: "text only", kinds: [.text]),
        KindScenario(name: "text + reaction", kinds: [.text, .reactionSummary]),
        KindScenario(name: "text + malware + map", kinds: [.text, .malwareWarning, .mapPreview]),
        KindScenario(name: "text + inline image", kinds: [.text, .inlineImage]),
        KindScenario(name: "text + link preview", kinds: [.text, .linkPreview]),
        KindScenario(name: "text + map preview", kinds: [.text, .mapPreview]),
        KindScenario(name: "all kinds", kinds: [.text, .reactionSummary, .inlineImage, .linkPreview, .mapPreview])
    ]

    struct KindScenario: Sendable, CustomStringConvertible {
        let name: String
        let kinds: [FragmentKind]
        var description: String { name }
    }

    // MARK: - Helpers

    enum FragmentKind: Sendable, Equatable {
        case text, inlineImage, linkPreview, mapPreview, malwareWarning, reactionSummary
    }

    private static func fragment(for kind: FragmentKind) -> MessageFragment {
        switch kind {
        case .text: return textFragment()
        case .inlineImage: return inlineImageFragment()
        case .linkPreview: return linkPreviewFragment
        case .mapPreview: return mapPreviewFragment
        case .malwareWarning: return malwareFragment
        case .reactionSummary: return reactionFragment
        }
    }

    private static func kind(of fragment: MessageFragment) -> FragmentKind {
        switch fragment {
        case .text: return .text
        case .inlineImage: return .inlineImage
        case .linkPreview: return .linkPreview
        case .mapPreview: return .mapPreview
        case .malwareWarning: return .malwareWarning
        case .reactionSummary: return .reactionSummary
        }
    }

    private static func kinds(_ fragments: [MessageFragment]) -> [FragmentKind] {
        fragments.map(kind(of:))
    }
}
