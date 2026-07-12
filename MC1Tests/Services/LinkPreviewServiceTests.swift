import Foundation
@testable import MC1
import Testing
import UIKit

@Suite("LinkPreviewService Tests")
@MainActor
struct LinkPreviewServiceTests {
  @Test
  func `Extracts HTTPS URL from text`() {
    let text = "Check out https://example.com/article for more info"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.absoluteString == "https://example.com/article")
  }

  @Test
  func `Extracts HTTP URL from text`() {
    let text = "Visit http://example.com"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.scheme == "http")
  }

  @Test
  func `Returns nil for text without URLs`() {
    let text = "Just some plain text without links"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url == nil)
  }

  @Test
  func `Extracts first URL when multiple URLs present`() {
    let text = "First https://first.com then https://second.com"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.host == "first.com")
  }

  @Test
  func `Ignores non-HTTP schemes like tel: and mailto:`() {
    let text = "Call me at tel:+1234567890 or mailto:test@example.com"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url == nil)
  }

  @Test
  func `Extracts URL with path and query string`() {
    let text = "Read https://example.com/blog/2024/article-title?ref=social"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.path == "/blog/2024/article-title")
    #expect(url?.query == "ref=social")
  }

  @Test
  func `Extracts URL at beginning of text`() {
    let text = "https://example.com is a great site"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.absoluteString == "https://example.com")
  }

  @Test
  func `Extracts URL at end of text`() {
    let text = "Check this out: https://example.com"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.absoluteString == "https://example.com")
  }

  @Test
  func `Returns nil for empty text`() {
    let text = ""
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url == nil)
  }

  @Test
  func `Handles URL with fragment`() {
    let text = "See https://example.com/page#section"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.fragment == "section")
  }

  // MARK: - URL in Mention Tests

  @Test
  func `Ignores URL-like text within mention brackets`() {
    let text = "Hey @[Ferret PocketMesh WCMesh.com], check this out!"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url == nil, "WCMesh.com within @[] should not be extracted as a URL")
  }

  @Test
  func `Ignores domain-like text within mention brackets`() {
    let text = "@[Server node.example.com] says hello"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url == nil, "node.example.com within @[] should not be extracted")
  }

  @Test
  func `Extracts real URL when mention also contains URL-like text`() {
    let text = "@[Server node.example.com] says check https://docs.example.com"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.absoluteString == "https://docs.example.com")
  }

  @Test
  func `Extracts URL when no mentions present`() {
    let text = "Just a normal message with https://example.com link"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.absoluteString == "https://example.com")
  }

  @Test
  func `Returns nil when only URL-like text in mention`() {
    let text = "Message from @[192.168.1.100]"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url == nil, "IP address in mention should not be extracted")
  }

  // MARK: - Meshcore-open GIF Format Tests

  @Test
  func `Extracts Giphy URL from g: prefix message`() {
    let text = "g:JgWZYoIgjzsIQO8joZ"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.absoluteString == "https://media.giphy.com/media/JgWZYoIgjzsIQO8joZ/giphy.gif")
  }

  @Test
  func `Extracts Giphy URL from g: with whitespace`() {
    let text = "  g:ABC123xyz  "
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.absoluteString == "https://media.giphy.com/media/ABC123xyz/giphy.gif")
  }

  @Test
  func `Handles g: with hyphens and underscores in ID`() {
    let text = "g:my-gif_ID-123"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url?.absoluteString == "https://media.giphy.com/media/my-gif_ID-123/giphy.gif")
  }

  @Test
  func `Returns nil for g: with no ID`() {
    let text = "g:"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url == nil)
  }

  @Test
  func `Does not match g: embedded in longer text`() {
    let text = "Check out g:ABC123 please"
    let url = LinkPreviewService.extractFirstURL(from: text)
    // Should not match because wholeMatch requires entire string
    #expect(url == nil)
  }

  @Test
  func `Does not match g: with invalid characters in ID`() {
    let text = "g:ABC 123"
    let url = LinkPreviewService.extractFirstURL(from: text)
    #expect(url == nil)
  }

  @Test
  func `extractGiphyGIFURL returns nil for plain text`() {
    #expect(LinkPreviewService.extractGiphyGIFURL(from: "hello world") == nil)
  }

  @Test
  func `extractGiphyGIFURL returns nil for regular URL`() {
    #expect(LinkPreviewService.extractGiphyGIFURL(from: "https://example.com") == nil)
  }

  // MARK: - parseHTMLMetadata (og:image scrape)

  @Test
  func `Parses a pasteboard-style head for its og image`() throws {
    let html = """
    <head>
    <meta property="og:title" content="Pasteboard">
    <meta property="og:image" content="https://gcdnb.pbrd.co/images/lZtwvIBHoO7L.jpg">
    </head>
    """
    let baseURL = try #require(URL(string: "https://pasteboard.co/lZtwvIBHoO7L.jpg"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result?.imageURL?.absoluteString == "https://gcdnb.pbrd.co/images/lZtwvIBHoO7L.jpg")
  }

  @Test
  func `Prefers og image over twitter image when both are present`() throws {
    let html = """
    <head>
    <meta property="og:title" content="Imgur gallery">
    <meta property="og:image" content="https://i.imgur.com/rmwz8FJ.jpeg?fb">
    <meta name="twitter:image" content="https://i.imgur.com/different.jpeg">
    </head>
    """
    let baseURL = try #require(URL(string: "https://imgur.com/gallery/abc123"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result?.imageURL?.absoluteString == "https://i.imgur.com/rmwz8FJ.jpeg?fb")
  }

  @Test
  func `Handles content attribute before property attribute`() throws {
    let html = """
    <meta content="https://example.com/hero.jpg" property="og:image">
    """
    let baseURL = try #require(URL(string: "https://example.com/page"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result?.imageURL?.absoluteString == "https://example.com/hero.jpg")
  }

  @Test
  func `Falls back to twitter image when no og image is present`() throws {
    let html = """
    <meta name="twitter:image" content="https://example.com/twitter-hero.jpg">
    """
    let baseURL = try #require(URL(string: "https://example.com/page"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result?.imageURL?.absoluteString == "https://example.com/twitter-hero.jpg")
  }

  @Test
  func `Resolves a relative og image URL against the page URL`() throws {
    let html = """
    <meta property="og:image" content="/images/hero.jpg">
    """
    let baseURL = try #require(URL(string: "https://example.com/page"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result?.imageURL?.absoluteString == "https://example.com/images/hero.jpg")
  }

  @Test
  func `Unescapes HTML entities in a multi param og image URL`() throws {
    let html = """
    <meta property="og:image" content="https://example.com/img.jpg?a=1&amp;b=2">
    """
    let baseURL = try #require(URL(string: "https://example.com/page"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result?.imageURL?.absoluteString == "https://example.com/img.jpg?a=1&b=2")
  }

  @Test
  func `Returns nil when the page has no Open Graph or Twitter Card tags`() throws {
    let html = "<head><meta charset=\"utf-8\"><title>No OG tags</title></head>"
    let baseURL = try #require(URL(string: "https://example.com/page"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result == nil)
  }

  @Test
  func `Extracts the og title alongside the og image`() throws {
    let html = """
    <head>
    <meta property="og:title" content="Ben &amp; Jerry">
    <meta property="og:image" content="https://example.com/hero.jpg">
    </head>
    """
    let baseURL = try #require(URL(string: "https://example.com/page"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result?.title == "Ben & Jerry")
    #expect(result?.imageURL?.absoluteString == "https://example.com/hero.jpg")
  }

  @Test
  func `Returns a title-only result when the page has og title but no image`() throws {
    let html = """
    <meta property="og:title" content="Title only page">
    """
    let baseURL = try #require(URL(string: "https://example.com/page"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result?.title == "Title only page")
    #expect(result?.imageURL == nil)
  }

  @Test
  func `Drops a non-HTTP og image URL`() throws {
    let html = """
    <meta property="og:image" content="file:///etc/passwd">
    """
    let baseURL = try #require(URL(string: "https://example.com/page"))
    let result = LinkPreviewService.parseHTMLMetadata(html, baseURL: baseURL)
    #expect(result == nil)
  }

  // MARK: - scrapeHTMLMetadata (URLProtocol-stubbed)

  @Test
  func `scrapeHTMLMetadata parses og tags from a stubbed HTML page`() async throws {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [HTMLPageURLProtocol.self]
    let session = URLSession(configuration: config)
    let service = LinkPreviewService(scrapeSession: session)

    let url = try #require(URL(string: "https://example.com/page"))
    let result = await service.scrapeHTMLMetadata(for: url)
    #expect(result?.title == "Stubbed page")
    #expect(result?.imageURL?.absoluteString == "https://example.com/hero.jpg")
  }

  @Test
  func `scrapeHTMLMetadata rejects a non-HTML mime type`() async throws {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [NonImageMimeURLProtocol.self]
    let session = URLSession(configuration: config)
    let service = LinkPreviewService(scrapeSession: session)

    let url = try #require(URL(string: "https://example.com/page"))
    let result = await service.scrapeHTMLMetadata(for: url)
    #expect(result == nil)
  }

  // MARK: - loadImageData orchestration (URLProtocol-stubbed)

  @Test
  func `loadImageData rejects a redirect to a private host`() async throws {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [RedirectToPrivateHostURLProtocol.self]
    let session = URLSession(configuration: config, delegate: RedirectSafetyDelegate(), delegateQueue: nil)
    let service = LinkPreviewService(scrapeSession: session)

    let url = try #require(URL(string: "https://example.com/photo.jpg"))
    let data = await service.loadImageData(from: url)
    #expect(data == nil)
  }

  @Test
  func `loadImageData rejects an oversized expected content length`() async throws {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [OversizedImageURLProtocol.self]
    let session = URLSession(configuration: config)
    let service = LinkPreviewService(scrapeSession: session)

    let url = try #require(URL(string: "https://example.com/huge.jpg"))
    let data = await service.loadImageData(from: url)
    #expect(data == nil)
  }

  @Test
  func `loadImageData rejects a non image mime type`() async throws {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [NonImageMimeURLProtocol.self]
    let session = URLSession(configuration: config)
    let service = LinkPreviewService(scrapeSession: session)

    let url = try #require(URL(string: "https://example.com/not-an-image.jpg"))
    let data = await service.loadImageData(from: url)
    #expect(data == nil)
  }

  @Test
  func `loadImageData returns decoded data for a valid image`() async throws {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [ValidImageURLProtocol.self]
    let session = URLSession(configuration: config)
    let service = LinkPreviewService(scrapeSession: session)

    let url = try #require(URL(string: "https://example.com/photo.jpg"))
    let data = await service.loadImageData(from: url)
    let image = try #require(data.flatMap(UIImage.init(data:)))
    #expect(image.size.width > 0)
  }
}

