import SwiftUI
import UIKit

/// A growing UITextView composer with one-shot programmatic focus.
/// Focus tokens prevent native dismissal from being undone by later view updates.
struct ChatComposerTextView: UIViewRepresentable {
  @Binding var text: String
  /// Incremented by the parent to request focus; compared against the
  /// coordinator's last-applied value so each request fires exactly once.
  let focusRequest: Int
  let isEncrypted: Bool
  /// Receives the text view on creation so the parent can finalize IME
  /// composition before reading the text to send.
  let proxy: ChatComposerProxy
  /// A true result consumes Return and retains focus; a false result inserts a newline.
  let onSend: () -> Bool

  func makeUIView(context: Context) -> ChatComposerUITextView {
    let textView = ChatComposerUITextView(usingTextLayoutManager: false)
    textView.delegate = context.coordinator
    textView.onSend = onSend
    textView.font = UIFont.preferredFont(forTextStyle: .body)
    textView.adjustsFontForContentSizeCategory = true
    textView.backgroundColor = .clear
    textView.inlinePredictionType = .default
    // Keep UITextView scrolling enabled so caret visibility never scrolls the containing timeline.
    // sizeThatFits limits height, so internal scrolling starts only beyond the visible-line cap.
    textView.isScrollEnabled = true
    textView.alwaysBounceVertical = false
    textView.textContainerInset = UIEdgeInsets(
      top: ChatComposerUITextView.verticalInset,
      left: 0,
      bottom: ChatComposerUITextView.verticalInset,
      right: 0
    )
    textView.textContainer.lineFragmentPadding = 0
    textView.setContentHuggingPriority(.defaultLow, for: .horizontal)
    textView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    proxy.textView = textView

    textView.accessibilityHint = L10n.Chats.Chats.Input.accessibilityHint
    textView.applyComposerAccessibility(isEncrypted: isEncrypted)
    return textView
  }

  func updateUIView(_ textView: ChatComposerUITextView, context: Context) {
    context.coordinator.parent = self
    textView.onSend = onSend
    textView.applyComposerAccessibility(isEncrypted: isEncrypted)

    if textView.text != text {
      if text.isEmpty {
        textView.clearAfterSend()
      } else {
        textView.text = text
      }
    }

    // Become first responder once per token increment. There is no resign
    // path, so native dismissal is left untouched.
    if focusRequest != context.coordinator.lastFocusRequest {
      context.coordinator.lastFocusRequest = focusRequest
      if !textView.isFirstResponder {
        Task { @MainActor in
          guard textView.window != nil else { return }
          textView.becomeFirstResponder()
        }
      }
    }
  }

  func sizeThatFits(_ proposal: ProposedViewSize, uiView: ChatComposerUITextView, context: Context) -> CGSize? {
    if let width = proposal.width, width.isFinite, width > 0 {
      return CGSize(width: width, height: uiView.clampedHeight(forWidth: width))
    }
    // Return minimal ideal width so the HStack offers its remaining space instead of the single-line text width.
    return CGSize(width: 0, height: uiView.clampedHeight(forWidth: max(uiView.bounds.width, 1)))
  }

  func makeCoordinator() -> Coordinator {
    Coordinator(self)
  }

  @MainActor
  final class Coordinator: NSObject, UITextViewDelegate {
    var parent: ChatComposerTextView
    /// Last `focusRequest` value acted on, so a request fires only once.
    var lastFocusRequest: Int

    init(_ parent: ChatComposerTextView) {
      self.parent = parent
      lastFocusRequest = parent.focusRequest
    }

    func textViewDidChange(_ textView: UITextView) {
      // Clearing text in updateUIView invokes this delegate synchronously; equal text needs no binding write.
      if parent.text != textView.text {
        parent.text = textView.text
      }
    }
  }
}

/// `UITextView` subclass that sends on an unmodified hardware Return and measures a
/// wrapped height from one line up to `maxVisibleLines`, scrolling beyond that.
final class ChatComposerUITextView: UITextView {
  static let verticalInset: CGFloat = 8
  static let maxVisibleLines = 5

  /// Key-command input for a hardware Return.
  private static let returnInput = "\r"
  /// Key-command input for a physical numpad Enter (Mac).
  private static let numpadEnterInput = "\u{3}"

  var onSend: (() -> Bool)?

