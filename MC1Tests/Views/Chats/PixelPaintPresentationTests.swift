import Foundation
@testable import MC1Services
import SwiftUI
import Testing
import UIKit
@testable import MC1

private struct AccessibilityDump: Error, CustomStringConvertible {
  var description: String
  init(_ description: String) { self.description = description }
}

@Suite("Pixel paint presentation", .serialized)
@MainActor
struct PixelPaintPresentationTests {
  @Test func fullScreenCoverBlocksDismissalAndCancelDiscardsPixels() async throws {
    let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true), isPlaceholder: true)
    appState.connectionManager.setTestState(connectionState: .ready)
    let draft = PixelPaintDraft()
    let presentation = Presentation()
    let window = try host(Harness(draft: draft, presentation: presentation).environment(\.appState, appState))
    defer { release(window, appState: appState) }

    try await waitUntil("Harness should appear before presenting") { presentation.isReady }
    presentation.isPresented = true
    try await waitUntil("Editor should present") { presentedController(in: window) != nil }
    let presented = try #require(presentedController(in: window))
    #expect(presented.modalPresentationStyle == .fullScreen || presented.modalPresentationStyle == .overFullScreen)
    try await waitUntil("Editor should block interactive dismissal") {
      presented.isModalInPresentation
        || presented.presentationController.map {
          $0.delegate?.presentationControllerShouldDismiss?($0) == false
        } == true
    }
    do {
      try await waitUntil("Cancel and Send should be accessible") {
        matching(L10n.Localizable.Common.cancel, in: window) != nil
          && matching(L10n.Chats.Chats.Mcoimg.send, in: window) != nil
      }
    } catch {
      Issue.record(AccessibilityDump(accessibilityDump(window)))
      throw error
    }
    #expect(isEnabled(L10n.Chats.Chats.Mcoimg.send, in: window) == true)

    draft.paint(x: 1, y: 2)
    draft.endStroke()
    let pixels = draft.image
    appState.connectionManager.setTestState(connectionState: .disconnected, connectedDevice: .some(nil))
    #expect(presentedController(in: window) != nil)
    #expect(draft.image == pixels)
    try await waitUntil("Send disables when the connection drops") {
      isEnabled(L10n.Chats.Chats.Mcoimg.send, in: window) == false
    }
    try #require(await activate(L10n.Localizable.Common.cancel, in: window))
    try await waitUntil("Cancel should dismiss the editor") { presentedController(in: window) == nil }
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })

    presentation.isPresented = true
    try await waitUntil("Editor should reopen") { presentedController(in: window) != nil }
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
  }

  @Test func sendAdmitsOnceWithoutChangingTheTextDraft() async throws {
    let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true), isPlaceholder: true)
    appState.connectionManager.setTestState(connectionState: .ready)
    let draft = PixelPaintDraft()
    draft.paint(x: 2, y: 3)
    draft.endStroke()
    let snapshot = draft.image
    let model = SendModel()
    let window = try host(SendHarness(draft: draft, model: model).environment(\.appState, appState))
    defer { release(window, appState: appState) }

    try await waitUntil("Harness should appear") { model.isReady }
    model.isPresented = true
    try await waitUntil("Editor should present") { presentedController(in: window) != nil }
    let presented = try #require(presentedController(in: window))
    try #require(await activate(L10n.Chats.Chats.Mcoimg.send, in: window))
    try await waitUntil("Send should reach admission") { model.payloads.count == 1 }
    #expect(draft.isAdmitting)
    try await waitUntil("Cancel disables once admission starts") {
      isEnabled(L10n.Localizable.Common.cancel, in: window) == false
    }
    // Clear is drawn in the SwiftUI hosting view, which does not expose a UIButton in this host.
    // The button calls clear(), and that stays a no-op for the whole submission.
    let pixelsDuringSend = draft.image
    draft.clear()
    #expect(draft.image == pixelsDuringSend)
    #expect(hasActivityIndicator(in: window))
    #expect(isEnabled(L10n.Chats.Chats.Mcoimg.send, in: window) == false)
    _ = await activate(L10n.Chats.Chats.Mcoimg.send, in: window)
    #expect(model.payloads.count == 1)
    #expect(model.text == SendModel.originalText)
    model.pause.resume()
    try await waitUntil("Admission should dismiss the editor") { presentedController(in: window) == nil }
    #expect(model.payloads.count == 1)
    #expect(model.text == SendModel.originalText)
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
    #expect(draft.image != snapshot)
  }

  @Test func rejectedAdmissionShowsOneCoverAlertAndKeepsPixels() async throws {
    let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true), isPlaceholder: true)
    appState.connectionManager.setTestState(connectionState: .ready)
    let draft = PixelPaintDraft()
    draft.paint(x: 4, y: 1)
    draft.endStroke()
    let snapshot = draft.image
    let model = SendModel()
    model.accept = false
    let window = try host(SendHarness(draft: draft, model: model).environment(\.appState, appState))
    defer { release(window, appState: appState) }

    try await waitUntil { model.isReady }
    model.isPresented = true
    try await waitUntil("Editor should present") { presentedController(in: window) != nil }
    let presented = try #require(presentedController(in: window))
    try #require(await activate(L10n.Chats.Chats.Mcoimg.send, in: window))
    try await waitUntil("Send should be waiting") { model.payloads.count == 1 }
    model.pause.resume()
    try await waitUntil("Cover should show the admission alert") {
      alertTitled(
        L10n.Chats.Chats.Alert.UnableToSend.title,
        message: L10n.Chats.Chats.Alert.UnableToSend.message,
        from: window.rootViewController
      )
    }
    #expect(presentedController(in: window) != nil)
    #expect(model.sendErrorMessage == nil)
    #expect(draft.image == snapshot)
    #expect(model.text == SendModel.originalText)
  }

  @Test func disconnectAfterTheTapShowsOneCoverAlertAndKeepsPixels() async throws {
    let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true), isPlaceholder: true)
    appState.connectionManager.setTestState(connectionState: .ready)
    let draft = PixelPaintDraft()
    draft.paint(x: 5, y: 6)
    draft.endStroke()
    let snapshot = draft.image
    let model = SendModel()
    let presentation = Presentation()
    let pause = SubmissionPause()
    let window = try host(Harness(draft: draft, presentation: presentation).environment(\.appState, appState))
    defer { release(window, appState: appState) }
    try await waitUntil("Harness should appear") { presentation.isReady }
    presentation.isPresented = true
    try await waitUntil("Editor should present") { presentedController(in: window) != nil }

    guard let attempt = draft.begin(isReady: { appState.connectionState == .ready }) else {
      Issue.record("Submission should start while connected")
      return
    }
    let task = Task {
      await draft.run(
        attempt,
        isChannel: false,
        senderName: { "" },
        isReady: { appState.connectionState == .ready },
        onSend: { _ in
          model.payloads.append("admitted")
          return true
        },
        onWillSend: {},
        onRejected: { model.sendErrorMessage = nil },
        onAdmitted: {},
        encode: { image, isChannel in
          await pause.pause()
          return try await encodePixelDrawing(image, channel: isChannel)
        }
      )
    }
    draft.track(task)
    await pause.untilPaused()
    appState.connectionManager.setTestState(connectionState: .disconnected, connectedDevice: .some(nil))
    pause.resume()
    await task.value
    try await waitUntil("Cover should show the disconnect alert") {
      alertTitled(
        L10n.Chats.Chats.Alert.UnableToSend.title,
        message: L10n.Chats.Chats.Alert.UnableToSend.message,
        from: window.rootViewController
      )
    }
    #expect(model.payloads.isEmpty)
    #expect(model.sendErrorMessage == nil)
    #expect(draft.image == snapshot)
    #expect(presentedController(in: window) != nil)
  }

  @Test func hardwareReturnSendsOnlyNonEmptyText() async throws {
    let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true), isPlaceholder: true)
    appState.connectionManager.setTestState(connectionState: .ready)
    let model = InputModel()
    model.text = String(repeating: "a", count: InputModel.maxBytes + 1)
    let window = try host(InputHarness(model: model).environment(\.appState, appState))
    defer { release(window, appState: appState) }
    try await waitUntil { descendants(window).contains { $0 is ChatComposerUITextView } }
    let composer = try #require(descendants(window).compactMap { $0 as? ChatComposerUITextView }.first)
    try await waitUntil("Over-limit text reaches the composer") {
      composer.text.count == InputModel.maxBytes + 1 && composer.onSend != nil
    }

    #expect(composer.onSend?() == false)
    #expect(model.submissions.isEmpty)
    #expect(model.text.count == InputModel.maxBytes + 1)

    model.text = "   "
    try await waitUntil("Whitespace reaches the composer") { composer.text == "   " }
    #expect(composer.onSend?() == false)
    #expect(model.submissions.isEmpty)
    #expect(model.text == "   ")

    model.text = "Hello"
    try await waitUntil("Text reaches the composer") { composer.text == "Hello" }
    #expect(composer.onSend?() == true)
    #expect(model.submissions == ["Hello"])
    #expect(model.text.isEmpty)
    #expect(composer.onSend?() == false)
    #expect(model.submissions == ["Hello"])
  }

  private struct Harness: View {
    let draft: PixelPaintDraft
    @Bindable var presentation: Presentation
    var body: some View {
      Color.clear
        .fullScreenCover(isPresented: $presentation.isPresented) {
          PixelPaintEditor(draft: draft, onSend: {})
        }
        .onAppear { presentation.isReady = true }
    }
  }

  private struct SendHarness: View {
    @Bindable var draft: PixelPaintDraft
    @Bindable var model: SendModel
    var body: some View {
      Text(model.text)
        .fullScreenCover(isPresented: $model.isPresented) {
          PixelPaintEditor(draft: draft) {
            guard let attempt = draft.begin(isReady: { model.ready }) else { return }
            let task = Task {
              await draft.run(
                attempt,
                isChannel: false,
                senderName: { "" },
                isReady: { model.ready },
                onSend: { text in
                  model.payloads.append(text)
                  await model.pause.pause()
                  if !model.accept {
                    model.sendErrorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
                  }
                  return model.accept
                },
                onWillSend: {},
                onRejected: { model.sendErrorMessage = nil },
                onAdmitted: { model.isPresented = false }
              )
            }
            draft.track(task)
          }
        }
        .onAppear { model.isReady = true }
    }
  }

  @Observable
  @MainActor
  final class Presentation {
    var isPresented = false
    var isReady = false
  }

  @Observable
  @MainActor
  final class SendModel {
    static let originalText = "Existing text stays"
    var text = originalText
    var isPresented = false
    var isReady = false
    var ready = true
    var accept = true
    var payloads: [String] = []
    var sendErrorMessage: String?
    let pause = SubmissionPause()
  }

  @Observable
  @MainActor
  final class InputModel {
    static let maxBytes = 150
    var text = ""
    var submissions: [String] = []
  }

  private struct InputHarness: View {
    @Bindable var model: InputModel
    var body: some View {
      ChatInputBar(
        text: $model.text,
        focusRequest: 0,
        placeholder: "Text",
        maxBytes: InputModel.maxBytes,
        isEncrypted: true,
        leading: { EmptyView() },
        onSend: { model.submissions.append($0) }
      )
    }
  }

  private func host<Content: View>(_ view: Content) throws -> UIWindow {
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let window = UIWindow(windowScene: scene)
    let controller = UIHostingController(rootView: view)
    window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
    window.rootViewController = controller
    controller.view.frame = window.bounds
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return window
  }

  private func release(_ window: UIWindow, appState: AppState) {
    window.isHidden = true
    window.rootViewController = nil
    window.windowScene = nil
    appState.shutdown()
  }

  private func presentedController(in window: UIWindow) -> UIViewController? {
    window.rootViewController?.presentedViewController
  }

  private func activate(_ identifier: String, in root: NSObject) async -> Bool {
    for _ in 0..<100 {
      if let view = root as? UIView { view.layoutIfNeeded() }
      if let object = matching(identifier, in: root) {
        if let button = object as? UIButton {
          if button.allControlEvents.contains(.primaryActionTriggered) {
            button.performPrimaryAction()
          } else {
            button.sendActions(for: .touchUpInside)
          }
          return true
        }
        if let control = object as? UIControl {
          control.performPrimaryAction()
          return true
        }
        if object.accessibilityActivate() { return true }
      }
      try? await Task.sleep(for: .milliseconds(20))
    }
    Issue.record(AccessibilityDump(accessibilityDump(root)))
    return false
  }

  private func matching(_ name: String, in root: NSObject) -> NSObject? {
    if let labeled = labeledControl(named: name, in: root) { return labeled }
    // A symbol-only bar button has no title until the accessibility tree is populated.
    // Cancellation is the leading bar button and confirmation is the trailing one.
    let buttons = barButtons(in: root)
    guard buttons.count >= 2 else { return nil }
    let direction = (root as? UIView)?.effectiveUserInterfaceLayoutDirection ?? .leftToRight
    let leading = direction == .rightToLeft ? buttons.last : buttons.first
    let trailing = direction == .rightToLeft ? buttons.first : buttons.last
    if name == L10n.Localizable.Common.cancel { return leading }
    if name == L10n.Chats.Chats.Mcoimg.send { return trailing }
    return nil
  }

  private func labeledControl(named name: String, in root: NSObject) -> NSObject? {
    for object in descendants(root) {
      let button = object as? UIButton
      let texts = [
        object.accessibilityLabel,
        button?.title(for: .normal),
        button?.titleLabel?.text,
        button?.titleLabel?.attributedText?.string,
        button?.configuration?.title,
        button?.configuration?.attributedTitle.map { String($0.characters) },
        (object as? UILabel)?.text,
        (object as? UILabel)?.attributedText?.string,
        (object as? UIAccessibilityIdentification)?.accessibilityIdentifier
      ]
      guard texts.contains(where: { $0 == name }) else { continue }
      if object is UIControl { return object }
      var view = object as? UIView
      while let current = view {
        if current is UIControl { return current }
        view = current.superview
      }
      return object
    }
    return nil
  }

  private func barButtons(in root: NSObject) -> [UIView] {
    if let view = root as? UIView { view.layoutIfNeeded() }
    return descendants(root).compactMap { object -> UIView? in
      guard let view = object as? UIView else { return nil }
      guard String(describing: type(of: view)).contains("ButtonBarButton") else { return nil }
      return view
    }.sorted {
      $0.convert($0.bounds, to: nil).minX < $1.convert($1.bounds, to: nil).minX
    }
  }

  private func hasActivityIndicator(in root: NSObject) -> Bool {
    descendants(root).contains { object in
      let type = String(describing: type(of: object))
      return type.contains("ActivityIndicator") || type.contains("ProgressView")
    }
  }

  private func isEnabled(_ identifier: String, in root: NSObject) -> Bool? {
    guard let object = matching(identifier, in: root) else { return nil }
    if let control = object as? UIControl { return control.isEnabled }
    return !object.accessibilityTraits.contains(.notEnabled)
  }

  private func accessibilityDump(_ root: NSObject) -> String {
    let objects = descendants(root)
    let rows = objects.prefix(40).map { object -> String in
      let label = object.accessibilityLabel ?? ""
      let identifier = (object as? UIAccessibilityIdentification)?.accessibilityIdentifier ?? ""
      let button = object as? UIButton
      let title = button?.title(for: .normal) ?? button?.titleLabel?.text ?? button?.configuration?.title ?? ""
      let labelText = (object as? UILabel)?.text ?? ""
      return "\(type(of: object)) title=\(title) text=\(labelText) label=\(label) id=\(identifier)"
    }
    return "Count \(objects.count). \(rows.joined(separator: " | "))"
  }

  private func contains(_ text: String, in window: UIWindow) -> Bool {
    if alertMessage(text, from: window.rootViewController) { return true }
    return descendants(window).contains { object in
      if object.accessibilityLabel == text || object.accessibilityValue as? String == text { return true }
      if let label = object as? UILabel {
        return label.text == text || label.attributedText?.string == text
      }
      return false
    }
  }

  private func alertTitled(_ title: String, message: String, from controller: UIViewController?) -> Bool {
    guard let controller else { return false }
    if let alert = controller as? UIAlertController, alert.title == title, alert.message == message {
      return true
    }
    if alertTitled(title, message: message, from: controller.presentedViewController) { return true }
    return controller.children.contains { alertTitled(title, message: message, from: $0) }
  }

  private func alertMessage(_ text: String, from controller: UIViewController?) -> Bool {
    guard let controller else { return false }
    if let alert = controller as? UIAlertController, alert.message == text { return true }
    if alertMessage(text, from: controller.presentedViewController) { return true }
    return controller.children.contains { alertMessage(text, from: $0) }
  }

  private func descendants(_ root: NSObject) -> [NSObject] {
    var seen: Set<ObjectIdentifier> = []
    var result: [NSObject] = []
    func visit(_ object: NSObject) {
      guard seen.insert(ObjectIdentifier(object)).inserted else { return }
      result.append(object)
      let count = object.accessibilityElementCount()
      if count != NSNotFound, count > 0 {
        for index in 0..<count {
          if let child = object.accessibilityElement(at: index) as? NSObject, child !== object {
            visit(child)
          }
        }
      } else if let elements = object.accessibilityElements, !elements.isEmpty {
        for case let child as NSObject in elements where child !== object {
          visit(child)
        }
      }
      if let view = object as? UIView {
        view.subviews.forEach(visit)
      }
    }
    visit(root)
    return result
  }
}
