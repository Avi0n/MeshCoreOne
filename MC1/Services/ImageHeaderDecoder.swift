import Foundation
import ImageIO

/// Shared image header decoder. Reads pixel width and height from an image
/// payload without decoding the full bitmap. Used by inline-image probes and
/// link-preview hero extraction.
enum ImageHeaderDecoder {

    /// Returns pixel width and height parsed from the image header in `data`.
    /// Returns `nil` if the bytes are not a recognized image or either
    /// dimension is missing or non-positive.
    static func decodeDimensions(from data: Data) -> (width: Int, height: Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(
                source,
                0,
                [kCGImageSourceShouldCache: false] as CFDictionary
              ) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int,
              let height = props[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0 else {
            return nil
        }
        return (width, height)
    }
}
