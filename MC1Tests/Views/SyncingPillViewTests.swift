@testable import MC1
import SwiftUI
import Testing
import UIKit

@Suite("SyncingPillView Tests")
struct SyncingPillViewTests {
  @Test
  func `Connecting state shows correct text and icon`() {
    let state = StatusPillState.connecting
    #expect(state.displayText == L10n.Localizable.Common.Status.connecting)
    #expect(state.systemImageName == "arrow.trianglehead.2.clockwise")
    #expect(state.isFailure == false)
    #expect(state.textColor == .primary)
  }

  @Test
  func `Syncing state shows correct text and icon`() {
    let state = StatusPillState.syncing
    #expect(state.displayText == L10n.Localizable.Common.Status.syncing)
    #expect(state.systemImageName == "arrow.trianglehead.2.clockwise")
    #expect(state.isFailure == false)
    #expect(state.textColor == .primary)
  }

  @Test
  func `Ready state shows correct text and icon`() {
    let state = StatusPillState.ready
    #expect(state.displayText == L10n.Localizable.Common.Status.ready)
    #expect(state.systemImageName == "checkmark.circle")
    #expect(state.isFailure == false)
    #expect(state.textColor == .primary)
  }

  @Test
  func `Disconnected state shows orange warning icon and text`() {
    let state = StatusPillState.disconnected
    #expect(state.displayText == L10n.Localizable.Common.Status.disconnected)
    #expect(state.systemImageName == "exclamationmark.triangle")
    #expect(state.isFailure == false)
    #expect(state.textColor == .orange)
  }

  @Test
  @MainActor
  func `Disconnected with tap handler stores closure`() {
    var tapped = false
    let view = SyncingPillView(
      state: .disconnected,
      onDisconnectedTap: { tapped = true }
    )
    // The handler is stored but not called until user interaction
    #expect(!tapped)
    // Manually invoke to verify the closure is wired correctly
    view.onDisconnectedTap?()
    #expect(tapped)
  }

  @Test
  func `Failed state shows red text and failure icon with custom message`() {
    let message = "Sync Failed"
    let state = StatusPillState.failed(message: message)
    #expect(state.displayText == message)
    #expect(state.systemImageName == "exclamationmark.triangle.fill")
    #expect(state.isFailure == true)
    #expect(state.textColor == .red)
  }

  @Test
  func `Failed state preserves custom error message`() {
    let customMessage = "Custom Error"
    let state = StatusPillState.failed(message: customMessage)
    #expect(state.displayText == customMessage)
    #expect(state.isFailure == true)
  }

  @Test
  func `Hidden state shows empty text and no icon`() {
    let state = StatusPillState.hidden
    #expect(state.displayText == "")
    #expect(state.systemImageName == "")
    #expect(state.isFailure == false)
    #expect(state.textColor == .primary)
  }
}

private final class FixedHeightTabBar: UITabBar {
  override func sizeThatFits(_ size: CGSize) -> CGSize {
    CGSize(width: size.width, height: 44)
  }

  override var intrinsicContentSize: CGSize {
    CGSize(width: UIView.noIntrinsicMetric, height: 44)
  }
}

@Suite("SyncingPillPlacement")
struct SyncingPillPlacementTests {
  private let overlayHeight: CGFloat = 1180

  @Test
  func `missing tab bar keeps the compact top gap`() {
    let padding = SyncingPillPlacement.topPadding(
      tabBarFrameInOverlay: nil,
      overlayHeight: overlayHeight
    )
    #expect(padding == SyncingPillPlacement.contentGap)
  }

  @Test
  func `bottom tab bar keeps the compact top gap`() {
    let bottomBar = CGRect(x: 0, y: 1100, width: 820, height: 50)
    let padding = SyncingPillPlacement.topPadding(
      tabBarFrameInOverlay: bottomBar,
      overlayHeight: overlayHeight
    )
    #expect(padding == SyncingPillPlacement.contentGap)
  }

  @Test
  func `top tab bar sits the pill below the bar`() {
    let topBar = CGRect(x: 0, y: 32, width: 820, height: 44)
    let padding = SyncingPillPlacement.topPadding(
      tabBarFrameInOverlay: topBar,
      overlayHeight: overlayHeight
    )
    #expect(padding == 32 + 44 + SyncingPillPlacement.contentGap)
  }

  @Test
  func `full-screen chrome is not treated as a top tab bar`() {
    let chrome = CGRect(x: 0, y: 0, width: 820, height: overlayHeight)
    let padding = SyncingPillPlacement.topPadding(
      tabBarFrameInOverlay: chrome,
      overlayHeight: overlayHeight
    )
    #expect(padding == SyncingPillPlacement.contentGap)
  }

  @Test
  @MainActor
  func `bottom tab bar does not infer a top bar`() {
    let window = makeWindow(size: CGSize(width: 400, height: 800))
    defer { release(window) }
    let root = UIView(frame: window.bounds)
    window.addSubview(root)
    let bar = UITabBar(frame: CGRect(x: 0, y: 751, width: 400, height: 49))
    bar.autoresizingMask = []
    root.addSubview(bar)
    let decoy = UIView(frame: CGRect(x: 0, y: 8, width: 300, height: 44))
    root.addSubview(decoy)
    let overlay = UIView(frame: window.bounds)
    root.addSubview(overlay)

    #expect(SyncingPillPlacement.topTabBarFrame(in: overlay) == nil)
  }

  @Test
  @MainActor
  func `top tab bar frame is returned`() {
    let window = makeWindow(size: CGSize(width: 400, height: 800))
    defer { release(window) }
    let root = UIView(frame: window.bounds)
    window.addSubview(root)
    let bar = FixedHeightTabBar(frame: CGRect(x: 0, y: 8, width: 400, height: 44))
    bar.autoresizingMask = []
    root.addSubview(bar)
    bar.frame = CGRect(x: 0, y: 8, width: 400, height: 44)
    let overlay = UIView(frame: window.bounds)
    root.addSubview(overlay)

    let frame = SyncingPillPlacement.topTabBarFrame(in: overlay)
    #expect(frame == bar.convert(bar.bounds, to: overlay))
  }

  @Test
  @MainActor
  func `a wide short view is inferred when no tab bar exists`() {
    let window = makeWindow(size: CGSize(width: 400, height: 800))
    defer { release(window) }
    let root = UIView(frame: window.bounds)
    window.addSubview(root)
    let decoy = UIView(frame: CGRect(x: 0, y: 8, width: 300, height: 44))
    root.addSubview(decoy)
    let overlay = UIView(frame: window.bounds)
    root.addSubview(overlay)

    #expect(SyncingPillPlacement.topTabBarFrame(in: overlay) == decoy.convert(decoy.bounds, to: overlay))
  }

  @MainActor
  private func makeWindow(size: CGSize) -> UIWindow {
    let frame = CGRect(origin: .zero, size: size)
    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
      window = UIWindow(windowScene: scene)
      window.frame = frame
    } else {
      window = UIWindow(frame: frame)
    }
    window.isHidden = false
    return window
  }

  @MainActor
  private func release(_ window: UIWindow) {
    window.isHidden = true
    window.windowScene = nil
  }

  @Test
  func `zero overlay height keeps the compact top gap`() {
    let topBar = CGRect(x: 0, y: 32, width: 820, height: 44)
    let padding = SyncingPillPlacement.topPadding(
      tabBarFrameInOverlay: topBar,
      overlayHeight: 0
    )
    #expect(padding == SyncingPillPlacement.contentGap)
  }
}
