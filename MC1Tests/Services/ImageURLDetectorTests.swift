import Testing
import Foundation
@testable import MC1
@testable import MC1Services

struct ImageURLDetectorTests {

    // MARK: - Direct Image URL Detection

    @Test("Detects common image extensions", arguments: ["jpg", "jpeg", "png", "gif", "webp", "heic"])
    func detectsImageExtensions(ext: String) {
        let url = URL(string: "https://example.com/photo.\(ext)")!
        #expect(ImageURLClassifier.isDirectImageURL(url), "Should detect .\(ext)")
    }

    @Test("Rejects non-image extensions", arguments: ["html", "pdf", "mp4", "txt", "js", "css"])
    func rejectsNonImageExtensions(ext: String) {
        let url = URL(string: "https://example.com/file.\(ext)")!
        #expect(!ImageURLClassifier.isDirectImageURL(url), "Should reject .\(ext)")
    }

    @Test("Case insensitive extension detection")
    func caseInsensitiveExtension() {
        let url = URL(string: "https://example.com/photo.JPG")!
        #expect(ImageURLClassifier.isDirectImageURL(url))

        let urlMixed = URL(string: "https://example.com/photo.Png")!
        #expect(ImageURLClassifier.isDirectImageURL(urlMixed))
    }

    @Test("Handles URLs with query parameters")
    func urlWithQueryParameters() {
        let url = URL(string: "https://example.com/photo.jpg?width=100&height=100")!
        #expect(ImageURLClassifier.isDirectImageURL(url))
    }

    @Test("Rejects URL with no extension")
    func noExtension() {
        let url = URL(string: "https://example.com/photo")!
        #expect(!ImageURLClassifier.isDirectImageURL(url))
    }

    @Test("Rejects empty path")
    func emptyPath() {
        let url = URL(string: "https://example.com/")!
        #expect(!ImageURLClassifier.isDirectImageURL(url))
    }

    // MARK: - GIF Magic Byte Detection

    @Test("Detects GIF87a magic bytes")
    func detectsGIF87a() {
        let data = Data([0x47, 0x49, 0x46, 0x38, 0x37, 0x61]) // GIF87a
        #expect(ImageURLDetector.isGIFData(data))
    }

    @Test("Detects GIF89a magic bytes")
    func detectsGIF89a() {
        let data = Data([0x47, 0x49, 0x46, 0x38, 0x39, 0x61]) // GIF89a
        #expect(ImageURLDetector.isGIFData(data))
    }

    @Test("Rejects non-GIF data")
    func rejectsNonGIFData() {
        let pngData = Data([0x89, 0x50, 0x4E, 0x47]) // PNG header
        #expect(!ImageURLDetector.isGIFData(pngData))
    }

    @Test("Rejects data shorter than 4 bytes")
    func rejectsShortData() {
        let data = Data([0x47, 0x49, 0x46]) // Only 3 bytes
        #expect(!ImageURLDetector.isGIFData(data))
    }

    @Test("Rejects empty data")
    func rejectsEmptyData() {
        #expect(!ImageURLDetector.isGIFData(Data()))
    }

    // MARK: - Giphy URL Resolution

    @Test("Resolves giphy.com/gifs/slug-text-ID")
    func resolvesGiphySlugURL() {
        let url = URL(string: "https://giphy.com/gifs/meme-cute-penguin-UTYwlUGi5iiRHtqEgj")!
        let resolved = ImageURLClassifier.resolveImageURL(url)
        #expect(resolved?.absoluteString == "https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")
    }

    @Test("Resolves giphy.com/gifs/ID (no slug)")
    func resolvesGiphyIDOnly() {
        let url = URL(string: "https://giphy.com/gifs/UTYwlUGi5iiRHtqEgj")!
        let resolved = ImageURLClassifier.resolveImageURL(url)
        #expect(resolved?.absoluteString == "https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")
    }

    @Test("Resolves giphy.com/embed/ID")
    func resolvesGiphyEmbedURL() {
        let url = URL(string: "https://giphy.com/embed/UTYwlUGi5iiRHtqEgj")!
        let resolved = ImageURLClassifier.resolveImageURL(url)
        #expect(resolved?.absoluteString == "https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")
    }

    @Test("Recognizes media.giphy.com as direct image URL")
    func recognizesMediaGiphy() {
        let url = URL(string: "https://media.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")!
        #expect(ImageURLClassifier.isDirectImageURL(url), "Should be detected as direct image URL via .gif extension")
    }

    @Test("Recognizes i.giphy.com as direct image URL")
    func recognizesIGiphy() {
        let url = URL(string: "https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")!
        #expect(ImageURLClassifier.isDirectImageURL(url), "Should be detected as direct image URL via .gif extension")
    }

    @Test("Returns nil for non-Giphy URLs")
    func returnsNilForNonGiphy() {
        let url = URL(string: "https://example.com/gifs/test-123")!
        #expect(ImageURLClassifier.resolveImageURL(url) == nil)
    }

    @Test("Returns nil for Giphy URLs without valid path")
    func returnsNilForInvalidGiphyPath() {
        let url = URL(string: "https://giphy.com/")!
        #expect(ImageURLClassifier.resolveImageURL(url) == nil)
    }

    @Test("Resolves www.giphy.com URLs")
    func resolvesWWWGiphy() {
        let url = URL(string: "https://www.giphy.com/gifs/test-ID123")!
        let resolved = ImageURLClassifier.resolveImageURL(url)
        #expect(resolved?.absoluteString == "https://i.giphy.com/media/ID123/giphy.gif")
    }

    // MARK: - Composite Detection

    @Test("isImageURL returns true for direct image URLs")
    func isImageURLDirectImage() {
        let url = URL(string: "https://example.com/photo.png")!
        #expect(ImageURLClassifier.isImageURL(url))
    }

    @Test("isImageURL returns true for resolvable Giphy URLs")
    func isImageURLGiphy() {
        let url = URL(string: "https://giphy.com/gifs/test-ABC123")!
        #expect(ImageURLClassifier.isImageURL(url))
    }

    @Test("isImageURL returns false for non-image URLs")
    func isImageURLNonImage() {
        let url = URL(string: "https://example.com/page.html")!
        #expect(!ImageURLClassifier.isImageURL(url))
    }

    @Test("directImageURL returns self for direct images")
    func directImageURLSelf() {
        let url = URL(string: "https://example.com/photo.jpg")!
        #expect(ImageURLClassifier.directImageURL(for: url) == url)
    }

    @Test("directImageURL resolves Giphy URLs")
    func directImageURLResolvesGiphy() {
        let url = URL(string: "https://giphy.com/gifs/funny-ABC123")!
        let resolved = ImageURLClassifier.directImageURL(for: url)
        #expect(resolved.absoluteString == "https://i.giphy.com/media/ABC123/giphy.gif")
    }
}
