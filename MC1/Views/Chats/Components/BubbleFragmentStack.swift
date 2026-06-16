import SwiftUI
import MC1Services

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
    let callbacks: MessageBubbleCallbacks
    let imageResolver: (ImageReference) -> UIImage?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    nonisolated static func == (lhs: BubbleFragmentStack, rhs: BubbleFragmentStack) -> Bool {
        lhs.item == rhs.item && lhs.bubbleColor == rhs.bubbleColor
    }

    private var hasFooter: Bool {
        item.footer.sendTimeToShow != nil
            || item.footer.showHop
            || item.footer.formattedPath != nil
            || item.footer.regionToShow != nil
    }

    var body: some View {
        let stack = VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                if let textPayload = layout.textPayload {
                    MessageTextView(text: textPayload)
                }

                if !item.envelope.isOutgoing && hasFooter {
                    BubbleFooterRow(footer: item.footer, dynamicTypeSize: dynamicTypeSize)
                }
            }
            .bubbleContentPadding()

            if let inlineImage = layout.inlineImage {
                InlineImageFragmentView(
                    inlineImage: inlineImage,
                    isOutgoing: item.envelope.isOutgoing,
                    imageResolver: imageResolver,
                    onTap: { callbacks.onImageTap?() },
                    onRetry: { callbacks.onRetryInlineImage?() }
                )
            }
        }

        if layout.inlineImage == nil {
            // Text-only bubbles fill a rounded shape directly. Drawing the
            // background as a shape avoids the mask `.clipShape` installs, which
            // forces an offscreen render pass per bubble while scrolling.
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
