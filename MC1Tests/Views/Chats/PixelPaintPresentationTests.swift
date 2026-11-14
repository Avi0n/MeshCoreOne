import Foundation
@testable import MC1Services
import SwiftUI
import Testing
import UIKit
@testable import MC1

@Suite("Pixel paint presentation", .serialized)
@MainActor
struct PixelPaintPresentationTests {
  @Test func fullScreenEditorBlocksDismissalAndDoneRetainsPixelsThroughReopenAndDisconnect() async throws {
    let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true), isPlaceholder: true)
    let draft = PixelPaintDraft()
    let presentation = Presentation()
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let window = UIWindow(windowScene: scene)
    let controller = UIHostingController(rootView: Harness(draft: draft, presentation: presentation).environment(\.appState, appState))
    window.rootViewController = controller
    window.makeKeyAndVisible()
    defer {
      window.isHidden = true
      window.rootViewController = nil
      window.windowScene = nil
      appState.shutdown()
    }

    try await waitUntil("Harness should appear before presenting") { presentation.isReady }
    presentation.isPresented = true
    try await waitUntil("Editor should present") { controller.presentedViewController != nil }
    let presented = try #require(controller.presentedViewController)
    #expect(presented.modalPresentationStyle == UIModalPresentationStyle.fullScreen || presented.modalPresentationStyle == UIModalPresentationStyle.overFullScreen)
    try await waitUntil("Editor should block interactive dismissal") {
      presented.isModalInPresentation
        || presented.presentationController.map {
          $0.delegate?.presentationControllerShouldDismiss?($0) == false
        } == true
    }
    do {
      try await waitUntil("Done should be accessible") { descendants(presented.view).contains { $0.accessibilityLabel == L10n.Localizable.Common.done } }
    } catch {
      let labels = descendants(window).compactMap { object -> String? in
        if let label = object.accessibilityLabel { return "\(type(of: object)): \(label) [\((object as? UIView)?.accessibilityIdentifier ?? "")]" }
        return nil
      }
      Issue.record("Window accessibility labels: \(labels)")
      throw error
    }
    draft.paint(x: 1, y: 2)
    draft.endStroke()
    let pixels = draft.image
    appState.connectionManager.setTestState(connectionState: .disconnected, connectedDevice: .some(nil))
    #expect(controller.presentedViewController != nil)
    #expect(draft.image == pixels)
    try #require(await activate(L10n.Localizable.Common.done, in: try #require(controller.presentedViewController).view))
    try await waitUntil("Done should dismiss the editor") { controller.presentedViewController == nil }
    #expect(draft.isStaged)
    #expect(draft.image == pixels)
    presentation.isPresented = true
    try await waitUntil("Editor should reopen") { controller.presentedViewController != nil }
    #expect(draft.image == pixels)
    try #require(await activate(L10n.Localizable.Common.done, in: try #require(controller.presentedViewController).view))
    try await waitUntil("Done should dismiss the editor") { controller.presentedViewController == nil }
    #expect(draft.image == pixels)
  }

  @Test func normalHardwareSendStagesImageWithoutClearingExistingText() async throws {
    let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true), isPlaceholder: true)
    appState.connectionManager.setTestState(connectionState: .ready)
    let model = InputModel()
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let window = UIWindow(windowScene: scene)
    window.rootViewController = UIHostingController(rootView: InputHarness(model: model).environment(\.appState, appState))
    window.makeKeyAndVisible()
    defer {
      window.isHidden = true
      window.rootViewController = nil
      window.windowScene = nil
      appState.shutdown()
    }
    try await waitUntil { descendants(window).contains { $0 is ChatComposerUITextView } }
    let composer = try #require(descendants(window).compactMap { $0 as? ChatComposerUITextView }.first)
    #expect(composer.onSend?() == true)
    #expect(model.imageSubmissions == 1)
    #expect(model.textSubmissions == 0)
    #expect(model.text == InputModel.originalText)
    try await Task.sleep(for: .milliseconds(50))
    #expect(composer.onSend?() == false)
    #expect(model.imageSubmissions == 1)
  }

  private struct Harness: View {
    let draft: PixelPaintDraft
    @Bindable var presentation: Presentation
    var body: some View {
      Color.clear
        .fullScreenCover(isPresented: $presentation.isPresented) { PixelPaintEditor(draft: draft) }
        .onAppear { presentation.isReady = true }
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
  final class InputModel {
    static let originalText = String(repeating: "Existing text ", count: 20)
    var text = originalText
    var imageSubmissions = 0
    var textSubmissions = 0
    var submitting = false
  }

  private struct InputHarness: View {
    @Bindable var model: InputModel
    var body: some View {
      ChatInputBar(text: $model.text, focusRequest: 0, placeholder: "Text", maxBytes: 150,
                   isEncrypted: true, leading: { EmptyView() },
                   onSend: { _ in model.textSubmissions += 1 }, hasAttachment: true,
                   isSubmittingAttachment: model.submitting, onSendAttachment: {
        model.submitting = true
        model.imageSubmissions += 1
      })
    }
  }

  private func activate(_ identifier: String, in window: UIView) async -> Bool {
    for _ in 0..<100 {
      window.layoutIfNeeded()
      if let object = descendants(window).first(where: { $0.accessibilityLabel == identifier }) {
        if let button = object as? UIButton {
          if button.allControlEvents.contains(.primaryActionTriggered) { button.performPrimaryAction() } else { button.sendActions(for: .touchUpInside) }
          return true
        }
        if object.accessibilityActivate() { return true }
      }
      try? await Task.sleep(for: .milliseconds(20))
    }
    return false
  }

  private func descendants(_ root: NSObject) -> [NSObject] {
    var seen: Set<ObjectIdentifier> = []
    var result: [NSObject] = []
    func visit(_ object: NSObject) {
      guard seen.insert(ObjectIdentifier(object)).inserted else { return }
      result.append(object)
      if let view = object as? UIView { view.subviews.forEach(visit) }
      let count = object.accessibilityElementCount()
      if count != NSNotFound, count > 0 {
        for index in 0..<count {
          if let child = object.accessibilityElement(at: index) as? NSObject { visit(child) }
        }
      }
    }
    visit(root)
    return result
  }
}
