import SwiftUI
import Testing
import UIKit

/// Hosts a SwiftUI view in a key window on the test app's scene.
@MainActor
struct HostedWindow {
  let window: UIWindow
  let controller: UIViewController
  private let previousKeyWindow: UIWindow?

  init(frame: CGRect, root: some View) throws {
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    previousKeyWindow = scene.keyWindow
    controller = UIHostingController(rootView: root)
    window = UIWindow(windowScene: scene)
    window.frame = frame
    window.rootViewController = controller
    window.makeKeyAndVisible()
  }

  func layout() {
    window.layoutIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.01))
  }

  func close() {
    controller.dismiss(animated: false)
    window.isHidden = true
    window.rootViewController = nil
    window.windowScene = nil
    previousKeyWindow?.makeKey()
  }
}

/// Accessible elements under `root`, walking SwiftUI's accessibility tree as VoiceOver would.
@MainActor
func accessibilityNodes(in root: NSObject) -> [NSObject] {
  var visited: Set<ObjectIdentifier> = []
  var result: [NSObject] = []
  func walk(_ object: NSObject) {
    guard visited.insert(ObjectIdentifier(object)).inserted else { return }
    if let view = object as? UIView, view.isHidden || view.accessibilityElementsHidden { return }
    if object.isAccessibilityElement { result.append(object) }
    if let children = object.accessibilityElements {
      for case let child as NSObject in children where child !== object {
        walk(child)
      }
    }
    if let children = object.automationElements {
      for case let child as NSObject in children where child !== object {
        walk(child)
      }
    }
    let count = object.accessibilityElementCount()
    if count != NSNotFound, count > 0 {
      for index in 0..<count {
        if let child = object.accessibilityElement(at: index) as? NSObject, child !== object { walk(child) }
      }
    }
    if let view = object as? UIView { view.subviews.forEach(walk) }
  }
  walk(root)
  return result
}
