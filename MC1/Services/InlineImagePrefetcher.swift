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

    private let imageCache: any InlineImageDimensionProbing
    private let linkPreviewCache: any LinkPreviewCaching
    private let dimensionsStore: InlineImageDimensionsStore
    private let dataStore: any PersistenceStoreProtocol

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
    ///
    /// Delegates URL extraction to `LinkPreviewService.extractAllURLs(in:)` so
    /// the receive-time prefetcher and the per-message URL-detection writer
    /// see the same set of URLs (Giphy short-codes expanded, `@[mention]`
    /// ranges skipped, HTTP/HTTPS only).
    func prefetch(urlsIn text: String, isChannelMessage: Bool) async {
        let urls = LinkPreviewService.extractAllURLs(in: text)
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
}
