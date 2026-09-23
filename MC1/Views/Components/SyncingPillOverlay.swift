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

/// Pins `SyncingPillView` above the tab host. `displayedPillState` lags
/// `statusPillState` so the exit animation finishes before the pill is removed.
struct SyncingPillOverlay: ViewModifier {
  @Environment(\.appState) private var appState
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass

  let onDisconnectedTap: () -> Void

  @State private var displayedPillState: StatusPillState = .hidden
  @State private var topPadding: CGFloat = SyncingPillPlacement.contentGap

  /// Measured padding, with a regular-iPad fallback when the tab bar cannot be found.
  private var displayTopPadding: CGFloat {
    let minimum: CGFloat =
      (horizontalSizeClass == .regular && UIDevice.current.userInterfaceIdiom == .pad)
        ? SyncingPillPlacement.fallbackTopTabBarHeight + SyncingPillPlacement.contentGap
        : SyncingPillPlacement.contentGap
    return max(topPadding, minimum)
  }

  private let transitionDuration: TimeInterval = 0.3
  private let offscreenOffset: CGFloat = -100

  private let readySpringDuration: TimeInterval = 0.4
  private let readySpringBounce = 0.15
  private let alertSpringDuration: TimeInterval = 0.35
  private let alertSpringBounce = 0.2
  private let defaultSpringDuration: TimeInterval = 0.4

  private var pillAnimation: Animation {
    if reduceMotion { return .linear(duration: 0) }

    switch appState.statusPillState {
    case .ready:
      return .spring(duration: readySpringDuration, bounce: readySpringBounce)
    case .failed, .disconnected:
      return .spring(duration: alertSpringDuration, bounce: alertSpringBounce)
    default:
      return .spring(duration: defaultSpringDuration)
    }
  }

  /// Animates the pill's content swap, suppressed under Reduce Motion to match `pillAnimation`.
  private var contentAnimation: Animation {
    reduceMotion ? .linear(duration: 0) : .spring(duration: transitionDuration)
  }

  func body(content: Content) -> some View {
    ZStack(alignment: .top) {
      content

      SyncingPillView(
        state: displayedPillState,
        onDisconnectedTap: onDisconnectedTap
      )
      .animation(contentAnimation, value: displayedPillState)
      .padding(.top, displayTopPadding)
      .frame(maxWidth: .infinity, alignment: .top)
      .offset(y: appState.statusPillState == .hidden ? offscreenOffset : 0)
      .opacity(appState.statusPillState == .hidden ? 0 : 1)
      .animation(pillAnimation, value: appState.statusPillState)
      .allowsHitTesting(appState.statusPillState != .hidden)
    }
    .background {
      TopTabBarFrameReader { padding in
        if abs(padding - topPadding) > 0.5 {
          topPadding = padding
        }
      }
    }
    .onChange(of: appState.statusPillState, initial: true) { _, new in
      if new != .hidden {
        withAnimation(pillAnimation) {
          displayedPillState = new
        }
      }
    }
  }
}

/// Reads the live top tab bar so the pill can sit below it.
private struct TopTabBarFrameReader: UIViewRepresentable {
  var onChange: (CGFloat) -> Void

  func makeUIView(context: Context) -> ProbeView {
    let view = ProbeView()
    view.onChange = onChange
    view.isUserInteractionEnabled = false
    view.backgroundColor = .clear
    view.isAccessibilityElement = false
    return view
  }

  func updateUIView(_ uiView: ProbeView, context: Context) {
    uiView.onChange = onChange
  }

  final class ProbeView: UIView {
    var onChange: ((CGFloat) -> Void)?
    private var lastPadding: CGFloat?

    override func layoutSubviews() {
      super.layoutSubviews()
      reportIfNeeded()
    }

    override func didMoveToWindow() {
      super.didMoveToWindow()
      setNeedsLayout()
    }

    private func reportIfNeeded() {
      guard bounds.height > 0 else { return }
      let frame = SyncingPillPlacement.topTabBarFrame(in: self)
      let padding = SyncingPillPlacement.topPadding(
        tabBarFrameInOverlay: frame,
        overlayHeight: bounds.height
      )
      guard lastPadding != padding else { return }
      lastPadding = padding
      DispatchQueue.main.async { [onChange] in
        onChange?(padding)
      }
    }
  }
}

extension View {
  /// Pins the connection syncing pill to the top of this shell. See `SyncingPillOverlay`.
  func syncingPillOverlay(onDisconnectedTap: @escaping () -> Void) -> some View {
    modifier(SyncingPillOverlay(onDisconnectedTap: onDisconnectedTap))
  }
}