/// A real, decodable JPEG produced by the platform encoder, so tests that
/// assert a fetch was refused can't pass merely because the payload was
/// undecodable, and positive controls have genuine image bytes to decode.
private let jpegFixture: Data = {
  let size = CGSize(width: 4, height: 4)
  let image = UIGraphicsImageRenderer(size: size).image { context in
    UIColor.red.setFill()
    context.fill(CGRect(origin: .zero, size: size))
  }
  return image.jpegData(compressionQuality: 1.0)!
}()

/// Redirects the initial request to a loopback-literal target via the real
/// URL Loading System redirect flow (`wasRedirectedTo:redirectResponse:`),
/// so a test using the production `RedirectSafetyDelegate` proves the
/// redirect hop is refused rather than followed. If the redirect were
/// followed despite being unsafe, the private-host leg would answer with a
/// valid image and the test would fail.
private final class RedirectToPrivateHostURLProtocol: URLProtocol {
  static let privateTarget = "http://127.0.0.1/secret.jpg"

  // swiftlint:disable:next static_over_final_class
  override class func canInit(with request: URLRequest) -> Bool {
    true
  }

  // swiftlint:disable:next static_over_final_class
  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    guard let url = request.url else { return }

    if url.host == "127.0.0.1" {
      let response = HTTPURLResponse(
        url: url,
        statusCode: 200,
        httpVersion: "HTTP/1.1",
        headerFields: ["Content-Type": "image/jpeg"]
      )!
      client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
      // A real decodable JPEG: if the redirect guard failed and this leg
      // were fetched, loadImageData would return data and the test would
      // fail, rather than passing because the payload couldn't decode.
      client?.urlProtocol(self, didLoad: jpegFixture)
      client?.urlProtocolDidFinishLoading(self)
      return
    }

