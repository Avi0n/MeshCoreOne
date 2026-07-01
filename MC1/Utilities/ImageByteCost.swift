import UIKit

/// Single source of truth for the pixel-byte cost of a decoded `UIImage`, used
/// by the image caches (`DecodedPreviewCache`, `InlineImageCache`,
/// `MapSnapshotStore`) to feed `NSCache.totalCostLimit` and hand-rolled cost
/// budgets.
///
/// The `cgImage` branch reads the real backing-store size including row
/// alignment; the fallback approximates 4 bytes per logical pixel for
/// `UIImage`s without a `CGImage` (e.g. built from a `CIImage` or a renderer
/// block that never materialised a CG image).
///
/// Animation-specific aggregation (e.g. summing GIF frames) stays at the
/// caller, which invokes `bytes(for:)` once per frame.
enum ImageByteCost {
  private static let bytesPerPixelRGBA = 4

  static func bytes(for image: UIImage) -> Int {
    if let cgImage = image.cgImage {
      return cgImage.bytesPerRow * cgImage.height
    }
    return Int(image.size.width * image.size.height) * bytesPerPixelRGBA
  }

  static func bytes(for image: UIImage?) -> Int {
    guard let image else { return 0 }
    return bytes(for: image)
  }
}
