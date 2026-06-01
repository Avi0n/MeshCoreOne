import SwiftUI
import UIKit
import MC1Services

/// Reusable chat input bar with configurable styling
struct ChatInputBar<Leading: View>: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Binding var text: String
    @FocusState.Binding var isFocused: Bool
    let placeholder: String
    let maxBytes: Int
    let isEncrypted: Bool
    @ViewBuilder let leading: () -> Leading
    let onSend: (String) -> Void

    @State private var isCoolingDown = false
    @State private var sendInvocationCounter: Int = 0

    private var byteCount: Int {
        text.utf8.count
    }

    private var isOverLimit: Bool {
        byteCount > maxBytes
    }

    private var shouldShowCharacterCount: Bool {
        // Show when within 20 bytes of limit or over limit
        byteCount >= maxBytes - 20
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 12) {
            leading()
            ChatInputTextField(
                text: $text,
                placeholder: placeholder,
                isFocused: $isFocused,
                isEncrypted: isEncrypted,
                onSend: handleHardwareSend
            )
            ChatSendButtonWithCounter(
                canSend: canSend,
                isOverLimit: isOverLimit,
                shouldShowCharacterCount: shouldShowCharacterCount,
                byteCount: byteCount,
                maxBytes: maxBytes,
                sendAccessibilityLabel: sendAccessibilityLabel,
                sendAccessibilityHint: sendAccessibilityHint,
                onSend: send
            )
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .inputBarBackground(themedCanvas: theme.surfaces?.canvas)
        .sensoryFeedback(.start, trigger: sendInvocationCounter)
    }

    private var sendAccessibilityLabel: String {
        if isOverLimit {
            return L10n.Chats.Chats.Input.tooLong
        } else {
            return L10n.Chats.Chats.Input.sendMessage
        }
    }

    private var sendAccessibilityHint: String {
        if isOverLimit {
            return L10n.Chats.Chats.Input.removeCharacters(byteCount - maxBytes)
        } else if appState.connectionState != .ready {
            return L10n.Chats.Chats.Input.requiresConnection
        } else if canSend {
            return L10n.Chats.Chats.Input.tapToSend
        } else {
            return L10n.Chats.Chats.Input.typeFirst
        }
    }

    private var canSend: Bool {
        !isCoolingDown &&
        appState.connectionState == .ready &&
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isOverLimit
    }

    /// Sends in response to an unmodified hardware Return from the composer,
    /// honoring the same gating as the send button. Returns `true` when a message
    /// was sent so the composer consumes the Return; `false` when gated off so the
    /// composer inserts a newline instead. The composer keeps focus on its own, so
    /// no re-focus is needed here.
    private func handleHardwareSend() -> Bool {
        guard canSend else { return false }
        send()
        return true
    }

    private func send() {
        let captured = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !captured.isEmpty else { return }
        isCoolingDown = true
        text = ""
        sendInvocationCounter &+= 1
        onSend(captured)
        Task {
            try? await Task.sleep(for: .seconds(1))
            isCoolingDown = false
        }
    }
}

extension ChatInputBar where Leading == EmptyView {
    /// Builds an input bar with no leading accessory, preserving the original
    /// call sites that pass only a trailing `onSend` closure.
    init(
        text: Binding<String>,
        isFocused: FocusState<Bool>.Binding,
        placeholder: String,
        maxBytes: Int,
        isEncrypted: Bool,
        onSend: @escaping (String) -> Void
    ) {
        self.init(
            text: text,
            isFocused: isFocused,
            placeholder: placeholder,
            maxBytes: maxBytes,
            isEncrypted: isEncrypted,
            leading: { EmptyView() },
            onSend: onSend
        )
    }
}

// MARK: - Extracted Views

private struct ChatInputTextField: View {
    @Binding var text: String
    let placeholder: String
    @FocusState.Binding var isFocused: Bool
    let isEncrypted: Bool
    let onSend: () -> Bool

