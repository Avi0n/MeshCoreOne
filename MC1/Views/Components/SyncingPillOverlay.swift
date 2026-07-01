import SwiftUI

/// Overlays the connection `SyncingPillView` on top of a shell's content, pinned to the top.
/// Both the iPhone tab shell and the iPad sidebar shell use it, so the pill's offset, fade,
/// and spring timing stay identical across layouts. `displayedPillState` lags `statusPillState`
/// by one animated step so the pill can finish its exit animation before being removed.
struct SyncingPillOverlay: ViewModifier {
  @Environment(\.appState) private var appState
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  let onDisconnectedTap: () -> Void

  @State private var displayedPillState: StatusPillState = .hidden

  private let topInset: CGFloat = 8
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
      .padding(.top, topInset)
      .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
      .offset(y: appState.statusPillState == .hidden ? offscreenOffset : 0)
      .opacity(appState.statusPillState == .hidden ? 0 : 1)
      .animation(pillAnimation, value: appState.statusPillState)
      .allowsHitTesting(appState.statusPillState != .hidden)
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

extension View {
  /// Pins the connection syncing pill to the top of this shell. See `SyncingPillOverlay`.
  func syncingPillOverlay(onDisconnectedTap: @escaping () -> Void) -> some View {
    modifier(SyncingPillOverlay(onDisconnectedTap: onDisconnectedTap))
  }
}
