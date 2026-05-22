import Foundation
import Testing
import UIKit
@testable import MC1

@Suite("InlineImageCache Tests")
struct InlineImageCacheTests {

    // MARK: - Safety gate

    @Test("Probe returns nil for a private-IP host")
    func probeRejectsPrivateIP() async {
        let url = URL(string: "http://127.0.0.1/test.png")!
        let result = await InlineImageCache.shared.probeImageDimensions(url: url)
        #expect(result == nil)
    }

    @Test("Probe returns nil for a non-HTTP scheme")
    func probeRejectsNonHTTPScheme() async {
        let url = URL(string: "ftp://example.com/test.png")!
        let result = await InlineImageCache.shared.probeImageDimensions(url: url)
        #expect(result == nil)
    }

    // MARK: - Decoded cache

    @Test("Decoded cache returns nil for an unseen URL")
    func decodedReturnsNilForMissingKey() async {
        let url = uniqueURL()
        #expect(InlineImageCache.shared.decoded(for: url) == nil)
    }

    @Test("Decoded cache round-trips a stored entry with raw bytes")
    @MainActor
    func decodedRoundTrip() async {
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

    @Test("Decoded cache preserves the GIF flag and omits bytes")
    @MainActor
    func decodedPreservesIsGIF() async {
        let url = uniqueURL()
        let image = Self.makeImage()
        let entry = CachedDecodedImage(image: image, isGIF: true, data: nil)
        await InlineImageCache.shared.storeDecoded(entry, for: url)

        let result = InlineImageCache.shared.decoded(for: url)
        #expect(result?.isGIF == true)
        #expect(result?.data == nil)
    }

    @Test("Re-storing the same key replaces the entry without growing the cache")
    @MainActor
    func decodedReplaceSameKey() async {
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

    @Test("Decoded cost reflects pixel size plus raw bytes")
    @MainActor
    func decodedCostIsPixelBytes() {
        let image = Self.makeImage(width: 100, height: 50)
        let bytes = Data(repeating: 0, count: 1_000)
        let entry = CachedDecodedImage(image: image, isGIF: false, data: bytes)
        // 100x50 RGBA bitmap is 100 * 50 * 4 = 20_000 bytes minimum, plus
        // 1_000 raw bytes. CGImage.bytesPerRow may be padded for alignment,
        // so we assert a sane lower bound rather than an exact value.
        #expect(entry.cost >= 21_000)
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
