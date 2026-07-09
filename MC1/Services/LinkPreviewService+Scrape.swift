import Foundation
import ImageIO
import UIKit

/// `og:image` / `og:title` HTML scrape fallback for `LinkPreviewService`,
/// used when `LPMetadataProvider` finds no hero image. No WebKit: a plain
/// GET of the page, a pure `<meta>` tag scan, and a capped image download.
extension LinkPreviewService {
  // MARK: - Constants

  /// Streaming bound for the HTML scrape GET. `og:image`/`og:title` live in
  /// `<head>`, well under this ceiling. Enforced during the stream (reject
  /// on an over-cap `expectedContentLength`, then cancel once the running
  /// total exceeds the cap) rather than via a `Range` header, which is only
  /// a hint a server may ignore, or `data(for:)`, which buffers the whole
  /// body before any `prefix` could bound it.
  private static let htmlScrapeByteCap = 512 * 1024

  /// Pre-decode streaming ceiling for the scraped-image GET, enforced the
  /// same streaming way as `htmlScrapeByteCap`. `maxImageSize` still governs
  /// post-fetch compression, but that is a post-download threshold and
  /// cannot bound an unbounded download or a decode bomb on its own.
  private static let imageByteCap = 2 * 1024 * 1024

  private static let htmlScrapeTimeout: TimeInterval = 5
  private static let imageFetchTimeout: TimeInterval = 5

  /// Defensive only: both known target hosts (imgur, pasteboard) serve
  /// their static `og:image` to a plain GET with no browser User-Agent.
  /// Sent regardless, for hosts that do gate on it.
  private static let scrapeUserAgent = "MC1LinkPreview/1.0"
  private static let userAgentHTTPHeaderField = "User-Agent"

  private static let imageMimePrefix = "image/"
  private static let htmlMimeSubstring = "html"

  /// Max pixel dimension for the bounded scraped-image decode. Applied via
  /// `CGImageSourceCreateThumbnailAtIndex`, which never allocates a
  /// full-size decode buffer, so a small file crafted to decompress into an
  /// oversized bitmap cannot exhaust memory.
  private static let scrapedImageMaxPixelSize: CGFloat = 2000
  private static let scrapedImageJPEGQuality: CGFloat = 0.9

  private static let metaPropertyAttribute = "property"
  private static let metaNameAttribute = "name"
  private static let metaContentAttribute = "content"
  private static let ogImageProperty = "og:image"
  private static let twitterImageProperty = "twitter:image"
  private static let ogTitleProperty = "og:title"

  private static let metaTagPattern: NSRegularExpression? = try? NSRegularExpression(
    pattern: "<meta\\b[^>]*>",
    options: [.caseInsensitive]
  )
  private static let metaAttributePattern: NSRegularExpression? = try? NSRegularExpression(
    pattern: "([a-zA-Z][a-zA-Z0-9-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')"
  )

  /// Ordered so `&amp;` decodes last: unescaping it first would let an
  /// already-escaped entity like `&amp;lt;` incorrectly decode a second
  /// time into `<` instead of the literal text `&lt;`.
  private static let htmlEntities: [(String, String)] = [
    ("&quot;", "\""),
    ("&#39;", "'"),
    ("&apos;", "'"),
    ("&lt;", "<"),
    ("&gt;", ">"),
    ("&amp;", "&")
  ]

  // MARK: - Session

  /// App-lifetime session for the scrape/image GETs, shared by every
  /// `LinkPreviewService` instance: a delegate-bound `URLSession` retains
  /// itself and its delegate until invalidated, so per-instance sessions
  /// would accumulate. A default session follows 3xx redirects
  /// automatically, which would bypass the initial `isSafe` check on a
  /// page/host that redirects to a private target; `RedirectSafetyDelegate`
  /// re-validates every redirect hop so that hop is refused instead of
  /// followed.
  static let sharedScrapeSession = URLSession(
    configuration: .ephemeral,
    delegate: RedirectSafetyDelegate(),
    delegateQueue: nil
  )

  // MARK: - HTML scrape

  /// Fetches the page and scans its `<meta>` tags for an `og:image` /
  /// `twitter:image` hero image and an `og:title`. Returns `nil` on any
  /// network, status, size, or mime failure, or when the page carries
  /// neither hint.
  func scrapeHTMLMetadata(for url: URL) async -> (title: String?, imageURL: URL?)? {
    guard let data = await boundedData(
      for: url,
      timeout: Self.htmlScrapeTimeout,
      byteCap: Self.htmlScrapeByteCap,
      acceptsMime: { $0.contains(Self.htmlMimeSubstring) }
    ) else {
      return nil
    }

    guard let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
      return nil
    }

