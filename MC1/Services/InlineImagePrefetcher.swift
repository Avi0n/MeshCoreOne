import Foundation
import CoreGraphics
import MC1Services

/// Probing seam for inline image dimension lookup. Lets tests inject a
/// stand-in for `InlineImageCache.probeImageDimensions(url:)`.
protocol InlineImageDimensionProbing: AnyObject, Sendable {
    func probeImageDimensions(url: URL) async -> CGSize?
}

extension InlineImageCache: InlineImageDimensionProbing {}

/// Drives receive-time prefetching of inline image dimensions and link
/// preview metadata for every URL in a new message body. Fans the work out
/// in parallel; callers wrap the call in a `Task` and time it out (3s) so
/// a slow probe never blocks message admission.
@MainActor
final class InlineImagePrefetcher {

    private static let httpScheme = "http"
    private static let httpsScheme = "https"

    private let imageCache: any InlineImageDimensionProbing
    private let linkPreviewCache: any LinkPreviewCaching
    private let dimensionsStore: InlineImageDimensionsStore
    private let dataStore: any PersistenceStoreProtocol

    /// Shared URL detector instance to avoid reallocating per call.
    private static let urlDetector: NSDataDetector? = {
        try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    }()

    init(
        imageCache: any InlineImageDimensionProbing,
        linkPreviewCache: any LinkPreviewCaching,
        dimensionsStore: InlineImageDimensionsStore,
        dataStore: any PersistenceStoreProtocol
    ) {
        self.imageCache = imageCache
        self.linkPreviewCache = linkPreviewCache
        self.dimensionsStore = dimensionsStore
        self.dataStore = dataStore
    }

    /// Prefetch dimensions and link-preview metadata for every URL in `text`.
    /// Returns once all probes have resolved (success or failure). Never throws.
    func prefetch(urlsIn text: String, isChannelMessage: Bool) async {
        let urls = Self.extractURLs(from: text)
        guard !urls.isEmpty else { return }

        let imageCache = self.imageCache
        let linkPreviewCache = self.linkPreviewCache
        let dimensionsStore = self.dimensionsStore
        let dataStore = self.dataStore

        await withTaskGroup(of: Void.self) { group in
            for url in urls {
                if ImageURLClassifier.isDirectImageURL(url) {
                    guard dimensionsStore.aspect(for: url) == nil else { continue }
                    group.addTask {
                        _ = await imageCache.probeImageDimensions(url: url)
                    }
                } else {
                    group.addTask {
                        _ = await linkPreviewCache.preview(
                            for: url,
                            using: dataStore,
                            isChannelMessage: isChannelMessage
                        )
                    }
                }
            }
        }
    }

    /// Returns every HTTP(S) URL detected in `text`, in document order.
    private static func extractURLs(from text: String) -> [URL] {
        guard !text.isEmpty, let detector = urlDetector else { return [] }

        let range = NSRange(text.startIndex..., in: text)
        let matches = detector.matches(in: text, options: [], range: range)

        var urls: [URL] = []
        urls.reserveCapacity(matches.count)
        for match in matches {
            guard let url = match.url,
                  let scheme = url.scheme?.lowercased(),
                  scheme == httpScheme || scheme == httpsScheme else { continue }
            urls.append(url)
        }
        return urls
    }
}
