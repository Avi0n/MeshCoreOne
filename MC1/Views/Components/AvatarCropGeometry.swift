import CoreGraphics

/// Pan, zoom, and crop-guide math for `AvatarCropView`.
struct AvatarCropGeometry: Equatable {
  static let minScale: CGFloat = 1
  static let maxScale: CGFloat = 4
  static let compactMaxCropSize: CGFloat = 300
  static let regularMaxCropSize: CGFloat = 480
  static let cropMargin: CGFloat = 32
  static let outputSide: CGFloat = 512
  static let decodeMaxPixelSize: CGFloat = 1024
  static let zoomStep: CGFloat = 0.25
  static let panStepFraction: CGFloat = 0.125

  var cropSize: CGFloat
  var imageSize: CGSize
  var scale: CGFloat = 1
  var offset: CGSize = .zero

  static func cropSize(in available: CGSize, isRegularWidth: Bool) -> CGFloat {
    let maxSide = isRegularWidth ? regularMaxCropSize : compactMaxCropSize
    let shortest = min(available.width, available.height)
    let fitted = min(maxSide, shortest - cropMargin * 2)
    return max(fitted, 1)
  }

  var baseDisplaySize: CGSize {
    guard imageSize.width > 0, imageSize.height > 0 else {
      return CGSize(width: cropSize, height: cropSize)
    }
    let fillScale = max(cropSize / imageSize.width, cropSize / imageSize.height)
    return CGSize(width: imageSize.width * fillScale, height: imageSize.height * fillScale)
  }

  func clampedScale(_ proposed: CGFloat) -> CGFloat {
    min(max(proposed, Self.minScale), Self.maxScale)
  }

  /// Keeps the crop square fully covered at the given scale.
  func clampedOffset(_ proposed: CGSize, scale: CGFloat? = nil) -> CGSize {
    let usedScale = scale ?? self.scale
    let displayed = CGSize(
      width: baseDisplaySize.width * usedScale,
      height: baseDisplaySize.height * usedScale
    )
    let maxX = max(0, (displayed.width - cropSize) / 2)
    let maxY = max(0, (displayed.height - cropSize) / 2)
    return CGSize(
      width: min(max(proposed.width, -maxX), maxX),
      height: min(max(proposed.height, -maxY), maxY)
    )
  }

  func liveTransform(pinchDelta: CGFloat, dragTranslation: CGSize) -> (scale: CGFloat, offset: CGSize) {
    let liveScale = clampedScale(scale * pinchDelta)
    let proposed = CGSize(
      width: offset.width + dragTranslation.width,
      height: offset.height + dragTranslation.height
    )
    return (liveScale, clampedOffset(proposed, scale: liveScale))
  }

  func imageDrawRect(outputSide: CGFloat) -> CGRect {
    let displayed = CGSize(
      width: baseDisplaySize.width * scale,
      height: baseDisplaySize.height * scale
    )
    let origin = CGPoint(
      x: (cropSize - displayed.width) / 2 + offset.width,
      y: (cropSize - displayed.height) / 2 + offset.height
    )
    let renderScale = outputSide / cropSize
    return CGRect(
      x: origin.x * renderScale,
      y: origin.y * renderScale,
      width: displayed.width * renderScale,
      height: displayed.height * renderScale
    )
  }

  mutating func applyZoomStep(_ delta: CGFloat) {
    scale = clampedScale(scale + delta)
    offset = clampedOffset(offset)
  }

  mutating func applyPan(width: CGFloat, height: CGFloat) {
    offset = clampedOffset(CGSize(width: offset.width + width, height: offset.height + height))
  }
}
