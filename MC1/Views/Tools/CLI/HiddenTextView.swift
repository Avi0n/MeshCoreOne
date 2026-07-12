import SwiftUI
import UIKit

/// Invisible UITextView that captures keyboard input while allowing visual rendering elsewhere.
/// Tracks cursor position and supports cursor movement via callbacks.
struct HiddenTextViewFocusable: UIViewRepresentable {
  @Binding var text: String
  @Binding var isFocused: Bool
  @Binding var cursorPosition: Int
  var onSubmit: () -> Void
  var onHistoryUp: () -> Void
  var onHistoryDown: () -> Void
  var onRightArrowAtEnd: () -> Void
  var onTabComplete: () -> Void

  func makeUIView(context: Context) -> FocusableTextView {
    let textView = FocusableTextView()
    textView.customDelegate = context.coordinator
    textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
    textView.autocorrectionType = .no
    textView.autocapitalizationType = .none
    textView.spellCheckingType = .no
    textView.returnKeyType = .default

    // Make invisible but still interactive
    textView.backgroundColor = .clear
    textView.textColor = .clear
    textView.tintColor = .clear

    return textView
  }

  func updateUIView(_ textView: FocusableTextView, context: Context) {
    // Update text if changed externally
    if textView.text != text {
      textView.text = text
    }

    // Sync cursor position from SwiftUI to UITextView
    let clampedPosition = min(cursorPosition, textView.text.count)
    if let startPosition = textView.position(from: textView.beginningOfDocument, offset: clampedPosition) {
      let currentOffset = textView.selectedTextRange.map {
        textView.offset(from: textView.beginningOfDocument, to: $0.start)
      } ?? 0

      if currentOffset != clampedPosition {
        textView.selectedTextRange = textView.textRange(from: startPosition, to: startPosition)
      }
    }

    // Manage focus. The accessory bar is gated on `isFocused`, which the
    // delegate clears only via `textViewDidEndEditing`. That never fires when
    // the responder request can't be honored (view not yet in a window), so
    // reconcile the binding here to keep the bar from stranding with no keyboard.
    if isFocused, !textView.isFirstResponder {
      Task { @MainActor in
        guard textView.window != nil, textView.becomeFirstResponder() else {
          isFocused = false
          return
        }
      }
    } else if !isFocused, textView.isFirstResponder {
      textView.resignFirstResponder()
    }
  }

  func makeCoordinator() -> Coordinator {
    Coordinator(
      text: $text,
      isFocused: $isFocused,
      cursorPosition: $cursorPosition,
      onSubmit: onSubmit,
      onHistoryUp: onHistoryUp,
      onHistoryDown: onHistoryDown,
      onRightArrowAtEnd: onRightArrowAtEnd,
      onTabComplete: onTabComplete
    )
  }

  class Coordinator: NSObject, FocusableTextViewDelegate {
    @Binding var text: String
    @Binding var isFocused: Bool
    @Binding var cursorPosition: Int
    let onSubmit: () -> Void
    let onHistoryUp: () -> Void
    let onHistoryDown: () -> Void
    let onRightArrowAtEnd: () -> Void
    let onTabComplete: () -> Void

    init(
      text: Binding<String>,
      isFocused: Binding<Bool>,
      cursorPosition: Binding<Int>,
      onSubmit: @escaping () -> Void,
      onHistoryUp: @escaping () -> Void,
      onHistoryDown: @escaping () -> Void,
      onRightArrowAtEnd: @escaping () -> Void,
      onTabComplete: @escaping () -> Void
    ) {
      _text = text
      _isFocused = isFocused
      _cursorPosition = cursorPosition
      self.onSubmit = onSubmit
      self.onHistoryUp = onHistoryUp
      self.onHistoryDown = onHistoryDown
      self.onRightArrowAtEnd = onRightArrowAtEnd
      self.onTabComplete = onTabComplete
    }

    func textViewDidChange(_ textView: UITextView) {
      let currentText = textView.text ?? ""
      if let newlineIndex = currentText.firstIndex(of: "\n") {
        // A paste can deliver the command and its newline in one change;
        // sync the binding before clearing so onSubmit reads the full text.
        text = String(currentText[..<newlineIndex])
        textView.text = ""
        Task { @MainActor in
          // Call onSubmit first while tab selection state is still valid
          // The handler will either apply a suggestion or execute the command
          self.onSubmit()
          self.cursorPosition = self.text.count
        }
      } else {
        text = textView.text
        updateCursorPosition(from: textView)
      }
    }

