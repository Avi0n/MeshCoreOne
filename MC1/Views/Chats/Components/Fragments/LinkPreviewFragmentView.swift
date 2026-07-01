import MC1Services
import SwiftUI
import UIKit

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
    case let .loaded(preview, imageRef, iconRef):
      if let url = state.primaryURL {
        let resolvedImage = imageRef.flatMap(imageResolver)
        if imageRef != nil, resolvedImage == nil {
          // Image bytes still downloading — reserve hero space.
          LinkPreviewLoadingCard(state: state)
        } else {
          LinkPreviewCard(
            url: url,
            title: preview.title,
            image: resolvedImage,
            icon: iconRef.flatMap(imageResolver),
            imageWidth: preview.imageWidth,
            imageHeight: preview.imageHeight,
            onTap: { openURL(url) }
          )
        }
      }
    case .loading:
      LinkPreviewLoadingCard(state: state)
    case let .disabled(url):
      TapToLoadPreview(
        url: url,
        isLoading: false,
        onTap: { onManualPreviewFetch?() }
      )
    case let .legacy(_, title, imageRef, iconRef):
      if let url = state.primaryURL {
        LinkPreviewCard(
          url: url,
          title: title,
          image: imageRef.flatMap(imageResolver),
          icon: iconRef.flatMap(imageResolver),
          imageWidth: nil,
          imageHeight: nil,
          onTap: { openURL(url) }
        )
      }
    case .idle, .noPreview:
      EmptyView()
    }
  }
}