    let redirectRequest = URLRequest(url: URL(string: Self.privateTarget)!)
    let redirectResponse = HTTPURLResponse(
      url: url,
      statusCode: 302,
      httpVersion: "HTTP/1.1",
      headerFields: ["Location": Self.privateTarget]
    )!
    // Deliver the 302 through the normal response path too: if the
    // delegate declines the redirect, the loading system needs an already
    // "finished" load to resolve the task promptly with the redirect
    // response, rather than sitting until the request timeout elapses.
    client?.urlProtocol(self, wasRedirectedTo: redirectRequest, redirectResponse: redirectResponse)
    client?.urlProtocol(self, didReceive: redirectResponse, cacheStoragePolicy: .notAllowed)
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

/// Answers with a `Content-Length` above `imageByteCap` so the pre-download
/// size guard rejects the fetch before any body bytes are streamed.
private final class OversizedImageURLProtocol: URLProtocol {
  private static let oversizedByteCount = 3 * 1024 * 1024

  // swiftlint:disable:next static_over_final_class
  override class func canInit(with request: URLRequest) -> Bool {
    true
  }

  // swiftlint:disable:next static_over_final_class
  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    guard let url = request.url else { return }
    let response = HTTPURLResponse(
      url: url,
      statusCode: 200,
      httpVersion: "HTTP/1.1",
      headerFields: [
        "Content-Type": "image/jpeg",
        "Content-Length": "\(Self.oversizedByteCount)"
      ]
    )!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: jpegFixture)
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

/// Serves a small, genuinely decodable JPEG so the happy path proves
/// `loadImageData` actually returns image data when nothing is wrong.
private final class ValidImageURLProtocol: URLProtocol {
  // swiftlint:disable:next static_over_final_class
  override class func canInit(with request: URLRequest) -> Bool {
    true
  }

