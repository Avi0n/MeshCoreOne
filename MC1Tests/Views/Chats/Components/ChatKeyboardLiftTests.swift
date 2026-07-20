import CoreGraphics
@testable import MC1
import Testing
import UIKit

@Suite("ChatKeyboardLift")
struct ChatKeyboardLiftTests {
  private let windowBounds = CGRect(x: 0, y: 0, width: 390, height: 852)
  private let homeIndicator: CGFloat = 34

  @Test
  func `off-screen keyboard produces zero lift`() {
    let frame = CGRect(x: 0, y: 852, width: 390, height: 336)
    let lift = ChatKeyboardLift.ownedBottomPadding(
      keyboardFrameInWindow: frame,
      windowBounds: windowBounds,
      bottomSafeArea: homeIndicator
    )
    #expect(lift == 0)
  }

  @Test
  func `null intersection produces zero lift`() {
    let frame = CGRect(x: 0, y: 900, width: 390, height: 336)
    let lift = ChatKeyboardLift.ownedBottomPadding(
      keyboardFrameInWindow: frame,
      windowBounds: windowBounds,
      bottomSafeArea: homeIndicator
    )
    #expect(lift == 0)
  }

  @Test
  func `docked keyboard subtracts home indicator so the bar sits flush`() {
    // Keyboard covers the bottom 336pt of the window, including the home indicator.
    let frame = CGRect(x: 0, y: 852 - 336, width: 390, height: 336)
    let lift = ChatKeyboardLift.ownedBottomPadding(
      keyboardFrameInWindow: frame,
      windowBounds: windowBounds,
      bottomSafeArea: homeIndicator
    )
    #expect(lift == 336 - homeIndicator)
  }

  @Test
  func `zero home indicator uses full overlap`() {
    let frame = CGRect(x: 0, y: 852 - 300, width: 390, height: 300)
    let lift = ChatKeyboardLift.ownedBottomPadding(
      keyboardFrameInWindow: frame,
      windowBounds: windowBounds,
      bottomSafeArea: 0
    )
    #expect(lift == 300)
  }

  @Test
  func `partial overlap below home indicator clamps to zero`() {
    // Overlap shorter than the home indicator must not produce negative lift.
    let frame = CGRect(x: 0, y: 852 - 20, width: 390, height: 20)
    let lift = ChatKeyboardLift.ownedBottomPadding(
      keyboardFrameInWindow: frame,
      windowBounds: windowBounds,
      bottomSafeArea: homeIndicator
    )
    #expect(lift == 0)
  }

  @Test
  func `residual keyboard-sized gap matches the mid-screen bar failure mode`() {
    // When the keyboard is gone, owned lift is zero even if a residual system
    // inset of roughly keyboard height would park the bar mid-screen under
    // automatic keyboard avoidance.
    let residualSystemInset: CGFloat = 336
    let barHeight: CGFloat = 52
    let automaticBarMinY = windowBounds.height - residualSystemInset - homeIndicator - barHeight
    #expect(automaticBarMinY / windowBounds.height > 0.35)
    #expect(automaticBarMinY / windowBounds.height < 0.75)

    let ownedLiftWhenKeyboardGone = ChatKeyboardLift.ownedBottomPadding(
      keyboardFrameInWindow: CGRect(x: 0, y: windowBounds.height, width: 390, height: residualSystemInset),
      windowBounds: windowBounds,
      bottomSafeArea: homeIndicator
    )
    let ownedBarMinY = windowBounds.height - homeIndicator - barHeight - ownedLiftWhenKeyboardGone
    #expect(ownedLiftWhenKeyboardGone == 0)
    #expect(ownedBarMinY / windowBounds.height > 0.85)
  }

  @Test
  func `interactive keyboard duration zero yields no animation`() {
    let userInfo: [AnyHashable: Any] = [
      UIResponder.keyboardAnimationDurationUserInfoKey: 0.0,
    ]
    let animation = ChatKeyboardLift.animation(
      forKeyboardNotificationUserInfo: userInfo,
      reduceMotion: false
    )
    #expect(animation == nil)
  }

  @Test
  func `missing duration yields no animation`() {
    let animation = ChatKeyboardLift.animation(
      forKeyboardNotificationUserInfo: [:],
      reduceMotion: false
    )
    #expect(animation == nil)
  }

  @Test
  func `positive keyboard duration yields an animation when motion is allowed`() {
    let userInfo: [AnyHashable: Any] = [
      UIResponder.keyboardAnimationDurationUserInfoKey: 0.25,
    ]
    let animation = ChatKeyboardLift.animation(
      forKeyboardNotificationUserInfo: userInfo,
      reduceMotion: false
    )
    #expect(animation != nil)
  }

  @Test
  func `reduce motion suppresses keyboard animation even with positive duration`() {
    let userInfo: [AnyHashable: Any] = [
      UIResponder.keyboardAnimationDurationUserInfoKey: 0.25,
    ]
    let animation = ChatKeyboardLift.animation(
      forKeyboardNotificationUserInfo: userInfo,
      reduceMotion: true
    )
    #expect(animation == nil)
  }
}
