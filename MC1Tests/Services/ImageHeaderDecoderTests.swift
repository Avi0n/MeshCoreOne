import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import MC1

@Suite("ImageHeaderDecoder Tests")
struct ImageHeaderDecoderTests {

    /// Creates an in-memory PNG of the requested pixel dimensions.
    private static func makePNG(width: Int, height: Int) -> Data? {
        let bytesPerRow = width * 4
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        context.setFillColor(CGColor(red: 0, green: 1, blue: 0, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        guard let image = context.makeImage() else { return nil }

        let mutable = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            mutable,
            UTType.png.identifier as CFString,
            1,
            nil
        ) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return mutable as Data
    }

    @Test("decodeDimensions returns pixel dims for a valid PNG")
    func decodeReturnsDimsForValidPNG() throws {
        let png = try #require(Self.makePNG(width: 480, height: 270))
        let dims = ImageHeaderDecoder.decodeDimensions(from: png)
        #expect(dims?.width == 480)
        #expect(dims?.height == 270)
    }

    @Test("decodeDimensions returns nil for non-image bytes")
    func decodeReturnsNilForCorruptData() {
        let dims = ImageHeaderDecoder.decodeDimensions(from: Data("not an image".utf8))
        #expect(dims == nil)
    }

    @Test("decodeDimensions returns nil for empty data")
    func decodeReturnsNilForEmptyData() {
        let dims = ImageHeaderDecoder.decodeDimensions(from: Data())
        #expect(dims == nil)
    }
}
