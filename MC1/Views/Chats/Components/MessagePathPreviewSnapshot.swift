import CoreGraphics
import Foundation

/// Prefetch cache key and snapshot size from the Path Details container width.
enum MessagePathPreviewSnapshot {
  static let widthBucketPoints: CGFloat = 8
  static let expandedHorizontalPadding: CGFloat = 16

  struct Key: Hashable {
    let arrivalID: UUID
    let isDark: Bool
    let isOffline: Bool
    let widthBucket: Int
  }

  static func bucketedWidth(_ width: CGFloat) -> Int {
    Int((width / widthBucketPoints).rounded() * widthBucketPoints)
  }

  static func previewSize(containerWidth: CGFloat) -> CGSize {
    let inner = max(0, containerWidth - 2 * expandedHorizontalPadding)
    return CGSize(
      width: CGFloat(bucketedWidth(inner)),
      height: MessagePathPreviewMap.previewHeight
    )
  }

  static func key(
    arrivalID: UUID,
    isDark: Bool,
    isOffline: Bool,
    containerWidth: CGFloat
  ) -> Key {
    let inner = max(0, containerWidth - 2 * expandedHorizontalPadding)
    return Key(
      arrivalID: arrivalID,
      isDark: isDark,
      isOffline: isOffline,
      widthBucket: bucketedWidth(inner)
    )
  }
}
