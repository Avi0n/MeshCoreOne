import MC1Services
import SwiftUI

/// The colored, clipped bubble box: text, optional footer, and optional inline
/// image. Reactions, malware warnings, and link previews are emitted as
/// siblings by `UnifiedMessageBubble.body` so they sit below the bubble box.
///
/// Conforms to `Equatable` on `item` and `bubbleColor` — the parent resolves
/// the contrast-aware color and passes it in so the env read doesn't
/// invalidate body on every visible cell. Closures and dynamic-type
/// environment changes propagate through the parent rebody path.
struct BubbleFragmentStack: View, Equatable {
  /// Corner radius for the bubble box, shared by the text-only shape-fill
  /// background, the inline-image clip path, and the context-menu lift shape
  /// applied by `UnifiedMessageBubble`.
  static let cornerRadius: CGFloat = 16

  let item: MessageItem
  /// The box-resident text and inline image from the shared partition.
  /// Excluded from `==` (which stays `item`/`bubbleColor`): it is a pure
  /// function of `item.content`, so equal items yield equal box fragments.
  let layout: FragmentLayout
  let bubbleColor: Color
  /// Color for the in-bubble send time. Passed in (not read from the theme
  /// environment) for the same reason as `bubbleColor`: keep body invalidation
  /// off the per-cell env-read path.
  let timeColor: Color
  let callbacks: MessageBubbleCallbacks
  let imageResolver: (ImageReference) -> UIImage?

  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  nonisolated static func == (lhs: BubbleFragmentStack, rhs: BubbleFragmentStack) -> Bool {
    lhs.item == rhs.item && lhs.bubbleColor == rhs.bubbleColor && lhs.timeColor == rhs.timeColor
  }

  private var hasFooter: Bool {
    item.footer.sendTimeToShow != nil
      || item.footer.showHop
      || item.footer.formattedPath != nil
      || item.footer.regionToShow != nil
  }

  /// The source URL when the inline image is parked at the scope-off
  /// tap-to-load placeholder. The placeholder carries its own material chrome
  /// and sits inside the padded content stack, so this case renders like a
  /// text-only bubble (shape fill, no edge-to-edge clip), never as an
  /// edge-to-edge `InlineImageFragmentView`.
  private var disabledImageURL: URL? {
    if case let .disabled(url) = layout.inlineImage?.state { url } else { nil }
  }

  var body: some View {
    let stack = VStack(alignment: .leading, spacing: 0) {
      // Stack alignment carries the footer placement: the time sits at the
      // bubble's trailing edge for outgoing, leading for incoming. Driving it
      // through alignment (rather than a greedy `Spacer`/`maxWidth`) keeps the
      // bubble hugging its content — a flexible-width child here leaves the
      // self-sizing hosting cell without a resolvable intrinsic width, which
      // SwiftUI surfaces as a fatal "invalid reuse after initialization failure".
      VStack(alignment: item.envelope.isOutgoing ? .trailing : .leading, spacing: 4) {
        if let textPayload = layout.textPayload {
          MessageTextView(text: textPayload)
        }

        if let disabledImageURL {
          TapToLoadPreview(
            url: disabledImageURL,
            isLoading: false,
            onTap: { callbacks.onManualPreviewFetch?() }
          )
        }

        if hasFooter {
          BubbleFooterRow(
            footer: item.footer,
            dynamicTypeSize: dynamicTypeSize,
            timeColor: timeColor
          )
        }
      }
      .bubbleContentPadding()

      if let inlineImage = layout.inlineImage, disabledImageURL == nil {
        InlineImageFragmentView(
          inlineImage: inlineImage,
          isOutgoing: item.envelope.isOutgoing,
          imageResolver: imageResolver,
          onTap: { callbacks.onImageTap?() },
          onRetry: { callbacks.onRetryInlineImage?() }
        )
      }
    }

    if layout.inlineImage == nil || disabledImageURL != nil {
      // Text-only bubbles (and the scope-off tap-to-load placeholder) fill a
      // rounded shape directly. Drawing the background as a shape avoids the
      // mask `.clipShape` installs, which forces an offscreen render pass per
      // bubble while scrolling.
      stack.background(bubbleColor, in: .rect(cornerRadius: Self.cornerRadius))
    } else {
      // Image bubbles keep the clip so the edge-to-edge image inherits the
      // rounded corners.
      stack
        .background(bubbleColor)
        .clipShape(.rect(cornerRadius: Self.cornerRadius))
    }
  }
}
