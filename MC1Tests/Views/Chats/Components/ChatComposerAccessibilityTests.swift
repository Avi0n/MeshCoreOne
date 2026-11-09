@testable import MC1
import Testing
import UIKit

/// The empty field keeps its name and encryption status. A draft leaves the
/// label and hint unset so VoiceOver speaks the entered text.
@MainActor
@Suite("Chat composer VoiceOver")
struct ChatComposerAccessibilityTests {
  private let message = "Meet at the bridge"

  @Test
  func `empty field speaks its name and encryption status`() {
    let textView = ChatComposerUITextView(usingTextLayoutManager: false)

    textView.applyComposerAccessibility(isEncrypted: false)

    #expect(textView.accessibilityValue == nil)
    #expect(textView.accessibilityLabel == label(isEncrypted: false))
    #expect(textView.accessibilityHint == L10n.Chats.Chats.Input.accessibilityHint)

    textView.applyComposerAccessibility(isEncrypted: true)

    #expect(textView.accessibilityValue == nil)
    #expect(textView.accessibilityLabel == label(isEncrypted: true))
    #expect(textView.accessibilityHint == L10n.Chats.Chats.Input.accessibilityHint)
  }

  @Test
  func `draft speaks the entered text without the name or hint`() {
    let textView = ChatComposerUITextView(usingTextLayoutManager: false)
    textView.text = message

    textView.applyComposerAccessibility(isEncrypted: false)

    #expect(textView.accessibilityValue == nil)
    #expect(textView.accessibilityLabel == nil)
    #expect(textView.accessibilityHint == nil)

    textView.applyComposerAccessibility(isEncrypted: true)

    #expect(textView.accessibilityLabel == nil)
    #expect(textView.accessibilityHint == nil)
  }

  private func label(isEncrypted: Bool) -> String {
    let status = isEncrypted
      ? L10n.Chats.Chats.Input.encrypted
      : L10n.Chats.Chats.Input.notEncrypted
    return "\(L10n.Chats.Chats.Input.accessibilityLabel), \(status)"
  }
}
