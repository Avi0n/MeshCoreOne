@testable import MC1
import Testing

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
}
