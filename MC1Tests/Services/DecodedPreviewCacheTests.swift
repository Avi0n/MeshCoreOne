import Foundation
@testable import MC1
@testable import MC1Services
import Testing
import UIKit

@Suite("DecodedPreviewCache Tests")
struct DecodedPreviewCacheTests {
  @Test
  func `Returns nil for an unseen URL`() {
    let cache = DecodedPreviewCache()
    #expect(cache.decoded(for: uniqueURL()) == nil)
  }

  @Test
  @MainActor
  func `Round-trips a stored entry with DTO, hero, and icon`() {
    let cache = DecodedPreviewCache()
    let url = uniqueURL()
    let dto = Self.makeDTO(url: url.absoluteString)
    let hero = Self.makeImage()
    let icon = Self.makeImage()
    cache.store(CachedDecodedPreview(dto: dto, hero: hero, icon: icon), for: url)

    let result = cache.decoded(for: url)
    #expect(result?.dto.url == dto.url)
    #expect(result?.dto.title == dto.title)
    #expect(result?.hero === hero)
    #expect(result?.icon === icon)
  }

  @Test
  @MainActor
  func `Tolerates a nil hero or icon`() {
    let cache = DecodedPreviewCache()
    let url = uniqueURL()
    let dto = Self.makeDTO(url: url.absoluteString)
    cache.store(CachedDecodedPreview(dto: dto, hero: nil, icon: Self.makeImage()), for: url)

    let result = cache.decoded(for: url)
    #expect(result?.hero == nil)
    #expect(result?.icon != nil)
  }

  @Test
  @MainActor
  func `Re-storing the same key replaces the entry`() {
    let cache = DecodedPreviewCache()
    let url = uniqueURL()
    let first = CachedDecodedPreview(dto: Self.makeDTO(url: url.absoluteString, title: "first"), hero: Self.makeImage(), icon: nil)
    let second = CachedDecodedPreview(dto: Self.makeDTO(url: url.absoluteString, title: "second"), hero: Self.makeImage(), icon: nil)

    cache.store(first, for: url)
    cache.store(second, for: url)

    let result = cache.decoded(for: url)
    #expect(result?.hero === second.hero)
    #expect(result?.dto.title == "second")
  }

  @Test
  @MainActor
  func `Strips raw image and icon bytes from the cached DTO`() {
    let dto = LinkPreviewDataDTO(
      url: "https://example.invalid/strip",
      title: "Title",
      imageData: Data([0x01, 0x02, 0x03]),
      iconData: Data([0x04, 0x05]),
      imageWidth: 100,
      imageHeight: 50
    )
    let entry = CachedDecodedPreview(dto: dto, hero: Self.makeImage(), icon: Self.makeImage())

    #expect(entry.dto.imageData == nil)
    #expect(entry.dto.iconData == nil)
    // Metadata the rehydration render path reads is preserved.
    #expect(entry.dto.url == dto.url)
    #expect(entry.dto.title == dto.title)
    #expect(entry.dto.imageWidth == 100)
    #expect(entry.dto.imageHeight == 50)
  }

  @Test
  @MainActor
  func `Cost reflects decoded pixel bytes`() {
    let dto = Self.makeDTO(url: "https://example.invalid/cost")
    let entry = CachedDecodedPreview(dto: dto, hero: Self.makeImage(width: 100, height: 50), icon: nil)
    // 100x50 RGBA bitmap is 100 * 50 * 4 = 20_000 bytes minimum;
    // bytesPerRow may be padded for alignment, so assert a lower bound.
    #expect(entry.cost >= 20000)
  }

  @Test
  @MainActor
  func `clear() empties the cache`() {
    let cache = DecodedPreviewCache()
    let url = uniqueURL()
    cache.store(CachedDecodedPreview(dto: Self.makeDTO(url: url.absoluteString), hero: Self.makeImage(), icon: nil), for: url)
    #expect(cache.decoded(for: url) != nil)

    cache.clear()
    #expect(cache.decoded(for: url) == nil)
  }

  @Test
  @MainActor
  func `FIFO eviction drops the oldest entry past the count cap`() throws {
    let cache = DecodedPreviewCache()
    // maxEntryCount is 50; store 60 tiny entries so the first 10 evict.
    var urls: [URL] = []
    for _ in 0..<60 {
      let url = uniqueURL()
      urls.append(url)
      cache.store(CachedDecodedPreview(dto: Self.makeDTO(url: url.absoluteString), hero: Self.makeImage(), icon: nil), for: url)
    }
    #expect(try cache.decoded(for: #require(urls.first)) == nil)
    #expect(try cache.decoded(for: #require(urls.last)) != nil)
  }

  // MARK: - Helpers

  private func uniqueURL() -> URL {
    URL(string: "https://example.invalid/\(UUID().uuidString)")!
  }

  private static func makeDTO(url: String, title: String? = "Title") -> LinkPreviewDataDTO {
    LinkPreviewDataDTO(url: url, title: title, imageWidth: 100, imageHeight: 50)
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
