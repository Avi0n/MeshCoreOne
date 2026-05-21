import Foundation
import Testing
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
}
