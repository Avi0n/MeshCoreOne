import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Connection control menu", .serialized, .timeLimit(.minutes(1)))
@MainActor
struct ConnectionControlMenuTests {
  private enum Layout {
    static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
    static let presentationSettle = Duration.milliseconds(750)
  }

  @Test(arguments: [false, true])
  func `recovery menu exports status and renders controls with standard and accessible hosting sizes`(accessibleSize: Bool) async throws {
    let env = try await ConnectionLinkSwitchHarness.make()
    var host: MenuHost?
    do {
      let original = try #require(env.manager.currentConnection)
      let targetName = "Mountain Rescue Operations Relay 27"
      env.manager.persistConnection(deviceID: env.device.id, radioID: env.device.radioID, deviceName: targetName)
      await env.bluetooth.simulateAutoReconnecting(deviceID: env.device.id)
      try await waitUntil("physical loss must publish a recovery activity") {
        env.manager.currentConnection == nil && env.manager.connectionActivity?.phase == .reconnecting
      }
      let activity = try #require(env.manager.connectionActivity)
      #expect(activity.requestID == original.requestID)
      #expect(activity.targetName == targetName)
      let title = L10n.Settings.BleStatus.accessibilityValue(L10n.Settings.BleStatus.accessibilityLabel, activity.accessibilityValue)
      let mounted = try MenuHost(app: env.appState, accessibleSize: accessibleSize)
      host = mounted
      try await waitUntil("production toolbar must export target and status: \(mounted.describe())") {
        mounted.nodes(in: [mounted.window]).contains { $0.label == title && $0.traits.contains(.button) }
      }
      let controls = mounted.nodes(in: [mounted.window]).filter { $0.label == title && $0.traits.contains(.button) }
      #expect(controls.count == 1, "\(mounted.describe())")
      let control = try #require(controls.first)
      #expect(control.hasWindowContainedAXFrame)
      try #require(mounted.openMenu(control), "Native toolbar did not accept activation: \(mounted.describe())")
      let menuTitles = [activity.accessibilityValue, L10n.Settings.BleStatus.stopReconnecting, L10n.Settings.BleStatus.changeDevice]
      try await waitUntil("native menu must render target, state and both controls: \(mounted.describe())") {
        let nodes = mounted.nodes(in: mounted.visibleWindows)
        return menuTitles.allSatisfy { title in nodes.contains { $0.label == title && $0.hasWindowContainedAXFrame } }
      }
      try await Task.sleep(for: Layout.presentationSettle)
      let menuNodes = mounted.nodes(in: mounted.visibleWindows)
      for title in [L10n.Settings.BleStatus.stopReconnecting, L10n.Settings.BleStatus.changeDevice] {
        #expect(menuNodes.contains { $0.label == title && $0.traits.contains(.button) && $0.hasWindowContainedAXFrame })
      }
      let header = try #require(menuNodes.first { $0.label == activity.accessibilityValue })
      let actions = menuNodes.filter {
        [L10n.Settings.BleStatus.stopReconnecting, L10n.Settings.BleStatus.changeDevice].contains($0.label)
          && $0.traits.contains(.button)
      }
      #expect(actions.allSatisfy { !header.frame.intersects($0.frame) })
      Attachment.record(Data(mounted.describe().utf8), named: "connection-menu-accessibility-tree.txt")
      let stop = try #require(menuNodes.first { $0.label == L10n.Settings.BleStatus.stopReconnecting && $0.traits.contains(.button) })
      let change = try #require(menuNodes.first { $0.label == L10n.Settings.BleStatus.changeDevice && $0.traits.contains(.button) })
      #expect(stop.window === change.window)
      #expect(stop.frame.minY < change.frame.minY, "Stopping the current request must precede changing devices")
      let sizes = [
        "application: \(UIApplication.shared.preferredContentSizeCategory.rawValue)",
        "nativeMenuWindow: \(stop.window.traitCollection.preferredContentSizeCategory.rawValue)",
        "accessibleHostingOverride: \(accessibleSize)"
      ].joined(separator: "\n")
      Attachment.record(Data(sizes.utf8), named: "connection-menu-content-sizes.txt")
      var didDraw = false
      let image = UIGraphicsImageRenderer(bounds: stop.window.bounds).image { _ in
        didDraw = stop.window.drawHierarchy(in: stop.window.bounds, afterScreenUpdates: true)
      }
      #expect(didDraw)
      let name = accessibleSize ? "connection-menu-accessibility5.png" : "connection-menu-standard.png"
      try Attachment.record(#require(image.pngData()), named: name)
    } catch {
      host?.close()
      await env.close()
      throw error
    }
    host?.close()
    await env.close()
  }

  @MainActor
  private final class MenuHost {
    let window: UIWindow
    private let scene: UIWindowScene
    private let previousKeyWindow: UIWindow?

    init(app: AppState, accessibleSize: Bool) throws {
      let selectedScene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
      scene = selectedScene
      previousKeyWindow = selectedScene.windows.first(where: \.isKeyWindow)
      let root = NavigationStack {
        Text("Connection menu fixture")
          .toolbar { ToolbarItem(placement: .topBarLeading) { BLEStatusIndicatorView() } }
      }
      .environment(\.appState, app)
      .environment(\.dynamicTypeSize, accessibleSize ? .accessibility5 : .large)
      let controller = UIHostingController(rootView: root)
      controller.traitOverrides.preferredContentSizeCategory = accessibleSize
        ? UIContentSizeCategory.accessibilityExtraExtraExtraLarge : UIContentSizeCategory.large
      window = UIWindow(windowScene: scene)
      window.frame = Layout.viewport
      window.rootViewController = controller
      controller.view.frame = window.bounds
      window.makeKeyAndVisible()
      window.layoutIfNeeded()
    }

    var visibleWindows: [UIWindow] {
      scene.windows.filter { !$0.isHidden && $0.alpha > 0 }
    }

    func close() {
      window.rootViewController?.dismiss(animated: false)
      window.rootViewController = nil
      window.isHidden = true
      window.windowScene = nil
      previousKeyWindow?.makeKeyAndVisible()
    }

    func describe() -> String {
      nodes(in: visibleWindows).map { node in
        "[\(node.label) traits=\(node.traits.rawValue) type=\(type(of: node.object)) frame=\(node.frame)]"
      }.joined(separator: " ")
    }

    func openMenu(_ node: AccessNode) -> Bool {
      var ancestor = node.object as? UIView
      while let view = ancestor {
        if let control = view as? UIControl, control.isEnabled {
          control.performPrimaryAction()
          return true
        }
        ancestor = view.superview
      }
      if node.object.accessibilityActivate() { return true }
      let center = CGPoint(x: node.frame.midX, y: node.frame.midY)
      guard let control = controls(in: node.window).first(where: {
        $0.showsMenuAsPrimaryAction && $0.convert($0.bounds, to: nil).contains(center)
      }) else { return false }
      control.performPrimaryAction()
      return true
    }

    private func controls(in view: UIView) -> [UIControl] {
      guard !view.isHidden, view.alpha > 0 else { return [] }
      return ((view as? UIControl).map { [$0] } ?? []) + view.subviews.flatMap { controls(in: $0) }
    }

    func nodes(in windows: [UIWindow]) -> [AccessNode] {
      var result: [AccessNode] = []
      var seen = Set<ObjectIdentifier>()
      func visit(_ object: NSObject, in owner: UIWindow) {
        guard seen.insert(ObjectIdentifier(object)).inserted else { return }
        if let view = object as? UIView, view.isHidden || view.accessibilityElementsHidden { return }
        let count = object.accessibilityElementCount()
        let label = object.accessibilityLabel ?? ""
        if object.isAccessibilityElement, !label.isEmpty {
          result.append(AccessNode(object: object, window: owner, label: label, traits: object.accessibilityTraits, frame: object.accessibilityFrame))
        }
        if count != NSNotFound, count > 0 {
          for index in 0..<count {
            if let child = object.accessibilityElement(at: index) as? NSObject, child !== object { visit(child, in: owner) }
          }
        } else if let elements = object.accessibilityElements, !elements.isEmpty {
          for case let child as NSObject in elements where child !== object {
            visit(child, in: owner)
          }
        } else if let view = object as? UIView {
          for child in view.subviews where child.alpha > 0 {
            visit(child, in: owner)
          }
        }
      }
      windows.forEach { visit($0, in: $0) }
      return result
    }
  }

  @MainActor
  private struct AccessNode {
    let object: NSObject
    let window: UIWindow
    let label: String
    let traits: UIAccessibilityTraits
    let frame: CGRect

    var hasWindowContainedAXFrame: Bool {
      !frame.isEmpty && window.convert(window.bounds, to: nil).contains(frame)
    }
  }
}
