import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

/// Hosts the production Contact Info section and types through the real
/// UITextView so a binding or drawing failure matches the user's symptom:
/// typed characters missing while focused, and Apply sending a stale value.
@Suite("NodeContactInfoSection typing", .serialized)
@MainActor
struct NodeContactInfoSectionTests {
  private static let existingInfo = "KD7ABC"
  private static let typedSuffix = " extra"
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)

  private struct Harness: View {
    @Bindable var settings: NodeSettingsViewModel
    @FocusState var focusedField: NodeSettingsField?

    var body: some View {
      NavigationStack {
        Form {
          NodeContactInfoSection(settings: settings, focusedField: $focusedField)
        }
        .toolbar {
          ToolbarItemGroup(placement: .keyboard) {
            Spacer()
            Button(L10n.RemoteNodes.RemoteNodes.Settings.done) {
              focusedField = nil
            }
          }
        }
      }
    }
  }

  @MainActor
  final class CommandRecorder {
    private(set) var commands: [String] = []
    var reply = "OK"
    func send(_ id: UUID, _ command: String, _ timeout: Duration) async throws -> String {
      commands.append(command)
      return reply
    }
  }

  @Test
  func `typing into contact info updates ownerInfo while focused`() async throws {
    let settings = makeLoadedSettings()
    let (window, _) = mount(settings: settings)
    defer { window.isHidden = true }

    let textView = try await waitForTextView(in: window)
    textView.inputView = UIView()
    #expect(textView.becomeFirstResponder())
    textView.insertText(Self.typedSuffix)
    window.layoutIfNeeded()

    try await waitUntil("ownerInfo never picked up typed text") {
      settings.ownerInfo == Self.existingInfo + Self.typedSuffix
    }
    #expect(
      textView.text.contains(Self.typedSuffix),
      "typed characters must be visible in the field while focused; after Done is too late"
    )
  }

  @Test
  func `contact info text color stays readable while the field is focused`() async throws {
    let settings = makeLoadedSettings()
    let (window, _) = mount(settings: settings)
    defer { window.isHidden = true }

    let textView = try await waitForTextView(in: window)
    let unfocusedColor = textView.textColor
    textView.inputView = UIView()
    #expect(textView.becomeFirstResponder())
    try await Task.sleep(for: .milliseconds(50))

    let focusedColor = textView.textColor
    let alpha = focusedColor?.cgColor.alpha ?? -1
    #expect(
      alpha > 0.2,
      "focused text color is invisible (unfocused=\(String(describing: unfocusedColor)), focused=\(String(describing: focusedColor)), bg=\(String(describing: textView.backgroundColor)))"
    )
    #expect(
      textView.bounds.height > 8,
      "focused field collapsed to \(textView.bounds.size), which would hide typed glyphs"
    )
  }

  @Test
  func `apply after typing sends set owner.info with the edited value`() async throws {
    let recorder = CommandRecorder()
    let settings = makeLoadedSettings(recorder: recorder)
    let (window, _) = mount(settings: settings)
    defer { window.isHidden = true }

    let textView = try await waitForTextView(in: window)
    textView.inputView = UIView()
    #expect(textView.becomeFirstResponder())
    textView.insertText(Self.typedSuffix)
    _ = textView.resignFirstResponder()
    window.layoutIfNeeded()

    try await waitUntil("ownerInfo never committed after resign") {
      settings.ownerInfo == Self.existingInfo + Self.typedSuffix
    }

    await settings.applyContactInfoSettings()

    #expect(recorder.commands == ["set owner.info \(Self.existingInfo + Self.typedSuffix)"])
  }

  @Test
  func `backspace on loaded contact info updates ownerInfo`() async throws {
    let settings = makeLoadedSettings()
    let (window, _) = mount(settings: settings)
    defer { window.isHidden = true }

    let textView = try await waitForTextView(in: window)
    textView.inputView = UIView()
    #expect(textView.becomeFirstResponder())
    textView.deleteBackward()
    window.layoutIfNeeded()

    try await waitUntil("backspace never reached ownerInfo") {
      settings.ownerInfo == String(Self.existingInfo.dropLast())
    }
  }

  private func makeLoadedSettings(recorder: CommandRecorder = CommandRecorder()) -> NodeSettingsViewModel {
    let settings = NodeSettingsViewModel()
    let session = RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test Repeater",
      role: .repeater,
      isConnected: true,
      permissionLevel: .admin
    )
    settings.configure(session: session, sendCommand: recorder.send, sendRawCommand: recorder.send)
    settings.setNodeInfo(firmwareVersion: "v1.17.1", name: "Test Repeater", ownerInfo: Self.existingInfo)
    settings.isContactInfoExpanded = true
    return settings
  }

  private func mount(
    settings: NodeSettingsViewModel
  ) -> (UIWindow, UIHostingController<Harness>) {
    let controller = UIHostingController(rootView: Harness(settings: settings))
    let window = UIWindow(frame: Self.viewport)
    window.rootViewController = controller
    window.isHidden = false
    window.layoutIfNeeded()
    return (window, controller)
  }

  private func waitForTextView(in window: UIWindow) async throws -> UITextView {
    var found: UITextView?
    try await waitUntil("contact info UITextView never appeared") {
      found = findTextView(in: window)
      return found != nil
    }
    return try #require(found)
  }

  private func findTextView(in view: UIView) -> UITextView? {
    if let textView = view as? UITextView { return textView }
    for subview in view.subviews {
      if let found = findTextView(in: subview) { return found }
    }
    return nil
  }
}
