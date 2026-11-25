import CoreGraphics
import Foundation

/// Prefetch cache key and snapshot size from the width the preview is drawn at.
enum MessagePathPreviewSnapshot {
  static let widthBucketPoints: CGFloat = 8

  struct Key: Hashable {
    let arrivalID: UUID
    let isDark: Bool
    let isOffline: Bool
    let widthBucket: Int
  }

  static func bucketedWidth(_ width: CGFloat) -> Int {
    Int((width / widthBucketPoints).rounded() * widthBucketPoints)
  }

  static func previewSize(previewWidth: CGFloat) -> CGSize {
    CGSize(
      width: CGFloat(bucketedWidth(max(0, previewWidth))),
      height: MessagePathPreviewMap.previewHeight
    )
  }

  static func key(
    arrivalID: UUID,
    isDark: Bool,
    isOffline: Bool,
    previewWidth: CGFloat
  ) -> Key {
    Key(
      arrivalID: arrivalID,
      isDark: isDark,
      isOffline: isOffline,
      widthBucket: bucketedWidth(max(0, previewWidth))
    )
  }
}
