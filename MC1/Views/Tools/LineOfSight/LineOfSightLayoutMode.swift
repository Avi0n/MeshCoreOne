import CoreGraphics

/// Presentation for one Line of Sight workspace. Pair only when both surfaces
/// meet these named minima; otherwise keep the map and an analysis sheet.
enum LineOfSightLayoutMode: String, Equatable {
  case paired
  case mapWithSheet

  /// Narrowest analysis column that still fits point rows and RF controls.
  static let minimumAnalysisWidth: CGFloat = 320

  /// Analysis column on a regular iPad when the map still has its minimum width.
  static let preferredAnalysisWidth: CGFloat = 400

  /// Width for map markers, the path, and overlay controls.
  static let minimumMapWidth: CGFloat = 320

  /// Height for points, Analyze, and a results summary without a sheet detent.
  static let minimumAnalysisHeight: CGFloat = 420

  /// Height for the map canvas and overlay controls.
  static let minimumMapHeight: CGFloat = 280

  var accessibilityIdentifier: String {
    "lineOfSight.layout.\(rawValue)"
  }

  static func preferred(in size: CGSize) -> LineOfSightLayoutMode {
    let canPairHorizontally = size.width >= minimumAnalysisWidth + minimumMapWidth
    let canPairVertically = size.height >= minimumAnalysisHeight
      && size.height >= minimumMapHeight
    if canPairHorizontally, canPairVertically {
      return .paired
    }
    return .mapWithSheet
  }

  /// Preferred width, clamped so the map always keeps `minimumMapWidth`.
  static func analysisColumnWidth(in size: CGSize) -> CGFloat {
    guard size.width > 0 else { return preferredAnalysisWidth }
    let available = size.width - minimumMapWidth
    return min(preferredAnalysisWidth, max(minimumAnalysisWidth, available))
  }
}

/// Compact map-control padding. The overlay lives in the workspace; the analysis
/// sheet covers the tab bar, so the two views do not share a bottom safe area.
enum LineOfSightMapOverlayPadding {
  static let fallback: CGFloat = 220

  /// Cancels `MapControlsToolbar`'s default `.padding()` so extra space is
  /// measured from the glass edge, not the padded layout box.
  static let toolbarChromeInset: CGFloat = 16

  /// Space between the map controls and the collapsed analysis sheet.
  static let collapsedSheetGap: CGFloat = 12

  static func amount(overlayMaxY: CGFloat, sheetMinY: CGFloat) -> CGFloat {
    let overlap = overlayMaxY - sheetMinY
    guard overlayMaxY > 0, sheetMinY > 0, overlap > 0 else { return fallback }
    return max(0, overlap - toolbarChromeInset + collapsedSheetGap)
  }
}
