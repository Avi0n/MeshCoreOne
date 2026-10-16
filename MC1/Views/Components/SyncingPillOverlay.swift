import SwiftUI
import UIKit

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

  /// Reads the live top tab bar so the pill can sit below it.
  struct TopTabBarFrameReader: UIViewRepresentable {
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
}

extension View {
  /// Pins the connection syncing pill to the top of this shell. See `SyncingPillOverlay`.
  func syncingPillOverlay(onDisconnectedTap: @escaping () -> Void) -> some View {
    modifier(SyncingPillOverlay(onDisconnectedTap: onDisconnectedTap))
  }
}
