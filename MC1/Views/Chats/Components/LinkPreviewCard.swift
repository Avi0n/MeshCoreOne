import SwiftUI

/// Displays a link preview with image, title, and domain. The hero frame is
/// reserved from the image's aspect ratio (clamped to a min/max height) so the
/// bubble does not jump when image bytes arrive after layout.
struct LinkPreviewCard: View {
    let url: URL
    let title: String?
    let image: UIImage?
    let icon: UIImage?
    let imageWidth: Int?
    let imageHeight: Int?
    let onTap: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var minHeroHeight: CGFloat = 100
    @ScaledMetric(relativeTo: .body) private var maxHeroHeight: CGFloat = 250

    private static let fallbackAspect: Double = 16.0 / 9.0
    private static let cardCornerRadius: CGFloat = 12
    private static let cardPadding: CGFloat = 10
    private static let headerSpacing: CGFloat = 8
    private static let iconSize: CGFloat = 16
    private static let iconCornerRadius: CGFloat = 4

    init(
        url: URL,
        title: String?,
        image: UIImage?,
        icon: UIImage?,
        imageWidth: Int? = nil,
        imageHeight: Int? = nil,
        onTap: @escaping () -> Void
    ) {
        self.url = url
        self.title = title
        self.image = image
        self.icon = icon
        self.imageWidth = imageWidth
        self.imageHeight = imageHeight
        self.onTap = onTap
    }

    private var domain: String {
        url.host ?? url.absoluteString
    }

    /// Allow more lines for larger accessibility text sizes
    private var titleLineLimit: Int {
        dynamicTypeSize.isAccessibilitySize ? 4 : 2
    }

    private var domainLineLimit: Int {
        dynamicTypeSize.isAccessibilitySize ? 2 : 1
    }

    private var heroAspect: CGFloat {
        guard let imageWidth, let imageHeight, imageWidth > 0, imageHeight > 0 else {
            return CGFloat(Self.fallbackAspect)
        }
        return CGFloat(imageWidth) / CGFloat(imageHeight)
    }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                if let image {
                    Color.clear
                        .aspectRatio(heroAspect, contentMode: .fit)
                        .frame(minHeight: minHeroHeight, maxHeight: maxHeroHeight)
                        .frame(maxWidth: .infinity)
                        .overlay {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                        }
                        .clipShape(.rect(
                            topLeadingRadius: Self.cardCornerRadius,
                            topTrailingRadius: Self.cardCornerRadius
                        ))
                }

                HStack(spacing: Self.headerSpacing) {
                    if let icon {
                        Image(uiImage: icon)
                            .resizable()
                            .frame(width: Self.iconSize, height: Self.iconSize)
                            .clipShape(.rect(cornerRadius: Self.iconCornerRadius))
                    } else {
                        Image(systemName: "globe")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        if let title, !title.isEmpty {
                            Text(title)
                                .font(.subheadline)
                                .bold()
                                .lineLimit(titleLineLimit)
                                .foregroundStyle(.primary)
                        }

                        Text(domain)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(domainLineLimit)
                    }

                    Spacer()
                }
                .padding(Self.cardPadding)
            }
            .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: Self.cardCornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.Chats.Chats.LinkPreview.Accessibility.label(title ?? domain, domain))
        .accessibilityHint(L10n.Chats.Chats.LinkPreview.Accessibility.hint)
    }
}

#Preview("With Image") {
    LinkPreviewCard(
        url: URL(string: "https://apple.com/iphone")!,
        title: "iPhone 16 Pro - Apple",
        image: nil,
        icon: nil,
        onTap: {}
    )
    .padding()
}

#Preview("Without Image") {
    LinkPreviewCard(
        url: URL(string: "https://example.com/article")!,
        title: "An Interesting Article About Technology",
        image: nil,
        icon: nil,
        onTap: {}
    )
    .padding()
}
