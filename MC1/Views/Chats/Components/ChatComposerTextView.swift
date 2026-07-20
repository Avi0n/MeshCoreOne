import SwiftUI
import UIKit

/// A growing, multi-line message composer backed by `UITextView`.
///
/// A hardware Return sends via `onSend`; Shift+Return and Option+Return insert a
/// newline. Return is a `UIKeyCommand` with `wantsPriorityOverSystemBehavior` so it
/// beats the text view's own newline, with explicit Shift/Option newline commands. The
/// on-screen keyboard and IME commits still insert a newline, and a gated-off send
/// (byte limit, disconnected, cooling down) inserts a newline rather than nothing.
///
/// Programmatic focus is driven by the `focusRequest` token: each new value
/// raises the keyboard once. There is no resign path; dismissal happens natively.
/// A token is used rather than `@FocusState`/`Bool` because focus is a one-shot
/// intent: a `@FocusState` with no `.focused()` consumer is reset by the focus
/// engine and never drives `becomeFirstResponder()`, and a sticky `Bool` would
/// re-raise the keyboard on the next view update after a native dismissal.
///
/// `sizeThatFits` reports a flexible width and a content-driven height clamped to
/// `maxVisibleLines`, so the field wraps to the offered width and grows downward
/// instead of stretching the input bar.
struct ChatComposerTextView: UIViewRepresentable {
  @Binding var text: String
  /// Incremented by the parent to request focus; compared against the
  /// coordinator's last-applied value so each request fires exactly once.
  let focusRequest: Int
  let isEncrypted: Bool
  /// Receives the text view on creation so the parent can finalize IME
  /// composition before reading the text to send.
  let proxy: ChatComposerProxy
  /// Attempts a send. Returns `true` when a message was sent (Return is then
  /// consumed and focus retained), `false` when gated off (Return inserts a
  /// newline instead).
  let onSend: () -> Bool
  /// Called when the field becomes first responder.
  let onFocus: () -> Void

  func makeUIView(context: Context) -> ChatComposerUITextView {
    let textView = ChatComposerUITextView(usingTextLayoutManager: false)
    textView.delegate = context.coordinator
    textView.onSend = onSend
    textView.font = UIFont.preferredFont(forTextStyle: .body)
    textView.adjustsFontForContentSizeCategory = true
    textView.backgroundColor = .clear
    textView.inlinePredictionType = .default
    // Keep scrolling enabled at all sizes. When it is disabled, UITextView's
    // private scroll-to-visible adjusts the containing scroll view instead of
    // itself, which throws the caret outside the field while it collapses after
    // a send. Height is driven by `sizeThatFits`, so the view only ever scrolls
    // its own content once it exceeds the visible-line cap.
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

    textView.accessibilityLabel = L10n.Chats.Chats.Input.accessibilityLabel
    textView.accessibilityHint = L10n.Chats.Chats.Input.accessibilityHint
    return textView
  }

  func updateUIView(_ textView: ChatComposerUITextView, context: Context) {
    context.coordinator.parent = self
    textView.onSend = onSend
    textView.accessibilityValue = isEncrypted
      ? L10n.Chats.Chats.Input.encrypted
      : L10n.Chats.Chats.Input.notEncrypted

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
    // Ideal/unbounded query: claim minimal width so the surrounding HStack
    // treats the field as fully flexible and hands it the leftover width,
    // rather than stretching to the text's single-line content width.
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

    func textViewDidBeginEditing(_: UITextView) {
      parent.onFocus()
    }

    func textViewDidChange(_ textView: UITextView) {
      // Skip the redundant write when the text already matches the binding.
      // The post-send clear edits the field from inside `updateUIView`, which
      // fires this delegate synchronously; writing the binding mid-update is
      // disallowed, and the value is already empty there, so guarding avoids it.
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

  /// Clears the field after a send through the text-input editing path.
  ///
  /// Assigning `text = ""` runs a private caret-reset that lives in the text
  /// input system, outside `UIView`/`CATransaction` control, so it can't be
  /// suppressed by `performWithoutAnimation`. Replacing the full range instead
  /// moves the caret as a discrete edit, the same as deleting, which keeps it
  /// from animating to the start as the field collapses. `unmarkText` first
  /// commits any in-progress IME composition so the replace deletes everything.
  ///
  /// The `inputDelegate` notifications bracket the edit so the keyboard resyncs
  /// its prediction context to the empty document; otherwise the edit bypasses the
  /// text-input pipeline and leaves stale ghost-text or extends the last sent word.
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

  /// Commits a marked IME composition and any pending autocorrect so a send
  /// captures what the field shows. The input-delegate notifications flush the
  /// autocorrect candidate; `unmarkText` commits the composition. Both are
  /// synchronous, so the bound text is current the moment this returns.
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
    // Register nothing during IME composition so Return commits the candidate. Shift
    // and Option Return get explicit newline commands; without them the unmodified
    // command's priority captures the modified press and would send.
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
    // A live composition takes Return as a candidate commit, never a send or
    // newline. Re-checked here because the command list is built ahead of
    // dispatch and may be served from before composition began.
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
