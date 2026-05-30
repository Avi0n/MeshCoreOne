import SwiftUI
import MC1Services

/// Corner radius for the message bubble box. Shared by the text-only
/// shape-fill background and the inline-image clip path.
private let bubbleCornerRadius: CGFloat = 16

/// The colored, clipped bubble box: text, optional footer, and optional inline
/// image. Reactions, malware warnings, and link previews are emitted as
/// siblings by `UnifiedMessageBubble.body` so they sit below the bubble box.
///
/// Conforms to `Equatable` on `item` and `bubbleColor` — the parent resolves
/// the contrast-aware color and passes it in so the env read doesn't
/// invalidate body on every visible cell. Closures and dynamic-type
/// environment changes propagate through the parent rebody path.
struct BubbleFragmentStack: View, Equatable {
    let item: MessageItem
    let bubbleColor: Color
    let callbacks: MessageBubbleCallbacks
    let imageResolver: (ImageReference) -> UIImage?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    nonisolated static func == (lhs: BubbleFragmentStack, rhs: BubbleFragmentStack) -> Bool {
        lhs.item == rhs.item && lhs.bubbleColor == rhs.bubbleColor
    }

    private var hasFooter: Bool {
        item.footer.showHop
            || item.footer.formattedPath != nil
            || item.footer.regionToShow != nil
    }

    private var inlineImageFragment: InlineImage? {
        for fragment in item.content {
            if case .inlineImage(let inlineImage) = fragment {
                return inlineImage
            }
        }
        return nil
    }

    private var textPayload: MessageTextPayload? {
        for fragment in item.content {
            if case .text(let payload) = fragment {
                return payload
            }
        }
        return nil
    }

    var body: some View {
        let stack = VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                if let textPayload {
                    MessageTextView(text: textPayload)
                }

                if !item.envelope.isOutgoing && hasFooter {
                    BubbleFooterRow(footer: item.footer, dynamicTypeSize: dynamicTypeSize)
                }
            }
            .bubbleContentPadding()

            if let inlineImage = inlineImageFragment {
                InlineImageFragmentView(
                    inlineImage: inlineImage,
                    isOutgoing: item.envelope.isOutgoing,
                    imageResolver: imageResolver,
                    onTap: { callbacks.onImageTap?() },
                    onRetry: { callbacks.onRetryImageFetch?() }
                )
            }
        }

        if inlineImageFragment == nil {
            // Text-only bubbles fill a rounded shape directly. Drawing the
            // background as a shape avoids the mask `.clipShape` installs, which
            // forces an offscreen render pass per bubble while scrolling.
            stack.background(bubbleColor, in: .rect(cornerRadius: bubbleCornerRadius))
        } else {
            // Image bubbles keep the clip so the edge-to-edge image inherits the
            // rounded corners.
            stack
                .background(bubbleColor)
                .clipShape(.rect(cornerRadius: bubbleCornerRadius))
        }
    }
}