    func textViewDidChangeSelection(_ textView: UITextView) {
      updateCursorPosition(from: textView)
    }

    private func updateCursorPosition(from textView: UITextView) {
      guard let selectedRange = textView.selectedTextRange else {
        cursorPosition = text.count
        return
      }
      cursorPosition = textView.offset(from: textView.beginningOfDocument, to: selectedRange.start)
    }

    func textViewDidBeginEditing(_ textView: UITextView) {
      Task { @MainActor in
        self.isFocused = true
      }
    }

    func textViewDidEndEditing(_ textView: UITextView) {
      Task { @MainActor in
        self.isFocused = false
      }
    }

    func historyUp() {
      onHistoryUp()
    }

    func historyDown() {
      onHistoryDown()
    }

    func moveCursor(by offset: Int, in textView: FocusableTextView) {
      guard let selectedRange = textView.selectedTextRange else { return }
      guard let newPosition = textView.position(from: selectedRange.start, offset: offset) else { return }
      textView.selectedTextRange = textView.textRange(from: newPosition, to: newPosition)
    }

    func moveCursorToEnd(in textView: FocusableTextView) {
      guard let endPosition = textView.position(from: textView.endOfDocument, offset: 0) else { return }
      textView.selectedTextRange = textView.textRange(from: endPosition, to: endPosition)
    }

    func rightArrowAtEnd() {
      onRightArrowAtEnd()
    }

    func tabComplete() {
      onTabComplete()
    }
  }
}

// MARK: - Custom UITextView with Key Commands

protocol FocusableTextViewDelegate: UITextViewDelegate {
  func historyUp()
  func historyDown()
  func moveCursor(by offset: Int, in textView: FocusableTextView)
  func moveCursorToEnd(in textView: FocusableTextView)
  func rightArrowAtEnd()
  func tabComplete()
}

class FocusableTextView: UITextView {
  weak var customDelegate: FocusableTextViewDelegate? {
    didSet { delegate = customDelegate }
  }

  override var keyCommands: [UIKeyCommand]? {
    // Tab is a reserved focus-traversal key: the UIKit focus engine claims it
    // before SwiftUI's .onKeyPress can see it (notably when running the iPad
    // build on a Mac), so it must be intercepted here on the first responder
    // with priority over the focus system.
    let tab = UIKeyCommand(input: "\t", modifierFlags: [], action: #selector(handleTab))
    tab.wantsPriorityOverSystemBehavior = true
    return [
      UIKeyCommand(input: UIKeyCommand.inputUpArrow, modifierFlags: [], action: #selector(handleUpArrow)),
      UIKeyCommand(input: UIKeyCommand.inputDownArrow, modifierFlags: [], action: #selector(handleDownArrow)),
      tab
    ]
    // Note: Left/right arrows handled via pressesBegan to intercept before UITextView's default handling
  }

  @objc private func handleUpArrow() {
    customDelegate?.historyUp()
  }

  @objc private func handleDownArrow() {
    customDelegate?.historyDown()
  }

  @objc private func handleTab() {
    customDelegate?.tabComplete()
  }

  // MARK: - Hardware Keyboard Press Handling

  override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
    guard let key = presses.first?.key else {
      super.pressesBegan(presses, with: event)
      return
    }

    switch key.keyCode {
    case .keyboardRightArrow:
      // Check if cursor is at end of text
      let cursorOffset: Int = if let selectedRange = selectedTextRange {
        offset(from: beginningOfDocument, to: selectedRange.start)
      } else {
        text.count
      }

      if cursorOffset >= text.count {
        // Cursor at end - notify delegate for ghost text acceptance
        customDelegate?.rightArrowAtEnd()
        // Don't call super - prevent default behavior
        return
      } else {
        // Cursor not at end - let UITextView handle normal cursor movement
        super.pressesBegan(presses, with: event)
      }

    default:
      super.pressesBegan(presses, with: event)
    }
  }

  /// Public methods for programmatic cursor movement (called from accessory bar buttons)
  func moveCursorLeft() {
    customDelegate?.moveCursor(by: -1, in: self)
  }

  func moveCursorRight() {
    customDelegate?.moveCursor(by: 1, in: self)
  }

  func moveCursorToEnd() {
    customDelegate?.moveCursorToEnd(in: self)
  }
}
