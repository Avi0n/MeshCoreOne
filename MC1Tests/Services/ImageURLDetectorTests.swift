import Foundation
@testable import MC1
@testable import MC1Services
import Testing

struct ImageURLDetectorTests {
  // MARK: - Direct Image URL Detection

  @Test(arguments: ["jpg", "jpeg", "png", "gif", "webp", "heic"])
  func `Detects common image extensions`(ext: String) throws {
    let url = try #require(URL(string: "https://example.com/photo.\(ext)"))
    #expect(ImageURLClassifier.isDirectImageURL(url), "Should detect .\(ext)")
  }

  @Test(arguments: ["html", "pdf", "mp4", "txt", "js", "css"])
  func `Rejects non-image extensions`(ext: String) throws {
    let url = try #require(URL(string: "https://example.com/file.\(ext)"))
    #expect(!ImageURLClassifier.isDirectImageURL(url), "Should reject .\(ext)")
  }

  @Test
  func `Case insensitive extension detection`() throws {
    let url = try #require(URL(string: "https://example.com/photo.JPG"))
    #expect(ImageURLClassifier.isDirectImageURL(url))

    let urlMixed = try #require(URL(string: "https://example.com/photo.Png"))
    #expect(ImageURLClassifier.isDirectImageURL(urlMixed))
  }

  @Test
  func `Handles URLs with query parameters`() throws {
    let url = try #require(URL(string: "https://example.com/photo.jpg?width=100&height=100"))
    #expect(ImageURLClassifier.isDirectImageURL(url))
  }

  @Test
  func `Rejects URL with no extension`() throws {
    let url = try #require(URL(string: "https://example.com/photo"))
    #expect(!ImageURLClassifier.isDirectImageURL(url))
  }

  @Test
  func `Rejects empty path`() throws {
    let url = try #require(URL(string: "https://example.com/"))
    #expect(!ImageURLClassifier.isDirectImageURL(url))
  }

  // MARK: - GIF Magic Byte Detection

  @Test
  func `Detects GIF87a magic bytes`() {
    let data = Data([0x47, 0x49, 0x46, 0x38, 0x37, 0x61]) // GIF87a
    #expect(ImageURLDetector.isGIFData(data))
  }

  @Test
  func `Detects GIF89a magic bytes`() {
    let data = Data([0x47, 0x49, 0x46, 0x38, 0x39, 0x61]) // GIF89a
    #expect(ImageURLDetector.isGIFData(data))
  }

  @Test
  func `Rejects non-GIF data`() {
    let pngData = Data([0x89, 0x50, 0x4E, 0x47]) // PNG header
    #expect(!ImageURLDetector.isGIFData(pngData))
  }

  @Test
  func `Rejects data shorter than 4 bytes`() {
    let data = Data([0x47, 0x49, 0x46]) // Only 3 bytes
    #expect(!ImageURLDetector.isGIFData(data))
  }

  @Test
  func `Rejects empty data`() {
    #expect(!ImageURLDetector.isGIFData(Data()))
  }

  // MARK: - Giphy URL Resolution

  @Test
  func `Resolves giphy.com/gifs/slug-text-ID`() throws {
    let url = try #require(URL(string: "https://giphy.com/gifs/meme-cute-penguin-UTYwlUGi5iiRHtqEgj"))
    let resolved = ImageURLClassifier.resolveImageURL(url)
    #expect(resolved?.absoluteString == "https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")
  }

  @Test
  func `Resolves giphy.com/gifs/ID (no slug)`() throws {
    let url = try #require(URL(string: "https://giphy.com/gifs/UTYwlUGi5iiRHtqEgj"))
    let resolved = ImageURLClassifier.resolveImageURL(url)
    #expect(resolved?.absoluteString == "https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")
  }

  @Test
  func `Resolves giphy.com/embed/ID`() throws {
    let url = try #require(URL(string: "https://giphy.com/embed/UTYwlUGi5iiRHtqEgj"))
    let resolved = ImageURLClassifier.resolveImageURL(url)
    #expect(resolved?.absoluteString == "https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")
  }

  @Test
  func `Recognizes media.giphy.com as direct image URL`() throws {
    let url = try #require(URL(string: "https://media.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif"))
    #expect(ImageURLClassifier.isDirectImageURL(url), "Should be detected as direct image URL via .gif extension")
  }

  @Test
  func `Recognizes i.giphy.com as direct image URL`() throws {
    let url = try #require(URL(string: "https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif"))
    #expect(ImageURLClassifier.isDirectImageURL(url), "Should be detected as direct image URL via .gif extension")
  }

  @Test
  func `Returns nil for non-Giphy URLs`() throws {
    let url = try #require(URL(string: "https://example.com/gifs/test-123"))
    #expect(ImageURLClassifier.resolveImageURL(url) == nil)
  }

  @Test
  func `Returns nil for Giphy URLs without valid path`() throws {
    let url = try #require(URL(string: "https://giphy.com/"))
    #expect(ImageURLClassifier.resolveImageURL(url) == nil)
  }

  @Test
  func `Resolves www.giphy.com URLs`() throws {
    let url = try #require(URL(string: "https://www.giphy.com/gifs/test-ID123"))
    let resolved = ImageURLClassifier.resolveImageURL(url)
    #expect(resolved?.absoluteString == "https://i.giphy.com/media/ID123/giphy.gif")
  }

  // MARK: - Composite Detection

  @Test
  func `isImageURL returns true for direct image URLs`() throws {
    let url = try #require(URL(string: "https://example.com/photo.png"))
    #expect(ImageURLClassifier.isImageURL(url))
  }

  @Test
  func `isImageURL returns true for resolvable Giphy URLs`() throws {
    let url = try #require(URL(string: "https://giphy.com/gifs/test-ABC123"))
    #expect(ImageURLClassifier.isImageURL(url))
  }

  @Test
  func `isImageURL returns false for non-image URLs`() throws {
    let url = try #require(URL(string: "https://example.com/page.html"))
    #expect(!ImageURLClassifier.isImageURL(url))
  }

  @Test
  func `directImageURL returns self for direct images`() throws {
    let url = try #require(URL(string: "https://example.com/photo.jpg"))
    #expect(ImageURLClassifier.directImageURL(for: url) == url)
  }

  @Test
  func `directImageURL resolves Giphy URLs`() throws {
    let url = try #require(URL(string: "https://giphy.com/gifs/funny-ABC123"))
    let resolved = ImageURLClassifier.directImageURL(for: url)
    #expect(resolved.absoluteString == "https://i.giphy.com/media/ABC123/giphy.gif")
  }
}