  @discardableResult
  override func becomeFirstResponder() -> Bool {
    if !isFirstResponder, window != nil {
      var responder = next
      while let current = responder {
        if let owner = current as? any ChatKeyboardPresentationPreparing {
          owner.prepareForKeyboardPresentation()
          break
        }
        responder = current.next
      }
    }
    return super.becomeFirstResponder()
  }

  /// UITextView reports its text as `accessibilityValue` when that property is left unset.
  /// Leave it unset so encryption status stays on the label.
  private var accessibilityValueIsUnset = true

  override var accessibilityValue: String? {
    get { accessibilityValueIsUnset ? nil : super.accessibilityValue }
    set {
      accessibilityValueIsUnset = newValue == nil
      super.accessibilityValue = newValue
    }
  }

  func applyComposerAccessibility(isEncrypted: Bool) {
    let status = isEncrypted
      ? L10n.Chats.Chats.Input.encrypted
      : L10n.Chats.Chats.Input.notEncrypted
    accessibilityLabel = "\(L10n.Chats.Chats.Input.accessibilityLabel), \(status)"
    // accessibilityValue stays nil. UITextView speaks the field text through that property.
    accessibilityValue = nil
  }

  /// Replace the committed text range to avoid caret-reset animation during field collapse.
  /// Notify inputDelegate so keyboard predictions match the empty document.
  func clearAfterSend() {
    inputDelegate?.textWillChange(self)
    inputDelegate?.selectionWillChange(self)
    unmarkText()
    if let fullRange = textRange(from: beginningOfDocument, to: endOfDocument) {
      replace(fullRange, withText: "")
    }
    inputDelegate?.selectionDidChange(self)
    inputDelegate?.textDidChange(self)
    contentOffset = .zero
  }

  /// Flush autocorrect and commit marked IME text synchronously so sending captures the displayed text.
  func commitPendingInput() {
    guard isFirstResponder else { return }
    inputDelegate?.selectionWillChange(self)
    inputDelegate?.selectionDidChange(self)
    unmarkText()
  }

  private var lineHeight: CGFloat {
    (font ?? UIFont.preferredFont(forTextStyle: .body)).lineHeight
  }

  private var minHeight: CGFloat {
    ceil(lineHeight) + textContainerInset.top + textContainerInset.bottom
  }

  private var maxHeight: CGFloat {
    ceil(lineHeight * CGFloat(Self.maxVisibleLines)) + textContainerInset.top + textContainerInset.bottom
  }

  /// Height the text needs when wrapped to `width`, clamped to the visible-line
  /// range, enabling internal scrolling once the text exceeds the cap.
  func clampedHeight(forWidth width: CGFloat) -> CGFloat {
    let innerWidth = max(1, width - textContainerInset.left - textContainerInset.right)
    let measuringFont = font ?? UIFont.preferredFont(forTextStyle: .body)
    let used = (text as NSString).boundingRect(
      with: CGSize(width: innerWidth, height: .greatestFiniteMagnitude),
      options: [.usesLineFragmentOrigin, .usesFontLeading],
      attributes: [.font: measuringFont],
      context: nil
    ).height
    let full = ceil(used) + textContainerInset.top + textContainerInset.bottom
    return min(max(full, minHeight), maxHeight)
  }

  override var keyCommands: [UIKeyCommand]? {
    // During IME composition, Return commits the candidate; explicit modified commands otherwise insert newlines.
    guard markedTextRange == nil else { return nil }
    let action = #selector(handleReturnCommand(_:))
    let commands = [
      UIKeyCommand(input: Self.returnInput, modifierFlags: [], action: action),
      UIKeyCommand(input: Self.numpadEnterInput, modifierFlags: [], action: action),
      UIKeyCommand(input: Self.returnInput, modifierFlags: .shift, action: action),
      UIKeyCommand(input: Self.returnInput, modifierFlags: .alternate, action: action)
    ]
    commands.forEach { $0.wantsPriorityOverSystemBehavior = true }
    return commands
  }

  @objc private func handleReturnCommand(_ command: UIKeyCommand) {
    // Recheck composition at dispatch because it can start after the command list was built.
    guard markedTextRange == nil else {
      unmarkText()
      return
    }
    // Shift+Return and Option+Return insert a newline; an unmodified Return or
    // numpad Enter sends, falling back to a newline when the send is gated off.
    let insertsNewline = !command.modifierFlags.isDisjoint(with: [.shift, .alternate])
    if insertsNewline || onSend?() != true {
      insertText("\n")
    }
  }
}
