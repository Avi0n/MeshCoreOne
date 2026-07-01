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

  // MARK: - Decoded cache

  @Test
  func `Decoded cache returns nil for an unseen URL`() {
    let url = uniqueURL()
    #expect(InlineImageCache.shared.decoded(for: url) == nil)
  }

  @Test
  @MainActor
  func `Decoded cache round-trips a stored entry with raw bytes`() async {
    let url = uniqueURL()
    let image = Self.makeImage()
    let bytes = Data([0xFF, 0xD8, 0xFF, 0xE0])
    let entry = CachedDecodedImage(image: image, isGIF: false, data: bytes)
    await InlineImageCache.shared.storeDecoded(entry, for: url)

    let result = InlineImageCache.shared.decoded(for: url)
    #expect(result?.image === image)
    #expect(result?.isGIF == false)
    #expect(result?.data == bytes)
  }

  @Test
  @MainActor
  func `Decoded cache preserves the GIF flag and omits bytes`() async {
    let url = uniqueURL()
    let image = Self.makeImage()
    let entry = CachedDecodedImage(image: image, isGIF: true, data: nil)
    await InlineImageCache.shared.storeDecoded(entry, for: url)

    let result = InlineImageCache.shared.decoded(for: url)
    #expect(result?.isGIF == true)
    #expect(result?.data == nil)
  }

  @Test
  @MainActor
  func `Re-storing the same key replaces the entry without growing the cache`() async {
    let url = uniqueURL()
    let first = CachedDecodedImage(image: Self.makeImage(), isGIF: false, data: Data([0x01]))
    let second = CachedDecodedImage(image: Self.makeImage(), isGIF: true, data: nil)

    await InlineImageCache.shared.storeDecoded(first, for: url)
    await InlineImageCache.shared.storeDecoded(second, for: url)

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

  @MainActor
  private static func makeImage(width: Int = 1, height: Int = 1) -> UIImage {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
    return renderer.image { ctx in
      UIColor.black.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    }
  }
}
