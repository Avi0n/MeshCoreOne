import SwiftUI
import MC1Services

/// The colored, clipped bubble box: text, optional footer, and optional inline
/// image. Reactions, malware warnings, and link previews are emitted as
/// siblings by `UnifiedMessageBubble.body` so they sit below the bubble box.
///
/// Conforms to `Equatable` on `item` alone — closures and dynamic-type
/// environment changes propagate through the parent rebody path.
struct BubbleFragmentStack: View, Equatable {
    let item: MessageItem
    let callbacks: MessageBubbleCallbacks
    let imageResolver: (ImageReference) -> UIImage?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    nonisolated static func == (lhs: BubbleFragmentStack, rhs: BubbleFragmentStack) -> Bool {
        lhs.item == rhs.item
    }

    private var bubbleColor: Color {
        if item.envelope.isOutgoing {
            return item.envelope.hasFailed
                ? AppColors.Message.outgoingBubbleFailed
                : AppColors.Message.outgoingBubble
        } else {
            return AppColors.Message.incomingBubble
        }
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
        VStack(alignment: .leading, spacing: 0) {
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
        .background(bubbleColor)
        .clipShape(.rect(cornerRadius: 16))
    }
}
