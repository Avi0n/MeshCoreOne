import Foundation
import Testing
import UIKit
@testable import MC1
@testable import MC1Services

/// Verifies the wiring on `LinkPreviewFragmentView` so a future refactor that
/// drops a payload field, swaps the resolver direction, or breaks the manual
/// fetch hook fails a test instead of silently changing render behavior. The
/// SwiftUI body itself is covered by `UnifiedMessageBubbleSnapshotTests`;
/// these tests pin the inputs the body reads.
@MainActor
@Suite("LinkPreviewFragmentView wiring")
struct LinkPreviewFragmentTapTests {

    @Test("loaded state preserves preview URL on the payload")
    func loadedState_passesPreviewURLToCard() {
        let urlString = "https://example.com"
        let preview = LinkPreviewDataDTO(url: urlString, title: "Example")
        let state = LinkPreviewFragmentState(
            mode: .loaded(preview, image: nil, icon: nil)
        )

        let view = LinkPreviewFragmentView(
            state: state,
            imageResolver: { _ in nil },
            onManualPreviewFetch: nil
        )

        guard case .loaded(let payload, let image, let icon) = view.state.mode else {
            Issue.record("expected .loaded mode, got \(view.state.mode)")
            return
        }
        #expect(payload.url == urlString)
        #expect(payload.title == "Example")
        #expect(image == nil)
        #expect(icon == nil)
    }

    @Test("loaded state forwards image references through the resolver")
    func loadedState_resolvesImagesByRole() {
        let messageID = UUID()
        let heroImage = solidImage(.systemIndigo)
        let iconImage = solidImage(.systemBlue)
        let preview = LinkPreviewDataDTO(url: "https://example.com", title: "Example")
        let state = LinkPreviewFragmentState(
            mode: .loaded(
                preview,
                image: ImageReference(cacheKey: messageID, role: .linkPreviewImage),
                icon: ImageReference(cacheKey: messageID, role: .linkPreviewIcon)
            )
        )

        let view = LinkPreviewFragmentView(
            state: state,
            imageResolver: { ref in
                switch ref.role {
                case .linkPreviewImage: return heroImage
                case .linkPreviewIcon: return iconImage
                case .inline: return nil
                }
            },
            onManualPreviewFetch: nil
        )

        guard case .loaded(_, let imageRef, let iconRef) = view.state.mode else {
            Issue.record("expected .loaded mode")
            return
        }
        #expect(imageRef.flatMap(view.imageResolver) === heroImage)
        #expect(iconRef.flatMap(view.imageResolver) === iconImage)
    }

    @Test("disabled state retains the manual preview fetch closure")
    func disabledState_tappingTapToLoad_invokesOnManualPreviewFetch() {
        let url = URL(string: "https://example.com")!
        var fired = false
        let state = LinkPreviewFragmentState(mode: .disabled(url))

        let view = LinkPreviewFragmentView(
            state: state,
            imageResolver: { _ in nil },
            onManualPreviewFetch: { fired = true }
        )

        guard case .disabled(let stateURL) = view.state.mode else {
            Issue.record("expected .disabled mode")
            return
        }
        #expect(stateURL == url)
        #expect(view.onManualPreviewFetch != nil)
        view.onManualPreviewFetch?()
        #expect(fired == true)
    }

    @Test("legacy state carries the persisted URL, title, and refs")
    func legacyState_passesPersistedFieldsToCard() {
        let messageID = UUID()
        let url = URL(string: "https://example.com")!
        let state = LinkPreviewFragmentState(
            mode: .legacy(
                url: url,
                title: "Legacy Example",
                image: ImageReference(cacheKey: messageID, role: .linkPreviewImage),
                icon: ImageReference(cacheKey: messageID, role: .linkPreviewIcon)
            )
        )

        let view = LinkPreviewFragmentView(
            state: state,
            imageResolver: { _ in nil },
            onManualPreviewFetch: nil
        )

        guard case .legacy(let stateURL, let title, let imageRef, let iconRef) = view.state.mode else {
            Issue.record("expected .legacy mode")
            return
        }
        #expect(stateURL == url)
        #expect(title == "Legacy Example")
        #expect(imageRef?.role == .linkPreviewImage)
        #expect(iconRef?.role == .linkPreviewIcon)
    }

    // MARK: - Helpers

    private func solidImage(_ color: UIColor, size: CGSize = CGSize(width: 4, height: 4)) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            color.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
        }
    }
}
