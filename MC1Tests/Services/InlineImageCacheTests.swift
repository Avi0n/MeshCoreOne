import Foundation
@testable import MC1
import Testing
import UIKit

@Suite("InlineImageCache Tests")
struct InlineImageCacheTests {
  // MARK: - Safety gate

  @Test
  func `Probe returns nil for a private-IP host`() async throws {
    let url = try #require(URL(string: "http://127.0.0.1/test.png"))
    let result = await InlineImageCache.shared.probeImageDimensions(url: url)
    #expect(result == nil)
  }

  @Test
  func `Probe returns nil for a non-HTTP scheme`() async throws {
    let url = try #require(URL(string: "ftp://example.com/test.png"))
    let result = await InlineImageCache.shared.probeImageDimensions(url: url)
    #expect(result == nil)
  }

  // MARK: - HTML reroute

  @Test
  func `An image URL that serves HTML returns notImage and stays retryable`() async {
    let cache = InlineImageCache(session: Self.stubbedSession())
    let url = Self.htmlServingURL()

    let first = await cache.fetchImageData(for: url)
    #expect(Self.isNotImage(first))

    // A reroute must not poison the negative cache: re-fetching runs the
    // network path again rather than short-circuiting to .failed, so a chat
    // re-entry can still discover the page and load its og:image.
    let second = await cache.fetchImageData(for: url)
    #expect(Self.isNotImage(second))
  }

  @Test
  func `An oversized HTML page still returns notImage instead of tripping the size guard`() async {
    let cache = InlineImageCache(session: Self.stubbedSession())
    let url = Self.htmlServingURL(oversized: true)

    // The mime-type check must precede the download-size guard, so a page
    // larger than the image cap reroutes rather than dead-ending as .failed.
    let result = await cache.fetchImageData(for: url)
    #expect(Self.isNotImage(result))
  }

  // MARK: - Decoded cache

  @Test
  func `Decoded cache returns nil for an unseen URL`() {
    let url = uniqueURL()
    #expect(InlineImageCache.shared.decoded(for: url) == nil)
  }

  @Test
  @MainActor
  func `Decoded cache round-trips a stored entry with raw bytes`() {
    let url = uniqueURL()
    let image = Self.makeImage()
    let bytes = Data([0xFF, 0xD8, 0xFF, 0xE0])
    let entry = CachedDecodedImage(image: image, isGIF: false, data: bytes)
    InlineImageCache.shared.storeDecoded(entry, for: url)

    let result = InlineImageCache.shared.decoded(for: url)
    #expect(result?.image === image)
    #expect(result?.isGIF == false)
    #expect(result?.data == bytes)
  }

  @Test
  @MainActor
  func `Decoded cache preserves the GIF flag and omits bytes`() {
    let url = uniqueURL()
    let image = Self.makeImage()
    let entry = CachedDecodedImage(image: image, isGIF: true, data: nil)
    InlineImageCache.shared.storeDecoded(entry, for: url)

    let result = InlineImageCache.shared.decoded(for: url)
    #expect(result?.isGIF == true)
    #expect(result?.data == nil)
  }

  @Test
  @MainActor
  func `Re-storing the same key replaces the entry without growing the cache`() {
    let url = uniqueURL()
    let first = CachedDecodedImage(image: Self.makeImage(), isGIF: false, data: Data([0x01]))
    let second = CachedDecodedImage(image: Self.makeImage(), isGIF: true, data: nil)

    InlineImageCache.shared.storeDecoded(first, for: url)
    InlineImageCache.shared.storeDecoded(second, for: url)

    let result = InlineImageCache.shared.decoded(for: url)
    #expect(result?.image === second.image)
    #expect(result?.isGIF == true)
    #expect(result?.data == nil)
  }

  @Test
  @MainActor
  func `Decoded cost reflects pixel size plus raw bytes`() {
    let image = Self.makeImage(width: 100, height: 50)
    let bytes = Data(repeating: 0, count: 1000)
    let entry = CachedDecodedImage(image: image, isGIF: false, data: bytes)
    // 100x50 RGBA bitmap is 100 * 50 * 4 = 20_000 bytes minimum, plus
    // 1_000 raw bytes. CGImage.bytesPerRow may be padded for alignment,
    // so we assert a sane lower bound rather than an exact value.
    #expect(entry.cost >= 21000)
  }

  // MARK: - Helpers

  private func uniqueURL() -> URL {
    URL(string: "https://example.invalid/\(UUID().uuidString).png")!
  }

  /// An image-extension URL on an allow-listed CDN host, so the fetch's SSRF
  /// gate passes without a real DNS lookup and the stubbed session answers.
  private static func htmlServingURL(oversized: Bool = false) -> URL {
    let marker = oversized ? "-\(HTMLResponseURLProtocol.oversizedMarker)" : ""
    return URL(string: "https://media.giphy.com/\(UUID().uuidString)\(marker).jpg")!
  }

  private static func stubbedSession() -> URLSession {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [HTMLResponseURLProtocol.self]
    return URLSession(configuration: config)
  }

  private static func isNotImage(_ result: InlineImageResult) -> Bool {
    if case .notImage = result { return true }
    return false
  }

  @MainActor
  private static func makeImage(width: Int = 1, height: Int = 1) -> UIImage {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
    return renderer.image { ctx in
      UIColor.black.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    }
  }
}

/// Answers any request with a 200 `text/html` response, standing in for an
/// image-extension URL that actually serves a landing page. A URL carrying
/// `oversizedMarker` returns a body larger than the fetch's 10MB image cap,
/// exercising that the HTML reclassification precedes the size guard.
private final class HTMLResponseURLProtocol: URLProtocol {
  static let oversizedMarker = "oversized"

  private static let htmlBody = Data("<html><body>landing page</body></html>".utf8)
  private static let oversizedByteCount = 11 * 1024 * 1024

  // swiftlint:disable:next static_over_final_class
  override class func canInit(with request: URLRequest) -> Bool {
    true
  }

  // swiftlint:disable:next static_over_final_class
  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    guard let url = request.url,
          let response = HTTPURLResponse(
            url: url,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/html; charset=utf-8"]
          ) else {
      client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
      return
    }
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    let body = url.absoluteString.contains(Self.oversizedMarker)
      ? Data(count: Self.oversizedByteCount)
      : Self.htmlBody
    client?.urlProtocol(self, didLoad: body)
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}
