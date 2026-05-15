import SwiftUI
import UIKit
import MC1Services

/// Fragment-level view that renders the inline-image slot of a message bubble.
/// Driven by a typed `InlineImage` payload plus a closure-based image resolver
/// that maps the inline `ImageReference` back to the preloaded UIImage on the
/// owning view state.
struct InlineImageFragmentView: View {
    let inlineImage: InlineImage
    let isOutgoing: Bool
    let imageResolver: (ImageReference) -> UIImage?
    let onTap: () -> Void
    let onRetry: () -> Void

    var body: some View {
        Group {
            switch inlineImage.state {
            case .loaded(let ref, let isGIF):
                if let image = imageResolver(ref) {
                    InlineImageView(
                        image: image,
                        isGIF: isGIF,
                        autoPlayGIFs: inlineImage.autoPlayGIFs,
                        isEmbedded: true,
                        onTap: onTap
                    )
                    .frame(maxWidth: .infinity)
                }

            case .loading, .idle:
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                        .tint(isOutgoing ? .white.opacity(0.7) : nil)
                    Text(L10n.Chats.Chats.InlineImage.loading)
                        .font(.subheadline)
                        .foregroundStyle(isOutgoing ? .white.opacity(0.7) : .secondary)
                }
                .bubbleContentPadding()
                .accessibilityElement(children: .combine)
                .accessibilityLabel(L10n.Chats.Chats.InlineImage.loading)

            case .failed:
                Button(action: onRetry) {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.clockwise")
                            .foregroundStyle(isOutgoing ? .white.opacity(0.7) : .secondary)
                        Text(L10n.Chats.Chats.InlineImage.tapToRetry)
                            .font(.subheadline)
                            .foregroundStyle(isOutgoing ? .white.opacity(0.7) : .secondary)
                    }
                    .bubbleContentPadding()
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.Chats.Chats.InlineImage.failedLabel)
            }
        }
    }
}
