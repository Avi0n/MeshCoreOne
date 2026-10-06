import SwiftUI
import UIKit

/// Pill offset from the overlay origin. A measured top tab bar adds its height
/// so the pill cannot cover tab hits; bottom tabs stay at `contentGap`.
enum SyncingPillPlacement {
  static let contentGap: CGFloat = 8
  /// Matches the iPadOS 26+ top tab capsule when the live bar cannot be measured.
  static let fallbackTopTabBarHeight: CGFloat = 44
  private static let topBandMaxY: CGFloat = 120
  private static let inferredBarHeightRange: ClosedRange<CGFloat> = 36...64

  static func topPadding(tabBarFrameInOverlay: CGRect?, overlayHeight: CGFloat) -> CGFloat {
    guard
      let frame = tabBarFrameInOverlay,
      overlayHeight > 0,
      inferredBarHeightRange.contains(frame.height),
      frame.minY < overlayHeight / 2
    else {
      return contentGap
    }
    return frame.maxY + contentGap
  }

  /// Live top tab bar in `overlay` coordinates, or `nil` when tabs are at the bottom.
  @MainActor
  static func topTabBarFrame(in overlay: UIView) -> CGRect? {
    guard let window = overlay.window else { return nil }
    if let bar = firstMatchingSubview(in: window, where: looksLikeTabBar(_:)) {
      let frame = bar.convert(bar.bounds, to: overlay)
      if frame.minY < overlay.bounds.height / 2, inferredBarHeightRange.contains(frame.height) {
        return frame
      }
    }
    return inferredTopTabBarFrame(in: overlay, window: window)
  }

  @MainActor
  private static func inferredTopTabBarFrame(in overlay: UIView, window: UIWindow) -> CGRect? {
    var candidate: UIView?
    walk(window) { view in
      let frame = view.convert(view.bounds, to: window)
      guard inferredBarHeightRange.contains(frame.height),
            frame.minY < topBandMaxY,
            frame.maxY < topBandMaxY,
            frame.width >= window.bounds.width * 0.6
      else { return }
      if candidate == nil || view.bounds.height < candidate!.bounds.height {
        candidate = view
      }
    }
    return candidate.map { $0.convert($0.bounds, to: overlay) }
  }

  @MainActor
  private static func looksLikeTabBar(_ view: UIView) -> Bool {
    if view is UITabBar { return true }
    let name = String(describing: type(of: view))
    return name.contains("TabBar")
      && !name.contains("Button")
      && !name.contains("Item")
      && !name.contains("ButtonBar")
  }

  @MainActor
  private static func firstMatchingSubview(in root: UIView, where match: (UIView) -> Bool) -> UIView? {
    var best: UIView?
    walk(root) { view in
      guard match(view) else { return }
      if best == nil || view.bounds.height < best!.bounds.height {
        best = view
      }
    }
    return best
  }

  @MainActor
  private static func walk(_ view: UIView, visit: (UIView) -> Void) {
    visit(view)
    for subview in view.subviews {
      walk(subview, visit: visit)
    }
  }
}
