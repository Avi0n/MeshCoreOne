import SwiftUI
import UIKit

/// A growing, multi-line message composer backed by `UITextView`.
///
/// Backing the field with UIKit lets a hardware keyboard's plain Return send the
/// message (via `onSend`) while Shift+Return and Option+Return insert a newline.
/// Return is intercepted in `pressesBegan` where `UIKey.modifierFlags` reports the
/// Shift modifier reliably (SwiftUI's `onKeyPress` omits it for Return). When
/// `onSend` reports a send actually fired, not forwarding to `super` keeps the view
/// first responder, so focus is retained without a keyboard flicker. When the send
/// is gated off (over the byte limit, disconnected, cooling down), Return falls
/// through to `super` so it inserts a newline instead of vanishing. The on-screen
/// keyboard's Return never reaches `pressesBegan`, so on-screen typing still
/// inserts a newline.
///
/// The `isFocused` binding is driven write-only toward focus: there is no
/// programmatic resign path, so setting it back to `false` will not dismiss the
/// keyboard (dismissal happens natively). See `updateUIView`.
///
/// `sizeThatFits` reports a flexible width and a content-driven height clamped to
/// `maxVisibleLines`, so the field wraps to the offered width and grows downward
/// instead of stretching the input bar.
struct ChatComposerTextView: UIViewRepresentable {
    @Binding var text: String
    var isFocused: FocusState<Bool>.Binding
    let placeholder: String
    let isEncrypted: Bool
    /// Attempts a send. Returns `true` when a message was sent (Return is then
    /// consumed and focus retained), `false` when gated off (Return inserts a
    /// newline instead).
    let onSend: () -> Bool

    func makeUIView(context: Context) -> ChatComposerUITextView {
        let textView = ChatComposerUITextView(usingTextLayoutManager: false)
        textView.delegate = context.coordinator
        textView.onSend = onSend
        textView.font = UIFont.preferredFont(forTextStyle: .body)
        textView.adjustsFontForContentSizeCategory = true
        textView.backgroundColor = .clear
        textView.inlinePredictionType = .no
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

        let placeholderLabel = UILabel()
        placeholderLabel.text = placeholder
        placeholderLabel.font = textView.font
        placeholderLabel.textColor = .placeholderText
        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        textView.addSubview(placeholderLabel)
        NSLayoutConstraint.activate([
            placeholderLabel.topAnchor.constraint(equalTo: textView.topAnchor, constant: ChatComposerUITextView.verticalInset),
            placeholderLabel.leadingAnchor.constraint(equalTo: textView.leadingAnchor),
            placeholderLabel.trailingAnchor.constraint(lessThanOrEqualTo: textView.trailingAnchor)
        ])
        context.coordinator.placeholderLabel = placeholderLabel
        placeholderLabel.isHidden = !text.isEmpty

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
            context.coordinator.placeholderLabel?.isHidden = !text.isEmpty
        }

        if isFocused.wrappedValue, !textView.isFirstResponder {
            // Drive first responder only toward focus. A resign here would fire
            // spuriously: @FocusState has no SwiftUI `.focused()` consumer (the
            // field is UIKit), so it reverts to false after `didBeginEditing`
            // sets it, and reconciling that back would unfocus mid-typing.
            // Keyboard dismissal happens natively (tapping away, leaving the view).
            Task { @MainActor in
                guard textView.window != nil else { return }
                textView.becomeFirstResponder()
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

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: ChatComposerTextView
        weak var placeholderLabel: UILabel?

        init(_ parent: ChatComposerTextView) {
            self.parent = parent
        }

        func textViewDidChange(_ textView: UITextView) {
            // Skip the redundant write when the text already matches the binding.
            // The post-send clear edits the field from inside `updateUIView`, which
            // fires this delegate synchronously; writing the binding mid-update is
            // disallowed, and the value is already empty there, so guarding avoids it.
            if parent.text != textView.text {
                parent.text = textView.text
            }
            placeholderLabel?.isHidden = !textView.text.isEmpty
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            Task { @MainActor in self.parent.isFocused.wrappedValue = true }
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            Task { @MainActor in self.parent.isFocused.wrappedValue = false }
        }
    }
}

/// `UITextView` subclass that sends on an unmodified hardware Return and measures a
/// wrapped height from one line up to `maxVisibleLines`, scrolling beyond that.
final class ChatComposerUITextView: UITextView {
    static let verticalInset: CGFloat = 8
    static let maxVisibleLines = 5

    var onSend: (() -> Bool)?

    /// Clears the field after a send through the text-input editing path.
    ///
    /// Assigning `text = ""` runs a private caret-reset that lives in the text
    /// input system, outside `UIView`/`CATransaction` control, so it can't be
    /// suppressed by `performWithoutAnimation`. Replacing the full range instead
    /// moves the caret as a discrete edit, the same as deleting, which keeps it
    /// from animating to the start as the field collapses. `unmarkText` first
    /// commits any in-progress IME composition so the replace deletes everything.
    func clearAfterSend() {
        unmarkText()
        if let fullRange = textRange(from: beginningOfDocument, to: endOfDocument) {
            replace(fullRange, withText: "")
        }
        contentOffset = .zero
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

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if let key = presses.first?.key,
           key.keyCode == .keyboardReturnOrEnter || key.keyCode == .keypadEnter {
            let composingModifiers: UIKeyModifierFlags = [.shift, .alternate, .control, .command]
            if key.modifierFlags.isDisjoint(with: composingModifiers), onSend?() == true {
                // A send fired: consume the Return so it neither inserts a newline
                // nor resigns first responder. When the send is gated off, fall
                // through to `super` so Return inserts a newline as it always did.
                return
            }
        }
        super.pressesBegan(presses, with: event)
    }
}
