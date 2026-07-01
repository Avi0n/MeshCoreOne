import CoreGraphics

/// Shared geometry for the three rich-preview families (website link, inline
/// image/GIF, coordinate map). Each family read its own copies of these values
/// before; collapsing them here keeps a height-stability or corner-radius tweak
/// from silently drifting one card away from the others. `@ScaledMetric`
/// wrappers stay per-view (they need the view's environment) and read these
/// named constants as their base values.
enum RichPreviewMetrics {
  /// Fallback hero aspect when the source provides no intrinsic dimensions.
  static let fallbackAspect: Double = 16.0 / 9.0

  /// Lower bound for a reserved hero, so a very tall image does not collapse.
  static let minHeroHeight: CGFloat = 100

  /// Upper bound for a reserved hero, so a very wide image does not dominate.
  static let maxHeroHeight: CGFloat = 250

  /// Card and hero corner radius, matching the chat map thumbnail.
  static let cornerRadius: CGFloat = 12

  /// Hero aspect from intrinsic pixel dimensions, falling back to
  /// `fallbackAspect` when either dimension is missing or non-positive.
  /// Shared by the link card and its loading placeholder so the reserved hero
  /// keeps the same shape across the byte-arrival transition.
  static func heroAspect(imageWidth: Int?, imageHeight: Int?) -> CGFloat {
    guard let imageWidth, let imageHeight, imageWidth > 0, imageHeight > 0 else {
      return CGFloat(fallbackAspect)
    }
    return CGFloat(imageWidth) / CGFloat(imageHeight)
  }
}
