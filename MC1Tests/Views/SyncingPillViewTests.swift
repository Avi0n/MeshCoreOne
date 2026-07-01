@testable import MC1
import SwiftUI
import Testing

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
