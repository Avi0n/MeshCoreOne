@testable import MC1
import Testing
import UIKit

/// Encryption status belongs on the composer's label. The value stays unset so
/// VoiceOver speaks the text in the field.
@MainActor
@Suite("Chat composer VoiceOver")
struct ChatComposerAccessibilityTests {
  private let message = "Meet at the bridge"

  @Test
  func `encryption status stays out of the value`() {
    let textView = ChatComposerUITextView(usingTextLayoutManager: false)
    textView.text = message

    textView.applyComposerAccessibility(isEncrypted: false)

    #expect(textView.accessibilityValue == nil)
    #expect(textView.accessibilityLabel == label(isEncrypted: false))
    #expect(textView.accessibilityLabel?.contains(message) != true)
    #expect(textView.text == message)

    textView.applyComposerAccessibility(isEncrypted: true)

    #expect(textView.accessibilityValue == nil)
    #expect(textView.accessibilityLabel == label(isEncrypted: true))
    #expect(textView.accessibilityLabel?.contains(message) != true)
    #expect(textView.text == message)
  }

  private func label(isEncrypted: Bool) -> String {
    let status = isEncrypted
      ? L10n.Chats.Chats.Input.encrypted
      : L10n.Chats.Chats.Input.notEncrypted
    return "\(L10n.Chats.Chats.Input.accessibilityLabel), \(status)"
  }
}
