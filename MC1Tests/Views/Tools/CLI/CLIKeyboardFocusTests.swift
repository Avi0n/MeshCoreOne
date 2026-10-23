import Foundation
@testable import MC1
import SwiftUI
import Testing
import UIKit

@Suite("CLI keyboard focus", .serialized)
struct CLIKeyboardFocusTests {
  @Test
  func `first appear sets focus and later appears do not`() {
    var focus = CLIKeyboardFocus()
    focus.onAppear()
    #expect(focus.isFocused)
    #expect(focus.hasAppeared)

    focus.clearFocus()
    focus.onAppear()
    #expect(focus.isFocused == false)
    #expect(focus.hasAppeared)
  }

  @Test
  func `tap after clearFocus sets focus`() {
    var focus = CLIKeyboardFocus()
    focus.onAppear()
    focus.clearFocus()
    focus.onTap()
    #expect(focus.isFocused)
    #expect(focus.hasAppeared)
  }

  @Test
  func `external begin sets focus and programmatic begin does not`() {
    var focus = CLIKeyboardFocus()
    focus.onAppear()
    focus.clearFocus()

    focus.noteBeginEditing()
    #expect(focus.isFocused)
    #expect(focus.hasAppeared)

    focus.clearFocus()
    focus.beginProgrammaticEditing()
    focus.noteBeginEditing()
    focus.endProgrammaticEditing()
    #expect(focus.isFocused == false)
    #expect(focus.hasAppeared)
  }

  @Test
  @MainActor
  func `a view with no window clears focus`() async throws {
    FocusableTextView.removeFromWindowForTesting = true
    defer { FocusableTextView.removeFromWindowForTesting = false }

    let model = CLIFocusHostModel(focused: true)
    let controller = UIHostingController(rootView: CLIFocusHost(model: model, log: CLIFocusWriteLog()))
    let window = makeWindow(controller: controller)
    defer { release(window) }

    try await waitUntil(timeout: .seconds(1), "focus should clear without a window") {
      model.focus.isFocused == false
    }
  }

  @Test
  @MainActor
  func `failed becomeFirstResponder clears focus`() async throws {
    FocusableTextView.failBecomeFirstResponderForTesting = true
    defer { FocusableTextView.failBecomeFirstResponderForTesting = false }

    let model = CLIFocusHostModel(focused: true)
    let controller = UIHostingController(rootView: CLIFocusHost(model: model, log: CLIFocusWriteLog()))
    let window = makeWindow(controller: controller)
    defer { release(window) }

    try await waitUntil(timeout: .seconds(1), "failed become should clear focus") {
      model.focus.isFocused == false
    }
    let textView = try #require(firstTextView(in: controller.view))
    #expect(textView.isFirstResponder == false)
  }

  @Test
  @MainActor
  func `hide path resigns without assigning focus before editing ends`() async throws {
    let model = CLIFocusHostModel(focused: true)
    let log = CLIFocusWriteLog()
    let controller = UIHostingController(rootView: CLIFocusHost(model: model, log: log))
    let window = makeWindow(controller: controller)
    defer { release(window) }

    let textView = try #require(firstTextView(in: controller.view))
    try await waitUntil(timeout: .seconds(1), "text view should become first responder") {
      textView.isFirstResponder && model.focus.isFocused
    }

    model.textView = textView
    log.writes.removeAll()
    var focus = model.focus
    focus.clearFocus()
    model.focus = focus

    try await waitUntil(timeout: .seconds(1), "hide should resign first responder") {
      textView.isFirstResponder == false
    }
    try await waitUntil(timeout: .seconds(1), "end editing should clear focus") {
      log.writes.contains { $0.focused == false }
    }
    #expect(log.writes.allSatisfy { $0.firstResponder == false })
    #expect(model.focus.isFocused == false)
  }

  @MainActor
  private func makeWindow(controller: UIViewController) -> UIWindow {
    let frame = CGRect(x: 0, y: 0, width: 320, height: 44)
    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
      window = UIWindow(windowScene: scene)
      window.frame = frame
    } else {
      window = UIWindow(frame: frame)
    }
    controller.view.frame = frame
    window.rootViewController = controller
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return window
  }

  @MainActor
  private func release(_ window: UIWindow) {
    window.rootViewController = nil
    window.isHidden = true
    window.windowScene = nil
  }

  @MainActor
  private func firstTextView(in view: UIView) -> FocusableTextView? {
    if let textView = view as? FocusableTextView { return textView }
    for subview in view.subviews {
      if let textView = firstTextView(in: subview) { return textView }
    }
    return nil
  }
}

@Observable
@MainActor
private final class CLIFocusHostModel {
  var text = ""
  var focus: CLIKeyboardFocus
  var cursor = 0
  weak var textView: FocusableTextView?

  init(focused: Bool) {
    var focus = CLIKeyboardFocus()
    if focused {
      focus.onTap()
    }
    self.focus = focus
  }
}

@MainActor
private final class CLIFocusWriteLog {
  var writes: [(focused: Bool, firstResponder: Bool)] = []
}

private struct CLIFocusHost: View {
  @Bindable var model: CLIFocusHostModel
  let log: CLIFocusWriteLog

  var body: some View {
    HiddenTextViewFocusable(
      text: $model.text,
      keyboardFocus: Binding(
        get: { model.focus },
        set: { newValue in
          log.writes.append((newValue.isFocused, model.textView?.isFirstResponder ?? false))
          model.focus = newValue
        }
      ),
      cursorPosition: $model.cursor,
      onSubmit: {},
      onHistoryUp: {},
      onHistoryDown: {},
      onRightArrowAtEnd: {},
      onTabComplete: {}
    )
  }
}