    var body: some View {
        ChatComposerTextView(
            text: $text,
            isFocused: $isFocused,
            placeholder: placeholder,
            isEncrypted: isEncrypted,
            onSend: onSend
        )
        .frame(maxWidth: .infinity)
        .padding(.leading, 12)
        .padding(.trailing, 28)
        .overlay(alignment: .trailing) {
            Image(systemName: isEncrypted ? "lock.fill" : "lock.open.fill")
                .font(.footnote)
                .foregroundStyle(isEncrypted ? .blue : .orange)
                .padding(.trailing, 10)
                .accessibilityHidden(true)
        }
        .textFieldBackground()
    }
}

private struct ChatSendButtonWithCounter: View {
    let canSend: Bool
    let isOverLimit: Bool
    let shouldShowCharacterCount: Bool
    let byteCount: Int
    let maxBytes: Int
    let sendAccessibilityLabel: String
    let sendAccessibilityHint: String
    let onSend: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            ChatSendButton(
                canSend: canSend,
                sendAccessibilityLabel: sendAccessibilityLabel,
                sendAccessibilityHint: sendAccessibilityHint,
                onSend: onSend
            )
            if shouldShowCharacterCount {
                ChatCharacterCountLabel(
                    byteCount: byteCount,
                    maxBytes: maxBytes,
                    isOverLimit: isOverLimit
                )
            }
        }
    }
}

private struct ChatCharacterCountLabel: View {
    let byteCount: Int
    let maxBytes: Int
    let isOverLimit: Bool

    var body: some View {
        Text("\(byteCount)/\(maxBytes)")
            .font(.caption2)
            .monospacedDigit()
            .foregroundStyle(isOverLimit ? .red : .secondary)
            .accessibilityLabel(L10n.Chats.Chats.Input.characterCount(byteCount, maxBytes))
    }
}

private struct ChatSendButton: View {
    let canSend: Bool
    let sendAccessibilityLabel: String
    let sendAccessibilityHint: String
    let onSend: () -> Void

    @Environment(\.appTheme) private var theme

    private var sendButtonFont: Font {
        if #available(iOS 26.0, *) { .title2 } else { .title }
    }

    var body: some View {
        Button(action: onSend) {
            Image(systemName: "arrow.up.circle.fill")
                .font(sendButtonFont)
                .foregroundStyle(canSend ? theme.accentColor : .secondary)
        }
        .sendButtonStyle()
        .disabled(!canSend)
        .accessibilityLabel(sendAccessibilityLabel)
        .accessibilityHint(sendAccessibilityHint)
    }
}

// MARK: - Platform-Conditional Styling

private extension View {
    @ViewBuilder
    func sendButtonStyle() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glass)
        } else {
            self.padding(.vertical, 4)
        }
    }

    @ViewBuilder
    func textFieldBackground() -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular.interactive(), in: .rect(cornerRadius: 20))
        } else {
            self
                .background(Color(.systemGray6))
                .clipShape(.rect(cornerRadius: 20))
        }
    }

    @ViewBuilder
    func inputBarBackground(themedCanvas: Color?) -> some View {
        if let themedCanvas {
            self.background(themedCanvas)
        } else if #available(iOS 26.0, *) {
            self
        } else {
            self.background(.bar)
        }
    }
}

// MARK: - Preview

private struct ChatInputBarPreviewHost: View {
    @State private var plainText = ""
    @State private var leadingText = ""
    @FocusState private var plainFocus: Bool
    @FocusState private var leadingFocus: Bool

    var body: some View {
        VStack(spacing: 24) {
            ChatInputBar(
                text: $plainText,
                isFocused: $plainFocus,
                placeholder: "No leading accessory",
                maxBytes: 140,
                isEncrypted: true
            ) { _ in }

            ChatInputBar(
                text: $leadingText,
                isFocused: $leadingFocus,
                placeholder: "With leading accessory",
                maxBytes: 140,
                isEncrypted: false,
                leading: { Image(systemName: "plus") }
            ) { _ in }
        }
    }
}

#Preview {
    ChatInputBarPreviewHost()
}
