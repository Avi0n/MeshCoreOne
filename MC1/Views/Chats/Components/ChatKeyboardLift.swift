import SwiftUI
import UIKit

/// Pure geometry and timing for owned chat keyboard lift.
///
/// SwiftUI's automatic keyboard safe area can retain residual height after an
/// interrupted hide (app switch, notification activation). Chat owns the lift
/// instead: ignore the system keyboard safe area and pad bottom chrome from
/// the keyboard frame. When the keyboard is gone, padding is zero — residual
/// system insets cannot float the compose bar mid-screen.
enum ChatKeyboardLift {
  /// Ignore sub-point lift churn from successive keyboard frame notifications.
  static let liftChangeThreshold: CGFloat = 0.5

  /// Bottom padding to place the compose bar flush above a docked keyboard.
  ///
  /// - Parameters:
  ///   - keyboardFrameInWindow: Keyboard end frame in the same space as `windowBounds`.
  ///   - windowBounds: Key window bounds.
  ///   - bottomSafeArea: Window bottom safe area (home indicator). The compose
  ///     bar already sits above it via container safe area, so that height is
  ///     subtracted to avoid a double-count gap on iPhone and iPad.
  static func ownedBottomPadding(
    keyboardFrameInWindow: CGRect,
    windowBounds: CGRect,
    bottomSafeArea: CGFloat
  ) -> CGFloat {
    let intersection = windowBounds.intersection(keyboardFrameInWindow)
    guard !intersection.isNull, intersection.height > 0 else { return 0 }
    return max(0, intersection.height - bottomSafeArea)
  }

  /// SwiftUI animation matching a keyboard notification, or `nil` for an
  /// immediate update. Interactive dismiss reports duration 0; non-interactive
  /// show/hide report a positive duration that should be mirrored.
  static func animation(
    forKeyboardNotificationUserInfo userInfo: [AnyHashable: Any]?,
    reduceMotion: Bool
  ) -> Animation? {
    guard !reduceMotion else { return nil }
    guard
      let duration = userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double,
      duration > 0
    else {
      return nil
    }
    // System keyboard curve raw values are private; match duration with easeInOut.
    return .easeInOut(duration: duration)
  }

  /// Resolves the key window used for keyboard geometry (foreground-active scene).
  @MainActor
  static func keyWindow() -> UIWindow? {
    UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .first { $0.activationState == .foregroundActive }?
      .keyWindow
  }

  @MainActor
  static func resignFirstResponder() {
    UIApplication.shared.sendAction(
      #selector(UIResponder.resignFirstResponder),
      to: nil,
      from: nil,
      for: nil
    )
  }
}

extension EnvironmentValues {
  // Owned keyboard lift applied to chat bottom chrome. Zero when the keyboard is down.
  @Entry var chatKeyboardLift: CGFloat = 0
}

// MARK: - View modifiers

private struct ChatKeyboardOwnedLiftModifier: ViewModifier {
  @Environment(\.scenePhase) private var scenePhase
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var lift: CGFloat = 0

  func body(content: Content) -> some View {
    content
      .environment(\.chatKeyboardLift, lift)
      .ignoresSafeArea(.keyboard, edges: .bottom)
      .onReceive(
        NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
      ) { notification in
        applyKeyboardFrame(from: notification)
      }
      .onReceive(
        NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)
      ) { notification in
        setLift(0, userInfo: notification.userInfo)
      }
      .onChange(of: scenePhase) { _, phase in
        switch phase {
        case .background, .inactive:
          // Hide notifications can be skipped on deactivate; collapse immediately.
          ChatKeyboardLift.resignFirstResponder()
          setLift(0, userInfo: nil)
        case .active:
          break
        @unknown default:
          break
        }
      }
  }

  private func applyKeyboardFrame(from notification: Notification) {
    guard
      let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect,
      let window = ChatKeyboardLift.keyWindow()
    else {
      setLift(0, userInfo: notification.userInfo)
      return
    }
    let keyboardInWindow = window.convert(frame, from: nil)
    let next = ChatKeyboardLift.ownedBottomPadding(
      keyboardFrameInWindow: keyboardInWindow,
      windowBounds: window.bounds,
      bottomSafeArea: window.safeAreaInsets.bottom
    )
    setLift(next, userInfo: notification.userInfo)
  }

  private func setLift(_ value: CGFloat, userInfo: [AnyHashable: Any]?) {
    guard abs(value - lift) > ChatKeyboardLift.liftChangeThreshold else { return }
    let animation = ChatKeyboardLift.animation(
      forKeyboardNotificationUserInfo: userInfo,
      reduceMotion: reduceMotion
    )
    if let animation {
      withAnimation(animation) { lift = value }
    } else {
      var transaction = Transaction()
      transaction.disablesAnimations = true
      withTransaction(transaction) { lift = value }
    }
  }
}

private struct ChatKeyboardLiftPaddingModifier: ViewModifier {
  @Environment(\.chatKeyboardLift) private var lift

  func body(content: Content) -> some View {
    // Animate at the lift source so duration-0 interactive frames stay immediate.
    content.padding(.bottom, lift)
  }
}

extension View {
  /// Owns keyboard avoidance for chat conversation hosts: ignores system
  /// keyboard safe area and publishes `chatKeyboardLift` for bottom chrome.
  func chatKeyboardOwnedLift() -> some View {
    modifier(ChatKeyboardOwnedLiftModifier())
  }

  /// Pads the bottom of compose / bottom-chrome content by `chatKeyboardLift`.
  func chatKeyboardLiftPadding() -> some View {
    modifier(ChatKeyboardLiftPaddingModifier())
  }
}
