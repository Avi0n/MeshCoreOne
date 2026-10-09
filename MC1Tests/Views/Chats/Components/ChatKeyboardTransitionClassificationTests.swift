@testable import MC1
import SwiftUI
import UIKit
import XCTest

@MainActor
final class ChatKeyboardTransitionClassificationTests: XCTestCase {
  private enum Metrics {
    static let chromeHeight: CGFloat = 44
    static let timeout: TimeInterval = 15
    static let pollInterval: Duration = .milliseconds(16)
  }

  private final class NavigationController: UINavigationController {
    var simulatedCoordinator: (any UIViewControllerTransitionCoordinator)?

    override var transitionCoordinator: (any UIViewControllerTransitionCoordinator)? {
      simulatedCoordinator ?? super.transitionCoordinator
    }
  }

  private final class Coordinator: NSObject, UIViewControllerTransitionCoordinator {
    let presentationStyle: UIModalPresentationStyle
    let initiallyInteractive: Bool
    let containerView: UIView
    let isAnimated = true
    let isInterruptible = true
    let isInteractive = true
    let isCancelled = false
    let transitionDuration: TimeInterval = 0
    let percentComplete: CGFloat = 0
    let completionVelocity: CGFloat = 0
    let completionCurve: UIView.AnimationCurve = .linear
    let targetTransform = CGAffineTransform.identity

    init(style: UIModalPresentationStyle, interactive: Bool, container: UIView) {
      presentationStyle = style
      initiallyInteractive = interactive
      containerView = container
    }

    func viewController(forKey key: UITransitionContextViewControllerKey) -> UIViewController? {
      nil
    }

    func view(forKey key: UITransitionContextViewKey) -> UIView? {
      nil
    }

    func animate(
      alongsideTransition animation: ((any UIViewControllerTransitionCoordinatorContext) -> Void)?,
      completion: ((any UIViewControllerTransitionCoordinatorContext) -> Void)?
    ) -> Bool {
      false
    }

    func animateAlongsideTransition(
      in view: UIView?, animation: ((any UIViewControllerTransitionCoordinatorContext) -> Void)?,
      completion: ((any UIViewControllerTransitionCoordinatorContext) -> Void)?
    ) -> Bool {
      false
    }

    func notifyWhenInteractionEnds(_ handler: @escaping (any UIViewControllerTransitionCoordinatorContext) -> Void) {}
    func notifyWhenInteractionChanges(_ handler: @escaping (any UIViewControllerTransitionCoordinatorContext) -> Void) {}
  }

  func testInteractiveSheetIsNotClassifiedAsNavigationPop() async throws {
    try await assertPopClassification(style: .pageSheet, interactive: true, expected: false)
  }

  func testInteractiveNavigationTransitionKeepsPopProtection() async throws {
    try await assertPopClassification(style: .none, interactive: true, expected: true)
  }

  func testNoninteractiveNavigationDoesNotFreezeKeyboard() async throws {
    try await assertPopClassification(style: .none, interactive: false, expected: false)
  }

  private func assertPopClassification(style: UIModalPresentationStyle, interactive: Bool, expected: Bool) async throws {
    var readyWindow: UIWindow?
    _ = await waitUntil {
      readyWindow = ChatKeyboardLift.keyWindow()
      return readyWindow != nil
    }
    let window = try XCTUnwrap(readyWindow)
    let originalController = window.rootViewController
    let hosting = UIHostingController(rootView:
      Color.clear.chatBottomChrome(canvas: .clear) {
        Color.clear.frame(height: Metrics.chromeHeight)
      }
      .environment(\.scenePhase, .active))
    let navigation = NavigationController(rootViewController: UIViewController())
    navigation.pushViewController(hosting, animated: false)
    navigation.setNavigationBarHidden(true, animated: false)
    window.rootViewController = navigation
    window.makeKeyAndVisible()
    defer {
      navigation.simulatedCoordinator = nil
      window.rootViewController = originalController
      window.makeKeyAndVisible()
    }
    let mounted = await waitUntil { self.findObserver(in: window) != nil }
    XCTAssertTrue(mounted)
    let observer = try XCTUnwrap(findObserver(in: window))
    var responder: UIResponder? = observer
    while let current = responder, !(current is UIViewController) {
      responder = current.next
    }
    XCTAssertTrue((responder as? UIViewController)?.navigationController === navigation)
    XCTAssertFalse(observer.isInteractivePopActive(), "A resting navigation stack must not freeze keyboard motion")
    navigation.simulatedCoordinator = Coordinator(style: style, interactive: interactive, container: navigation.view)
    XCTAssertEqual(
      observer.isInteractivePopActive(), expected,
      "A modal transition must not engage navigation keyboard protection"
    )
  }

  private func findObserver(in view: UIView) -> ChatKeyboardLayoutObserver? {
    if let observer = view as? ChatKeyboardLayoutObserver { return observer }
    return view.subviews.compactMap { findObserver(in: $0) }.first
  }

  private func waitUntil(_ condition: () -> Bool) async -> Bool {
    let deadline = Date(timeIntervalSinceNow: Metrics.timeout)
    while Date() < deadline {
      if condition() { return true }
      try? await Task.sleep(for: Metrics.pollInterval)
    }
    return condition()
  }
}