  // swiftlint:disable:next static_over_final_class
  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    guard let url = request.url else { return }
    let response = HTTPURLResponse(
      url: url,
      statusCode: 200,
      httpVersion: "HTTP/1.1",
      headerFields: ["Content-Type": "image/jpeg"]
    )!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: jpegFixture)
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

/// Serves a small HTML page carrying `og:title` and `og:image` tags for the
/// `scrapeHTMLMetadata` network-path tests.
private final class HTMLPageURLProtocol: URLProtocol {
  static let html = """
  <head>
  <meta property="og:title" content="Stubbed page">
  <meta property="og:image" content="https://example.com/hero.jpg">
  </head>
  """

  // swiftlint:disable:next static_over_final_class
  override class func canInit(with request: URLRequest) -> Bool {
    true
  }

  // swiftlint:disable:next static_over_final_class
  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    guard let url = request.url else { return }
    let response = HTTPURLResponse(
      url: url,
      statusCode: 200,
      httpVersion: "HTTP/1.1",
      headerFields: ["Content-Type": "text/html; charset=utf-8"]
    )!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: Data(Self.html.utf8))
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

/// Answers a `.jpg`-path request with a non-image body, standing in for an
/// og:image URL that doesn't actually serve image bytes.
private final class NonImageMimeURLProtocol: URLProtocol {
  // swiftlint:disable:next static_over_final_class
  override class func canInit(with request: URLRequest) -> Bool {
    true
  }

  // swiftlint:disable:next static_over_final_class
  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    guard let url = request.url else { return }
    let response = HTTPURLResponse(
      url: url,
      statusCode: 200,
      httpVersion: "HTTP/1.1",
      headerFields: ["Content-Type": "text/plain"]
    )!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: Data("nope".utf8))
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}
