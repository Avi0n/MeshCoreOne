import CoreGraphics
@testable import MC1
import SwiftUI
import Testing
import UIKit

@Suite("ChatKeyboardLift")
struct ChatKeyboardLiftTests {
  private let windowBounds = CGRect(x: 0, y: 0, width: 390, height: 852)
  private let homeIndicator: CGFloat = 34

  private enum HostedLayout {
    static let compactSize = CGSize(width: 390, height: 844)
    static let regularSize = CGSize(width: 1024, height: 768)
    static let composerHeight: CGFloat = 52
    static let startupTimeout: TimeInterval = 5
    static let runLoopSlice: TimeInterval = 0.02
    static let settle: TimeInterval = 0.35
    static let tolerance: CGFloat = 1
  }

  private struct NativeChromeBoundsHost: View {
    var body: some View {
      NavigationStack {
        Color.clear
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .chatBottomChrome(canvas: Color(.systemBackground)) {
            Color.gray.frame(height: HostedLayout.composerHeight)
          }
          .navigationTitle("Keyboard bounds")
          .navigationBarTitleDisplayMode(.inline)
      }
      .environment(\.scenePhase, .active)
    }
  }

  @Test
  @MainActor
  func `native chat observer owns the window bottom at compact and regular widths`() throws {
    for regularWidth in [false, true] {
      try assertObserverOwnsWindowBottom(regularWidth: regularWidth)
    }
  }

  @MainActor
  private func assertObserverOwnsWindowBottom(regularWidth: Bool) throws {
    var readyScene: UIWindowScene?
    let deadline = Date(timeIntervalSinceNow: HostedLayout.startupTimeout)
    repeat {
      readyScene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive && $0.keyWindow != nil }
      if readyScene != nil { break }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: HostedLayout.runLoopSlice))
    } while Date() < deadline
    let scene = try #require(readyScene, "Timed out waiting for a foreground app scene with a key window")
    let previousKeyWindow = scene.keyWindow
    let size = regularWidth ? HostedLayout.regularSize : HostedLayout.compactSize
    let controller = UIHostingController(rootView: NativeChromeBoundsHost())
    controller.traitOverrides.horizontalSizeClass = regularWidth ? .regular : .compact
    controller.traitOverrides.verticalSizeClass = .regular
    let window = UIWindow(windowScene: scene)
    window.frame = CGRect(origin: .zero, size: size)
    window.rootViewController = controller
    controller.view.frame = window.bounds
    window.makeKeyAndVisible()
    defer {
      window.rootViewController = nil
      window.isHidden = true
      window.windowScene = nil
      previousKeyWindow?.makeKey()
    }
    window.layoutIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: HostedLayout.settle))
    window.layoutIfNeeded()

    if !regularWidth {
      try #require(window.safeAreaInsets.bottom > 0, "The compact fixture must include the home indicator")
    }
    let observer = try #require(findObserver(in: window))
    let guide = controller.view.keyboardLayoutGuide
    let frame = observer.convert(observer.bounds, to: window)
    #expect(
      abs(frame.maxY - window.bounds.maxY) < HostedLayout.tolerance,
      "regular=\(regularWidth), observer=\(frame), window=\(window.bounds), safeArea=\(window.safeAreaInsets)"
    )
    let keyboardConstraints = controller.view.constraints.filter {
      ($0.secondItem as? UIKeyboardLayoutGuide) === guide
    }
    #expect(keyboardConstraints.contains { $0.isActive && $0.firstAttribute == .top && $0.relation == .equal })
  }

  @Test
  @MainActor
  func `observer rebinds the root guide after detachment and a window change`() throws {
    let controller = UIViewController()
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
      .first { $0.activationState == .foregroundActive && $0.keyWindow != nil })
    let previousKeyWindow = scene.keyWindow
    let window = UIWindow(windowScene: scene)
    window.frame = CGRect(origin: .zero, size: HostedLayout.compactSize)
    window.rootViewController = controller
    window.makeKeyAndVisible()
    defer {
      window.rootViewController = nil
      window.isHidden = true
      window.windowScene = nil
      previousKeyWindow?.makeKey()
    }
    let observer = ChatKeyboardLayoutObserver()
    observer.onWindowAttachment = { [weak observer] in
      guard let observer else { return }
      observer.trackKeyboardLayoutGuide(observer.window?.rootViewController?.view.keyboardLayoutGuide)
    }
    controller.view.addSubview(observer)
    let guide = controller.view.keyboardLayoutGuide
    let marker = try #require(observer.subviews.first)
    let original = try #require(controller.view.constraints.first {
      ($0.secondItem as? UIKeyboardLayoutGuide) === guide && ($0.firstItem as? UIView) === marker
    })
    #expect(original.isActive)

    observer.removeFromSuperview()
    #expect(!original.isActive)
    controller.view.addSubview(observer)
    let rebound = try #require(controller.view.constraints.first {
      ($0.secondItem as? UIKeyboardLayoutGuide) === guide && ($0.firstItem as? UIView) === marker
    })
    #expect(rebound.isActive)
    #expect(rebound !== original)

    let nextController = UIViewController()
    let nextWindow = UIWindow(windowScene: scene)
    nextWindow.frame = window.frame
    nextWindow.rootViewController = nextController
    nextWindow.makeKeyAndVisible()
    defer {
      nextWindow.rootViewController = nil
      nextWindow.isHidden = true
      nextWindow.windowScene = nil
    }
    nextController.view.addSubview(observer)
    let nextGuide = nextController.view.keyboardLayoutGuide
    #expect(!rebound.isActive)
    #expect(nextController.view.constraints.contains {
      $0.isActive && ($0.secondItem as? UIKeyboardLayoutGuide) === nextGuide && ($0.firstItem as? UIView) === marker
    })
    observer.trackKeyboardLayoutGuide(nil)
    #expect(!nextController.view.constraints.contains {
      $0.isActive && ($0.firstItem as? UIView) === marker
    })
  }

  @MainActor
  private func findObserver(in view: UIView) -> ChatKeyboardLayoutObserver? {
    if let observer = view as? ChatKeyboardLayoutObserver { return observer }
    for child in view.subviews {
      if let observer = findObserver(in: child) { return observer }
    }
    return nil
  }

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
  func `interactive pop keeps current lift on a drop`() {
    let resolved = ChatKeyboardLift.resolvedLift(
      current: 302,
      proposed: 0,
      isInteractivePopActive: true
    )
    #expect(resolved == 302)
  }

  @Test
  func `hide without interactive pop applies proposed lift`() {
    let resolved = ChatKeyboardLift.resolvedLift(
      current: 302,
      proposed: 0,
      isInteractivePopActive: false
    )
    #expect(resolved == 0)
  }

  @Test
  func `interactive pop still applies a higher lift`() {
    let resolved = ChatKeyboardLift.resolvedLift(
      current: 200,
      proposed: 302,
      isInteractivePopActive: true
    )
    #expect(resolved == 302)
  }
}
