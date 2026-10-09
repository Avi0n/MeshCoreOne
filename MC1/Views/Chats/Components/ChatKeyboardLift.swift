import SwiftUI
import UIKit

/// Chat reserves keyboard space explicitly because interrupted system avoidance can retain stale insets.
/// The keyboard layout guide supplies the current position to every bottom-chrome consumer.
enum ChatKeyboardLift {
  /// Ignore sub-point lift churn from successive layout samples.
  static let liftChangeThreshold: CGFloat = 0.5

  /// Subtract the home-indicator space already reserved below the composer.
  /// Both frames use window coordinates so partial keyboard overlap stays bounded.
  static func ownedBottomPadding(
    keyboardFrameInWindow: CGRect,
    windowBounds: CGRect,
    bottomSafeArea: CGFloat
  ) -> CGFloat {
    let intersection = windowBounds.intersection(keyboardFrameInWindow)
    guard !intersection.isNull, intersection.height > 0 else { return 0 }
    return max(0, intersection.height - bottomSafeArea)
  }

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

  /// Interactive pop posts willHide while the keyboard window stays; keep the
  /// current lift. Back-button and drag-to-dismiss still apply `proposed`.
  static func resolvedLift(
    current: CGFloat,
    proposed: CGFloat,
    isInteractivePopActive: Bool
  ) -> CGFloat {
    if isInteractivePopActive, proposed < current {
      return current
    }
    return proposed
  }
}

extension EnvironmentValues {
  /// Owned keyboard lift applied to chat bottom chrome. Zero when the keyboard is down.
  @Entry var chatKeyboardLift: CGFloat = 0
}

// MARK: - View modifiers

private struct ChatKeyboardLiftPaddingModifier: ViewModifier {
  @Environment(\.chatKeyboardLift) private var lift

  func body(content: Content) -> some View {
    content.padding(.bottom, lift)
  }
}

extension View {
  /// Pads the bottom of compose / bottom-chrome content by `chatKeyboardLift`.
  func chatKeyboardLiftPadding() -> some View {
    modifier(ChatKeyboardLiftPaddingModifier())
  }
}
