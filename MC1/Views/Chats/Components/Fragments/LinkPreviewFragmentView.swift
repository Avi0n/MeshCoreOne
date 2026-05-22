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
    let bubbleContentWidth: CGFloat?

    @Environment(\.openURL) private var openURL

    init(
        state: LinkPreviewFragmentState,
        imageResolver: @escaping (ImageReference) -> UIImage?,
        onManualPreviewFetch: (() -> Void)?,
        bubbleContentWidth: CGFloat? = nil
    ) {
        self.state = state
        self.imageResolver = imageResolver
        self.onManualPreviewFetch = onManualPreviewFetch
        self.bubbleContentWidth = bubbleContentWidth
    }

    var body: some View {
        switch state.mode {
        case .loaded(let preview, let imageRef, let iconRef):
            if let url = URL(string: preview.url) {
                let resolvedImage = imageRef.flatMap(imageResolver)
                if imageRef != nil && resolvedImage == nil {
                    // Image bytes still downloading — reserve hero space.
                    LinkPreviewLoadingCard(state: state, bubbleContentWidth: bubbleContentWidth)
                } else {
                    LinkPreviewCard(
                        url: url,
                        title: preview.title,
                        image: resolvedImage,
                        icon: iconRef.flatMap(imageResolver),
                        imageWidth: preview.imageWidth,
                        imageHeight: preview.imageHeight,
                        bubbleContentWidth: bubbleContentWidth,
                        onTap: { openURL(url) }
                    )
                }
            }
        case .loading:
            LinkPreviewLoadingCard(state: state, bubbleContentWidth: bubbleContentWidth)
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
                imageWidth: nil,
                imageHeight: nil,
                bubbleContentWidth: bubbleContentWidth,
                onTap: { openURL(url) }
            )
        case .idle, .noPreview:
            EmptyView()
        }
    }
}