    return Self.parseHTMLMetadata(html, baseURL: url)
  }

  /// Bounded GET shared by the HTML scrape and the scraped-image fetch:
  /// rejects on an over-cap `expectedContentLength` or unexpected mime,
  /// then enforces `byteCap` while streaming so the cap holds even when the
  /// server lies about, or omits, the content length. Returns `nil` on any
  /// network, status, size, or mime failure.
  private func boundedData(
    for url: URL,
    timeout: TimeInterval,
    byteCap: Int,
    acceptsMime: (String) -> Bool
  ) async -> Data? {
    var request = URLRequest(url: url)
    request.timeoutInterval = timeout
    request.setValue(Self.scrapeUserAgent, forHTTPHeaderField: Self.userAgentHTTPHeaderField)

    let bytes: URLSession.AsyncBytes
    let response: URLResponse
    do {
      (bytes, response) = try await scrapeSession.bytes(for: request)
    } catch {
      logger.debug("Bounded fetch failed for \(url): \(error.localizedDescription)")
      return nil
    }

    guard let httpResponse = response as? HTTPURLResponse,
          (200...299).contains(httpResponse.statusCode),
          httpResponse.expectedContentLength <= Int64(byteCap),
          let mimeType = httpResponse.mimeType,
          acceptsMime(mimeType) else {
      return nil
    }

    var data = Data()
    do {
      for try await byte in bytes {
        data.append(byte)
        if data.count > byteCap {
          return nil
        }
      }
    } catch {
      logger.debug("Bounded fetch stream failed for \(url): \(error.localizedDescription)")
      return nil
    }

    return data
  }

  /// Scans every `<meta>` tag in `html` for Open Graph / Twitter Card hero
  /// image and title hints. Pure and side-effect-free so it can run against
  /// captured HTML fixtures in tests without a network fetch. `og:image`
  /// wins over `twitter:image` when both are present.
  static func parseHTMLMetadata(_ html: String, baseURL: URL) -> (title: String?, imageURL: URL?)? {
    var ogImage: String?
    var twitterImage: String?
    var ogTitle: String?

    for tag in metaTagContents(in: html) {
      let attributes = parseAttributes(from: tag)
      guard let content = attributes[metaContentAttribute] else { continue }
      let property = attributes[metaPropertyAttribute] ?? attributes[metaNameAttribute]

      if ogImage == nil, property == ogImageProperty {
        ogImage = content
      } else if twitterImage == nil, property == twitterImageProperty {
        twitterImage = content
      } else if ogTitle == nil, property == ogTitleProperty {
        ogTitle = content
      }
    }

    let rawImageURLString: String? = (ogImage ?? twitterImage).map(unescapeHTMLEntities)
    let resolvedImageURL: URL? = rawImageURLString.flatMap { URL(string: $0, relativeTo: baseURL)?.absoluteURL }
    let imageURL: URL? = if let resolvedImageURL, resolvedImageURL.scheme == "http" || resolvedImageURL.scheme == "https" {
      resolvedImageURL
    } else {
      nil
    }

    let title = ogTitle.map(unescapeHTMLEntities)

    guard imageURL != nil || title != nil else { return nil }
    return (title: title, imageURL: imageURL)
  }

  /// Returns the raw text of every `<meta ...>` tag in `html`, in document order.
  private static func metaTagContents(in html: String) -> [String] {
    guard let regex = metaTagPattern else { return [] }
    let range = NSRange(html.startIndex..., in: html)
    return regex.matches(in: html, range: range).compactMap { match in
      Range(match.range, in: html).map { String(html[$0]) }
    }
  }

  /// Extracts `name="value"` / `name='value'` pairs from a single tag's
  /// text, independent of attribute order.
  private static func parseAttributes(from tag: String) -> [String: String] {
    guard let regex = metaAttributePattern else { return [:] }
    let range = NSRange(tag.startIndex..., in: tag)
    var attributes: [String: String] = [:]
    for match in regex.matches(in: tag, range: range) {
      guard let nameRange = Range(match.range(at: 1), in: tag) else { continue }
      let valueRange = Range(match.range(at: 2), in: tag) ?? Range(match.range(at: 3), in: tag)
      guard let valueRange else { continue }
      attributes[tag[nameRange].lowercased()] = String(tag[valueRange])
    }
    return attributes
  }

  private static func unescapeHTMLEntities(_ string: String) -> String {
    htmlEntities.reduce(string) { result, entity in
      result.replacingOccurrences(of: entity.0, with: entity.1)
    }
  }

  // MARK: - Image fetch

  /// Fetches the scraped `og:image` bytes, bounding both the download and
  /// the decode. Not private: exercised directly by tests through the
  /// injectable `scrapeSession` seam, since `fetchMetadata`'s
  /// `LPMetadataProvider` leg cannot be stubbed.
  func loadImageData(from url: URL) async -> Data? {
    guard let data = await boundedData(
      for: url,
      timeout: Self.imageFetchTimeout,
      byteCap: Self.imageByteCap,
      acceptsMime: { $0.hasPrefix(Self.imageMimePrefix) }
    ) else {
      return nil
    }

    return fitToMaxSize(Self.boundedDecode(data))
  }

  /// Decodes at a bounded pixel size via ImageIO rather than `UIImage(data:)`
  /// on raw network bytes, so a small file crafted to decompress into a huge
  /// bitmap cannot exhaust memory. Always re-encodes to JPEG, since the
  /// bound only applies going through the thumbnail path.
  private static func boundedDecode(_ data: Data) -> Data? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let options: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceThumbnailMaxPixelSize: scrapedImageMaxPixelSize,
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceShouldCacheImmediately: true
    ]
    guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
      return nil
    }
    return UIImage(cgImage: cgImage).jpegData(compressionQuality: scrapedImageJPEGQuality)
  }
}
