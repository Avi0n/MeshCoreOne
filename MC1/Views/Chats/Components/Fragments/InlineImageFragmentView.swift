import SwiftUI
import UIKit
import MC1Services

/// Fragment-level view that renders the inline-image slot of a message bubble.
/// Reserves correct height before bytes arrive using `InlineImage.cachedAspect`
/// (falling back to 16:9), so the bubble does not jump when the image loads.
struct InlineImageFragmentView: View {
    let inlineImage: InlineImage
    let isOutgoing: Bool
    let imageResolver: (ImageReference) -> UIImage?
    let onTap: () -> Void
    let onRetry: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .body) private var minHeight: CGFloat = 100
    @ScaledMetric(relativeTo: .body) private var maxHeight: CGFloat = 250

    private static let fallbackAspect: Double = 16.0 / 9.0
    private static let crossFadeDuration: Double = 0.2
    private static let skeletonCornerRadius: CGFloat = 12
    private static let retryIconSpacing: CGFloat = 8
    private static let retryForegroundOpacity: Double = 0.7

    private var aspect: Double {
        inlineImage.cachedAspect ?? Self.fallbackAspect
    }

    var body: some View {
        Color.clear
            .aspectRatio(CGFloat(aspect), contentMode: .fit)
            .frame(minHeight: minHeight, maxHeight: maxHeight)
            .frame(maxWidth: .infinity)
            .overlay {
                ZStack {
                    skeletonLayer
                        .opacity(isLoaded ? 0 : 1)

                    loadedLayer
                        .opacity(isLoaded ? 1 : 0)

                    if case .failed = inlineImage.state {
                        retryLayer
                    }
                }
            }
            .animation(
                reduceMotion ? nil : .easeOut(duration: Self.crossFadeDuration),
                value: isLoaded
            )
    }

    private var isLoaded: Bool {
        if case .loaded(let ref, _) = inlineImage.state,
           imageResolver(ref) != nil {
            return true
        }
        return false
    }

    @ViewBuilder
    private var skeletonLayer: some View {
        RoundedRectangle(cornerRadius: Self.skeletonCornerRadius, style: .continuous)
            .fill(Color(.tertiarySystemFill))
            .modifier(Shimmer(isActive: !reduceMotion))
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var loadedLayer: some View {
        if case .loaded(let ref, let isGIF) = inlineImage.state,
           let image = imageResolver(ref) {
            InlineImageView(
                image: image,
                isGIF: isGIF,
                autoPlayGIFs: inlineImage.autoPlayGIFs,
                isEmbedded: true,
                onTap: onTap
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private var retryLayer: some View {
        Button(action: onRetry) {
            ZStack {
                RoundedRectangle(cornerRadius: Self.skeletonCornerRadius, style: .continuous)
                    .fill(Color(.tertiarySystemFill))
                HStack(spacing: Self.retryIconSpacing) {
                    Image(systemName: "arrow.clockwise")
                        .foregroundStyle(isOutgoing ? .white.opacity(Self.retryForegroundOpacity) : .secondary)
                    Text(L10n.Chats.Chats.InlineImage.tapToRetry)
                        .font(.subheadline)
                        .foregroundStyle(isOutgoing ? .white.opacity(Self.retryForegroundOpacity) : .secondary)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.Chats.Chats.InlineImage.failedLabel)
        .accessibilityHint(L10n.Chats.Chats.InlineImage.retryHint)
    }
}

/// Animated linear-gradient overlay driven by `TimelineView(.animation)` so
/// off-screen reused cells naturally pause. Static when Reduce Motion is on.
/// Implementation detail of `InlineImageFragmentView`.
private struct Shimmer: ViewModifier {
    let isActive: Bool

    private static let duration: Double = 1.5
    private static let highlightOpacity: Double = 0.35
    private static let frameInterval: Double = 1.0 / 60.0

    func body(content: Content) -> some View {
        if isActive {
            content
                .overlay {
                    TimelineView(.animation(minimumInterval: Self.frameInterval)) { context in
                        let elapsed = context.date.timeIntervalSinceReferenceDate
                        let progress = elapsed.truncatingRemainder(dividingBy: Self.duration) / Self.duration
                        let phase = CGFloat(progress) * 2 - 1
                        GeometryReader { proxy in
                            let width = proxy.size.width
                            LinearGradient(
                                stops: [
                                    .init(color: .clear, location: 0),
                                    .init(color: .white.opacity(Self.highlightOpacity), location: 0.5),
                                    .init(color: .clear, location: 1)
                                ],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                            .frame(width: width)
                            .offset(x: phase * width)
                            .blendMode(.plusLighter)
                        }
                    }
                }
                .clipped()
        } else {
            content
        }
    }
}
