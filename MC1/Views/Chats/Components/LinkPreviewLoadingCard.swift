import SwiftUI
import MC1Services

/// Placeholder card shown while a link preview is being resolved or while the
/// hero image bytes are downloading. Driven by `LinkPreviewFragmentState.Mode`
/// so the shape matches what `LinkPreviewCard` will eventually render — that
/// keeps the bubble's reserved height stable across the transition.
struct LinkPreviewLoadingCard: View {
    let state: LinkPreviewFragmentState

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .body) private var minHeroHeight: CGFloat = 100
    @ScaledMetric(relativeTo: .body) private var maxHeroHeight: CGFloat = 250

    private static let fallbackAspect: Double = 16.0 / 9.0
    private static let cardCornerRadius: CGFloat = 12
    private static let shimmerCornerRadius: CGFloat = 4
    private static let cardPadding: CGFloat = 10
    private static let textRowSpacing: CGFloat = 6
    private static let headerSpacing: CGFloat = 8
    private static let titleRowHeight: CGFloat = 14
    private static let domainRowHeight: CGFloat = 10
    private static let titleRowWidthFraction: CGFloat = 0.72
    private static let domainRowWidthFraction: CGFloat = 0.4

    var body: some View {
        switch state.mode {
        case .idle:
            unknownImageCard(url: nil)
        case .loading(let url):
            unknownImageCard(url: url)
        case .loaded(let preview, _, _):
            confirmedImageCard(preview: preview)
        default:
            EmptyView()
        }
    }

    // Mode 1: shimmer hero + shimmer text rows. URL may be nil for `.idle`.
    @ViewBuilder
    private func unknownImageCard(url: URL?) -> some View {
        let host = url?.host ?? ""
        VStack(alignment: .leading, spacing: 0) {
            shimmerHero(aspect: CGFloat(Self.fallbackAspect))
                .clipShape(.rect(
                    topLeadingRadius: Self.cardCornerRadius,
                    topTrailingRadius: Self.cardCornerRadius
                ))

            VStack(alignment: .leading, spacing: Self.textRowSpacing) {
                shimmerRow(height: Self.titleRowHeight, widthFraction: Self.titleRowWidthFraction)
                shimmerRow(height: Self.domainRowHeight, widthFraction: Self.domainRowWidthFraction)
            }
            .padding(Self.cardPadding)
        }
        .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: Self.cardCornerRadius))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.Chats.Chats.Preview.loadingAccessibility(host))
        .accessibilityHint(L10n.Chats.Chats.Preview.loadingHint)
    }

    // Mode 2: placeholder hero at the eventual size + real title + domain.
    @ViewBuilder
    private func confirmedImageCard(preview: LinkPreviewDataDTO) -> some View {
        let url = URL(string: preview.url)
        let host = url?.host ?? preview.url

        VStack(alignment: .leading, spacing: 0) {
            shimmerHero(aspect: heroAspect(
                imageWidth: preview.imageWidth,
                imageHeight: preview.imageHeight
            ))
                .clipShape(.rect(
                    topLeadingRadius: Self.cardCornerRadius,
                    topTrailingRadius: Self.cardCornerRadius
                ))

            HStack(spacing: Self.headerSpacing) {
                Image(systemName: "globe")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                VStack(alignment: .leading, spacing: 2) {
                    if let title = preview.title, !title.isEmpty {
                        Text(title)
                            .font(.subheadline)
                            .bold()
                            .lineLimit(2)
                            .foregroundStyle(.primary)
                    }
                    Text(host)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer()
            }
            .padding(Self.cardPadding)
        }
        .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: Self.cardCornerRadius))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.Chats.Chats.Preview.loadingAccessibility(host))
        .accessibilityHint(L10n.Chats.Chats.Preview.loadingHint)
    }

    private func heroAspect(imageWidth: Int?, imageHeight: Int?) -> CGFloat {
        guard let imageWidth, let imageHeight, imageWidth > 0, imageHeight > 0 else {
            return CGFloat(Self.fallbackAspect)
        }
        return CGFloat(imageWidth) / CGFloat(imageHeight)
    }

    @ViewBuilder
    private func shimmerHero(aspect: CGFloat) -> some View {
        Color.clear
            .aspectRatio(aspect, contentMode: .fit)
            .frame(minHeight: minHeroHeight, maxHeight: maxHeroHeight)
            .frame(maxWidth: .infinity)
            .overlay {
                Rectangle()
                    .fill(Color(.tertiarySystemFill))
                    .modifier(Shimmer(isActive: !reduceMotion))
            }
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private func shimmerRow(height: CGFloat, widthFraction: CGFloat) -> some View {
        GeometryReader { proxy in
            RoundedRectangle(cornerRadius: Self.shimmerCornerRadius, style: .continuous)
                .fill(Color(.tertiarySystemFill))
                .frame(width: proxy.size.width * widthFraction, height: height)
                .modifier(Shimmer(isActive: !reduceMotion))
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

#Preview("Loading - unknown image") {
    LinkPreviewLoadingCard(
        state: .init(mode: .loading(URL(string: "https://example.com/article")!))
    )
    .padding()
}

#Preview("Loaded - awaiting image bytes") {
    LinkPreviewLoadingCard(
        state: .init(mode: .loaded(
            LinkPreviewDataDTO(
                url: "https://apple.com/iphone",
                title: "iPhone 16 Pro - Apple",
                imageWidth: 1200,
                imageHeight: 630
            ),
            image: nil,
            icon: nil
        ))
    )
    .padding()
}
