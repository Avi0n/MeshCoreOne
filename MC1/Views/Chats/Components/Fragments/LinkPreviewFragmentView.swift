import SwiftUI
import UIKit
import MC1Services

/// Fragment-level view that renders the link-preview slot of a message bubble.
/// Driven by a `LinkPreviewFragmentState` payload plus a closure-based image
/// resolver — keeps the view free of view-model lookups so it stays a pure
/// function of its inputs.
struct LinkPreviewFragmentView: View {
    let state: LinkPreviewFragmentState
    let imageResolver: (ImageReference) -> UIImage?
    let onManualPreviewFetch: (() -> Void)?

    @Environment(\.openURL) private var openURL

    var body: some View {
        switch state.mode {
        case .loaded(let preview, let imageRef, let iconRef):
            if let url = URL(string: preview.url) {
                LinkPreviewCard(
                    url: url,
                    title: preview.title,
                    image: imageRef.flatMap(imageResolver),
                    icon: iconRef.flatMap(imageResolver),
                    onTap: { openURL(url) }
                )
            }
        case .loading(let url):
            LinkPreviewLoadingCard(url: url)
        case .disabled(let url):
            TapToLoadPreview(
                url: url,
                isLoading: false,
                onTap: { onManualPreviewFetch?() }
            )
        case .legacy(let url, let title, let imageRef, let iconRef):
            LinkPreviewCard(
                url: url,
                title: title,
                image: imageRef.flatMap(imageResolver),
                icon: iconRef.flatMap(imageResolver),
                onTap: { openURL(url) }
            )
        case .idle, .noPreview:
            EmptyView()
        }
    }
}
