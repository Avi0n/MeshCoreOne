import CoreGraphics

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
