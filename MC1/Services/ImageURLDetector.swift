import Foundation
import ImageIO
import UIKit

/// Inline image decoding (UIKit/ImageIO). For URL classification, use
/// `MC1Services.ImageURLClassifier`.
enum ImageURLDetector {

    /// Max pixel dimension for inline image display (280pt × 3x scale)
    private static let inlineMaxPixelSize: CGFloat = 900

    /// Decodes an image at a reduced size using ImageIO, avoiding full-resolution decode.
    /// Falls back to `UIImage(data:)` if thumbnail generation fails.
    static func downsampledImage(from data: Data) -> UIImage? {
        let options: [CFString: Any] = [
            kCGImageSourceShouldCache: false
        ]
        guard let source = CGImageSourceCreateWithData(data as CFData, options as CFDictionary) else {
            return UIImage(data: data)
        }
        let downsampleOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: inlineMaxPixelSize,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, downsampleOptions as CFDictionary) else {
            return UIImage(data: data)
        }
        return UIImage(cgImage: cgImage)
    }

    /// Returns `true` if the data begins with the GIF magic bytes (`GIF8`)
    static func isGIFData(_ data: Data) -> Bool {
        guard data.count >= 4 else { return false }
        return data[data.startIndex] == 0x47     // G
            && data[data.startIndex + 1] == 0x49 // I
            && data[data.startIndex + 2] == 0x46 // F
            && data[data.startIndex + 3] == 0x38 // 8
    }

    /// Decodes GIF data into an animated UIImage using CGImageSource.
    /// Returns `nil` if the data is not valid GIF data.
    static func decodeGIFImage(from data: Data) -> UIImage? {
        guard isGIFData(data),
              let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }

        let count = CGImageSourceGetCount(source)
        guard count > 1 else {
            return downsampledImage(from: data)
        }

        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: inlineMaxPixelSize,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true
        ]

        var frames: [UIImage] = []
        var totalDuration: TimeInterval = 0

        for i in 0..<count {
            guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, i, thumbnailOptions as CFDictionary) else { continue }
            frames.append(UIImage(cgImage: cgImage))

            let properties = CGImageSourceCopyPropertiesAtIndex(source, i, nil) as? [CFString: Any]
            let gifDict = properties?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
            let delay = gifDict?[kCGImagePropertyGIFUnclampedDelayTime] as? Double
                ?? gifDict?[kCGImagePropertyGIFDelayTime] as? Double
                ?? 0.1
            totalDuration += max(delay, 0.02)
        }

        guard !frames.isEmpty else { return nil }
        return UIImage.animatedImage(with: frames, duration: totalDuration)
    }
}
