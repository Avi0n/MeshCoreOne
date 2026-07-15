import MC1Services
import SwiftUI

/// Placeholder card shown while a link preview is being resolved or while the
/// hero image bytes are downloading. Driven by `LinkPreviewFragmentState.Mode`
/// so the shape matches what `LinkPreviewCard` will eventually render — that
/// keeps the bubble's reserved height stable across the transition.
struct LinkPreviewLoadingCard: View {
  let state: LinkPreviewFragmentState

  @ScaledMetric(relativeTo: .body) private var minHeroHeight: CGFloat = RichPreviewMetrics.minHeroHeight
  @ScaledMetric(relativeTo: .body) private var maxHeroHeight: CGFloat = RichPreviewMetrics.maxHeroHeight

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
    case let .loading(url):
      unknownImageCard(url: url)
    case let .loaded(preview, _, _):
      confirmedImageCard(preview: preview)
    default:
      EmptyView()
    }
  }

  /// Mode 1: shimmer hero + shimmer text rows. URL may be nil for `.idle`.
  @ViewBuilder
  private func unknownImageCard(url: URL?) -> some View {
    let host = url?.host ?? ""
    VStack(alignment: .leading, spacing: 0) {
      // Prefer the remembered hero aspect for this URL so the shimmer reserves
      // the final card footprint; the guess only applies to never-seen URLs.
      shimmerHero(aspect: state.heroAspectHint.map { CGFloat($0) } ?? CGFloat(RichPreviewMetrics.fallbackAspect))

      VStack(alignment: .leading, spacing: Self.textRowSpacing) {
        shimmerRow(height: Self.titleRowHeight, widthFraction: Self.titleRowWidthFraction)
        shimmerRow(height: Self.domainRowHeight, widthFraction: Self.domainRowWidthFraction)
      }
      .padding(Self.cardPadding)
    }
    .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: RichPreviewMetrics.cornerRadius))
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(L10n.Chats.Chats.Preview.loadingAccessibility(host))
    .accessibilityHint(L10n.Chats.Chats.Preview.loadingHint)
  }

  /// Mode 2: placeholder hero at the eventual size + real title + domain.
  @ViewBuilder
  private func confirmedImageCard(preview: LinkPreviewDataDTO) -> some View {
    let url = URL(string: preview.url)
    let host = url?.host ?? preview.url

    VStack(alignment: .leading, spacing: 0) {
      shimmerHero(aspect: RichPreviewMetrics.heroAspect(
        imageWidth: preview.imageWidth,
        imageHeight: preview.imageHeight
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
    .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: RichPreviewMetrics.cornerRadius))
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(L10n.Chats.Chats.Preview.loadingAccessibility(host))
    .accessibilityHint(L10n.Chats.Chats.Preview.loadingHint)
  }

  /// The hero placeholder reserves the eventual image's footprint. Top corners
  /// are rounded; the bottom meets the in-card text rows squarely.
  private func shimmerHero(aspect: CGFloat) -> some View {
    RichPreviewCard(
      aspect: aspect,
      minHeight: minHeroHeight,
      maxHeight: maxHeroHeight,
      cornerStyle: .top
    ) {
      PreviewSkeleton(cornerRadius: 0)
    }
  }

  private func shimmerRow(height: CGFloat, widthFraction: CGFloat) -> some View {
    GeometryReader { proxy in
      PreviewSkeleton(cornerRadius: Self.shimmerCornerRadius)
        .frame(width: proxy.size.width * widthFraction, height: height)
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
