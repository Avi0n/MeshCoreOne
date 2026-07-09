import Foundation
import LinkPresentation
import MC1Services
import os
import UIKit
import UniformTypeIdentifiers

/// Metadata extracted from a URL for link previews
struct LinkPreviewMetadata {
  let url: URL
  let title: String?
  let imageData: Data?
  let iconData: Data?
}

/// Abstracts the network metadata fetch so the cache layer's fetch
/// coalescing can be tested without the LinkPresentation network path.
protocol LinkMetadataFetching: Sendable {
  func fetchMetadata(for url: URL) async -> LinkPreviewMetadata?
}

/// Service for extracting URLs from text and fetching link metadata
final class LinkPreviewService: LinkMetadataFetching, Sendable {
  /// Not private: shared with the scrape/image-fetch helpers declared in
  /// this type's extension.
  let logger = Logger(subsystem: "com.mc1", category: "LinkPreviewService")

  /// Shared session for the `og:image` scrape/image GETs used when
  /// `LPMetadataProvider` finds no hero image. Injectable so tests can
  /// substitute a `URLProtocol` stub; production shares one app-lifetime
  /// redirect-checked session, since a delegate-bound `URLSession` retains
  /// itself and its delegate until invalidated and service instances are
  /// created per environment read.
  let scrapeSession: URLSession

  init(scrapeSession: URLSession? = nil) {
    self.scrapeSession = scrapeSession ?? Self.sharedScrapeSession
  }

