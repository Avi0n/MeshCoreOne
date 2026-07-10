import CoreGraphics
import Foundation
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
  ///
  /// `allowImageProbes` is the privacy gate for direct-image URLs: when false
  /// (master on but auto-resolve off for this conversation type, or handled by
  /// the caller's own master check), the dimension probe is skipped so no
  /// third-party image request fires on receive. The card branch stays
  /// unconditional: `LinkPreviewCache.preview` self-gates via
  /// `shouldAutoResolve` and its cache checks perform no network fetch.
  func prefetch(urlsIn text: String, isChannelMessage: Bool, allowImageProbes: Bool) async {
    let urls = LinkPreviewService.extractAllURLs(in: text)
    guard !urls.isEmpty else { return }

    let imageCache = imageCache
    let linkPreviewCache = linkPreviewCache
    let dimensionsStore = dimensionsStore
    let dataStore = dataStore

    await withTaskGroup(of: Void.self) { group in
      for url in urls {
        if ImageURLClassifier.isImageURL(url) {
          guard allowImageProbes else { continue }
          let probeURL = ImageURLClassifier.directImageURL(for: url)
          guard dimensionsStore.aspect(for: probeURL) == nil else { continue }
          group.addTask {
            _ = await imageCache.probeImageDimensions(url: probeURL)
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
