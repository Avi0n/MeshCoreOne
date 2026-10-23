import Foundation
@testable import MC1
import SwiftUI
import Testing
import UIKit

@Suite("Chat keyboard transition classification", .serialized)
@MainActor
struct ChatKeyboardTransitionClassificationTests {
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

  @Test(arguments: [
    (style: UIModalPresentationStyle.pageSheet, interactive: true, expected: false),
    (style: .none, interactive: true, expected: true),
    (style: .none, interactive: false, expected: false)
  ])
  func `keyboard protection applies only to interactive navigation transitions`(
    scenario: (style: UIModalPresentationStyle, interactive: Bool, expected: Bool)
  ) async throws {
    var readyScene: UIWindowScene?
    _ = await waitUntil {
      readyScene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
      return readyScene != nil
    }
    let scene = try #require(readyScene)
    let originalWindow = scene.keyWindow
    let window = UIWindow(windowScene: scene)
    window.frame = scene.coordinateSpace.bounds
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
      window.isHidden = true
      window.rootViewController = nil
      window.windowScene = nil
      originalWindow?.makeKeyAndVisible()
    }
    let mounted = await waitUntil { self.findObserver(in: window) != nil }
    #expect(mounted)
    let observer = try #require(findObserver(in: window))
    var responder: UIResponder? = observer
    while let current = responder, !(current is UIViewController) {
      responder = current.next
    }
    #expect((responder as? UIViewController)?.navigationController === navigation)
    #expect(!(observer.isInteractivePopActive()), "A resting navigation stack must not freeze keyboard motion")
    navigation.simulatedCoordinator = Coordinator(style: scenario.style, interactive: scenario.interactive, container: navigation.view)
    #expect(observer.isInteractivePopActive() == scenario.expected, "A modal transition must not engage navigation keyboard protection")
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
