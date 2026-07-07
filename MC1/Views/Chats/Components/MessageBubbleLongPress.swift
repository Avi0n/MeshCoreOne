import SwiftUI

/// Tuning for the press-and-hold gesture that opens a message bubble's actions
/// sheet, shared by the chat (`UnifiedMessageBubble`) and room
/// (`RoomMessageBubble`) bubbles and their conversation views so the interaction
/// feels identical across surfaces.
enum MessageActionsPresentation {
  /// Seconds the user must hold before the actions sheet opens: long enough a
  /// quick tap can never reach it, short enough a deliberate hold stays responsive.
  static let longPressConfirmDuration: Double = 0.6

  /// Spring `response` (natural period) for the press-in/release scale animation.
  static let longPressSpringResponse: Double = 0.7

  /// Delay before the press-in scale animates, so a brief accidental tap shows
  /// no lift. No delay on release so the bubble retracts immediately.
  static let longPressInDelay: Double = 0.1

  /// Scale the bubble shrinks to while pressed, before the actions sheet opens.
  static let longPressPressedScale: CGFloat = 0.95

  /// Delay after the actions sheet dismisses before raising the keyboard or
  /// presenting a follow-on sheet. A focus request issued mid-dismissal is lost,
  /// and on iPad UIKit cancels a new sheet presented before the old one finishes
  /// animating away, leaving the user looking at nothing.
  static let dismissalDelay: Duration = .milliseconds(300)
}

extension View {
  /// Attaches the bubble's actions-sheet long-press: a sustained press fires
  /// `onFire`, drives the shared press state, and bumps the haptic trigger.
  /// Apply to every sub-view that should open the actions sheet; the visual
  /// response is rendered once by `messageBubbleLongPressEffect`.
  ///
  /// Uses `.simultaneousGesture` so the press coexists with a native `Text`
  /// link's own tap: a quick tap still routes through the link's `\.openURL`,
  /// while a sustained press fires the actions sheet. (A high-priority gesture
  /// would reserve the touch and swallow link taps.)
  func messageBubbleLongPressGesture(
    isPressing: Binding<Bool>,
    trigger: Binding<Int>,
    onFire: @escaping () -> Void
  ) -> some View {
    modifier(MessageBubbleActionsLongPress(isPressing: isPressing, trigger: trigger, onFire: onFire))
  }

  /// Renders the press-in shrink and haptic for a message bubble. Apply once to
  /// the view that should scale; pair with `messageBubbleLongPressGesture`.
  func messageBubbleLongPressEffect(isPressing: Bool, trigger: Int) -> some View {
    modifier(MessageBubbleLongPressEffect(isPressing: isPressing, trigger: trigger))
  }
}

/// The actions-sheet long press, expressed as a `.simultaneousGesture` `LongPressGesture` so it
/// coexists with a native `Text` link's tap rather than reserving the touch and swallowing it.
/// `@GestureState` reports the press-in phase on touch down (and auto-resets on release/cancel),
/// mirrored to the shared `isPressing` binding so the once-applied `messageBubbleLongPressEffect`
/// renders the lift for the whole bubble.
private struct MessageBubbleActionsLongPress: ViewModifier {
  @Binding var isPressing: Bool
  @Binding var trigger: Int
  let onFire: () -> Void

  @GestureState private var pressing = false

  func body(content: Content) -> some View {
    content
      .simultaneousGesture(
        LongPressGesture(minimumDuration: MessageActionsPresentation.longPressConfirmDuration)
          .updating($pressing) { current, state, _ in state = current }
          .onEnded { _ in
            trigger += 1
            onFire()
          }
      )
      .onChange(of: pressing) { _, now in isPressing = now }
  }
}

private struct MessageBubbleLongPressEffect: ViewModifier {
  let isPressing: Bool
  let trigger: Int

  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  func body(content: Content) -> some View {
    content
      .scaleEffect(isPressing ? MessageActionsPresentation.longPressPressedScale : 1.0)
      .animation(
        reduceMotion
          ? nil
          : .spring(response: MessageActionsPresentation.longPressSpringResponse)
          .delay(isPressing ? MessageActionsPresentation.longPressInDelay : 0),
        value: isPressing
      )
      .sensoryFeedback(.impact(flexibility: .solid), trigger: trigger)
  }
}