  /// Shared URL detector instance to avoid creating NSDataDetector on every call
  private static let urlDetector: NSDataDetector? = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)

  /// Extracts a Giphy GIF URL from meshcore-open `g:{id}` message format.
  /// - Parameter text: Message text to check
  /// - Returns: Giphy direct GIF URL if text matches `g:{id}` format, nil otherwise
  static func extractGiphyGIFURL(from text: String) -> URL? {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let match = trimmed.wholeMatch(of: /g:([A-Za-z0-9_-]+)/) else { return nil }
    return URL(string: "https://media.giphy.com/media/\(match.1)/giphy.gif")
  }

  /// Extracts the first HTTP/HTTPS URL from text, excluding URLs within mentions.
  /// - Parameter text: Message text to scan
  /// - Returns: First HTTP(S) URL found outside mentions, or nil
  static func extractFirstURL(from text: String) -> URL? {
    extractAllURLs(in: text).first
  }

  /// Extracts every HTTP/HTTPS URL from text, in document order. Used by the
  /// receive-time prefetcher and the per-message URL-detection writer so
  /// both paths see the same set of URLs:
  /// - meshcore-open `g:{id}` short-codes are expanded to direct Giphy URLs
  /// - URLs inside `@[mention]` ranges are skipped
  /// - schemes are restricted to HTTP / HTTPS
  static func extractAllURLs(in text: String) -> [URL] {
    guard !text.isEmpty else { return [] }

    // Check for meshcore-open g:{giphy_id} format first; when present it
    // is the entire message (trimmed wholeMatch), so no detector pass.
    if let gifURL = extractGiphyGIFURL(from: text) {
      return [gifURL]
    }

    guard let detector = urlDetector else { return [] }

    let mentionRanges = extractMentionRanges(from: text)
    let range = NSRange(text.startIndex..., in: text)
    let matches = detector.matches(in: text, options: [], range: range)

    var urls: [URL] = []
    urls.reserveCapacity(matches.count)
    for match in matches {
      guard let url = match.url,
            let scheme = url.scheme?.lowercased(),
            scheme == "http" || scheme == "https" else {
        continue
      }

      let urlRange = match.range
      let overlapsWithMention = mentionRanges.contains { mentionRange in
        NSIntersectionRange(urlRange, mentionRange).length > 0
      }
      if overlapsWithMention { continue }

      urls.append(url)
    }

    return urls
  }

  /// Cached mention regex to avoid re-creating on every call
  private static let mentionRegex: NSRegularExpression? = try? NSRegularExpression(pattern: MentionUtilities.mentionPattern)

  /// Extracts ranges of all mentions in the text (format: @[name])
  private static func extractMentionRanges(from text: String) -> [NSRange] {
    guard let regex = mentionRegex else { return [] }
    let range = NSRange(text.startIndex..., in: text)
    return regex.matches(in: text, range: range).map(\.range)
  }

  /// Timeout for the LinkPresentation (WebKit-backed) metadata fetch.
  private static let linkPresentationTimeout: TimeInterval = 10

  /// Fetches metadata for a URL using LinkPresentation framework, falling
  /// back to a plain-GET `og:image` scrape when LinkPresentation finds no
  /// hero image (its WebKit-backed extractor chokes on some ad/JS-heavy
  /// pages that still ship a static `og:image` in server HTML).
  /// - Parameter url: The URL to fetch metadata for
  /// - Returns: Metadata if either leg found a title or image, nil otherwise
  func fetchMetadata(for url: URL) async -> LinkPreviewMetadata? {
    guard await URLSafetyChecker.isSafe(url) else {
      logger.warning("Blocked metadata fetch to unsafe URL: \(url.host() ?? "unknown")")
      return nil
    }

    let provider = LPMetadataProvider()
    provider.timeout = Self.linkPresentationTimeout

    let lpMetadata: LPLinkMetadata?
    do {
      lpMetadata = try await provider.startFetchingMetadata(for: url)
    } catch {
      logger.warning("Failed to fetch metadata for \(url): \(error.localizedDescription)")
      lpMetadata = nil
    }

    var title = lpMetadata?.title
    var iconData: Data?
    if let iconProvider = lpMetadata?.iconProvider {
      iconData = await loadData(from: iconProvider)
    }
    var imageData: Data?
    if let imageProvider = lpMetadata?.imageProvider {
      imageData = await loadData(from: imageProvider)
    }

    if imageData == nil, let scraped = await scrapeHTMLMetadata(for: url) {
      if title == nil {
        title = scraped.title
      }
      if let imageURL = scraped.imageURL, await URLSafetyChecker.isSafe(imageURL) {
        imageData = await loadImageData(from: imageURL)
      }
    }

    guard lpMetadata != nil || title != nil || imageData != nil else { return nil }
    return LinkPreviewMetadata(url: url, title: title, imageData: imageData, iconData: iconData)
  }

  /// Maximum image size in bytes (500KB)
  private static let maxImageSize = 500 * 1024

  /// Loads image data from an NSItemProvider, compressing if necessary
  private func loadData(from provider: NSItemProvider) async -> Data? {
    let rawData = await withCheckedContinuation { continuation in
      _ = provider.loadDataRepresentation(for: .image) { data, error in
        if let error {
          self.logger.debug("Failed to load image data: \(error.localizedDescription)")
        }
        continuation.resume(returning: data)
      }
    }
    return fitToMaxSize(rawData)
  }

  /// Caps image data to `maxImageSize`, compressing if it's over. Shared by
  /// both the LinkPresentation icon/image legs and the scraped-image
  /// fallback so an oversized hero image from either path gets the same
  /// downgrade treatment.
  func fitToMaxSize(_ data: Data?) -> Data? {
    guard let data else { return nil }

    // If within size limit, return as-is
    if data.count <= Self.maxImageSize {
      return data
    }

    // Compress the image
    return compressImage(data: data, maxSize: Self.maxImageSize)
  }

  /// Compresses image data to fit within a maximum size
  private func compressImage(data: Data, maxSize: Int) -> Data? {
    guard let image = UIImage(data: data) else { return data }

    // Start with high quality and reduce until within size
    var quality: CGFloat = 0.8
    var compressed = image.jpegData(compressionQuality: quality)

    while let compressedData = compressed, compressedData.count > maxSize, quality > 0.1 {
      quality -= 0.1
      compressed = image.jpegData(compressionQuality: quality)
    }

    // If still too large, scale down the image
    if let compressedData = compressed, compressedData.count > maxSize {
      let scale = sqrt(Double(maxSize) / Double(compressedData.count))
      let newSize = CGSize(
        width: image.size.width * scale,
        height: image.size.height * scale
      )

      let renderer = UIGraphicsImageRenderer(size: newSize)
      let resized = renderer.image { _ in
        image.draw(in: CGRect(origin: .zero, size: newSize))
      }
      compressed = resized.jpegData(compressionQuality: 0.7)
    }

    logger.debug("Compressed image from \(data.count) to \(compressed?.count ?? 0) bytes")
    return compressed
  }
}
