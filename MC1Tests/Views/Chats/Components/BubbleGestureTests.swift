import Testing
import UIKit
@testable import MC1
@testable import MC1Services

/// Proves the host-independent logic behind the bubble's tap-vs-long-press arbitration: the yielding
/// tap recognizer's policy and the set of sibling fragments that carry the actions-sheet long-press.
/// The felt gesture behavior (a sustained press over a GIF or card opening the sheet while a quick
/// tap still fires) is device-only and covered by the manual matrix; this guards the parts that can
/// regress silently in code.
@Suite("Bubble gesture arbitration")
@MainActor
struct BubbleGestureTests {

    // MARK: - Yielding tap recognizer policy

    @Test("yielding tap denies simultaneity with a long-press, allows it with others")
    func yieldsToLongPress() {
        let coordinator = TapYieldingToLongPress.Coordinator(onTap: {})
        let tap = UITapGestureRecognizer()

        #expect(
            coordinator.gestureRecognizer(tap, shouldRecognizeSimultaneouslyWith: UILongPressGestureRecognizer())
                == false
        )
        #expect(
            coordinator.gestureRecognizer(tap, shouldRecognizeSimultaneouslyWith: UIPanGestureRecognizer())
                == true
        )
    }

    @Test("yielding tap never consumes touches and gates its delegate off Mac")
    func recognizerConfiguration() {
        let coordinator = TapYieldingToLongPress.Coordinator(onTap: {})

        let iPad = TapYieldingToLongPress.makeRecognizer(coordinator: coordinator, isMac: false)
        #expect(iPad.cancelsTouchesInView == false)
        #expect(iPad.delegate != nil)

        let mac = TapYieldingToLongPress.makeRecognizer(coordinator: coordinator, isMac: true)
        #expect(mac.cancelsTouchesInView == false)
        #expect(mac.delegate == nil)
    }

    // MARK: - Sibling long-press coverage

    @Test("content-card siblings carry the actions long-press, reactions and box fragments do not")
    func siblingLongPressSet() {
        #expect(UnifiedMessageBubble.siblingWantsActionsLongPress(Self.linkPreviewFragment))
        #expect(UnifiedMessageBubble.siblingWantsActionsLongPress(Self.mapPreviewFragment))
        #expect(UnifiedMessageBubble.siblingWantsActionsLongPress(Self.malwareFragment))

        #expect(!UnifiedMessageBubble.siblingWantsActionsLongPress(Self.reactionFragment))
        #expect(!UnifiedMessageBubble.siblingWantsActionsLongPress(Self.textFragment))
        #expect(!UnifiedMessageBubble.siblingWantsActionsLongPress(Self.inlineImageFragment))
    }

    // MARK: - Sample fragments, one per kind

    private static let textFragment: MessageFragment = .text(
        MessageTextPayload(raw: "hi", formatted: nil, baseColor: .incoming, isOutgoing: false, currentUserName: "Me")
    )

    private static let inlineImageFragment: MessageFragment = .inlineImage(
        InlineImage(state: .idle(URL(string: "https://example.com/a.png")!), autoPlayGIFs: false)
    )

    private static let reactionFragment: MessageFragment = .reactionSummary("👍:1")

    private static let malwareFragment: MessageFragment = .malwareWarning(URL(string: "https://bad.example")!)

    private static let linkPreviewFragment: MessageFragment = .linkPreview(
        LinkPreviewFragmentState(mode: .loading(URL(string: "https://example.com")!))
    )

    private static let mapPreviewFragment: MessageFragment = .mapPreview(
        MapPreviewFragmentState(latitude: 37.7749, longitude: -122.4194, isDark: false, isOffline: false, isReady: true)
    )
}
