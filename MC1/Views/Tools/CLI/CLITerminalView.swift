import SwiftUI
import UIKit

/// Terminal font optimized for mobile screens.
private let terminalFont = Font.caption.monospaced()

/// Presentational terminal shared by the Tools-tab CLI and the node CLI.
/// Holds no view model: all state arrives as values/bindings and all events
/// leave via callbacks, so each host wires its own view model.
struct CLITerminalView: View {
  let outputLines: [CLIOutputLine]
  let promptText: String
  let ghostText: String
  let tabSuggestions: [String]?
  let tabSelectionIndex: Int?
  let isWaitingForResponse: Bool
  let showSessionsButton: Bool

  @Binding var currentInput: String
  @Binding var isKeyboardFocused: Bool
  @Binding var scrollPosition: ScrollPosition
  @Binding var cursorPosition: Int

  let onSubmit: () -> Void
  let onHistoryUp: () -> Void
  let onHistoryDown: () -> Void
  let onRightArrowAtEnd: () -> Void
  let onTabComplete: () -> Void
  let onMoveLeft: () -> Void
  let onMoveRight: () -> Void
  let onPaste: () -> Void
  let onSessions: () -> Void
  let onCancel: () -> Void
  let onDismiss: () -> Void
  let onClear: () -> Void
  let onUpdateGhostText: (_ cursorAtEnd: Bool) -> Void
  let onClearTabState: () -> Void
  let onGetResponseBlock: (CLIOutputLine) -> String

  var body: some View {
    ZStack(alignment: .bottomTrailing) {
      ScrollView {
        LazyVStack(alignment: .leading, spacing: 2) {
          ForEach(outputLines) { line in
            Text(line.text)
              .font(terminalFont)
              .foregroundStyle(line.type.color)
              .textSelection(.enabled)
              .fixedSize(horizontal: false, vertical: true)
              .id(line.id)
              .contextMenu {
                Button {
                  UIPasteboard.general.string = onGetResponseBlock(line)
                } label: {
                  Label(L10n.Tools.Tools.RxLog.copy, systemImage: "doc.on.doc")
                }
              }
          }

          inlinePrompt
            .id("prompt")

          if let suggestions = tabSuggestions {
            let columns = [GridItem(.adaptive(minimum: 120), alignment: .leading)]
            LazyVGrid(columns: columns, alignment: .leading, spacing: 4) {
              ForEach(Array(suggestions.enumerated()), id: \.offset) { index, suggestion in
                Text(suggestion)
                  .font(terminalFont)
                  .foregroundStyle(index == tabSelectionIndex ? .primary : .secondary)
                  .accessibilityAddTraits(index == tabSelectionIndex ? .isSelected : [])
                  .padding(.horizontal, 4)
                  .padding(.vertical, 2)
                  .background {
                    if index == tabSelectionIndex {
                      RoundedRectangle(cornerRadius: 4)
                        .fill(Color.accentColor.opacity(0.3))
                    }
                  }
              }
            }
            .id("suggestions")
          }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.top, 8)
        .padding(.bottom, 8)
      }
      .scrollIndicators(.hidden)
      .scrollDismissesKeyboard(.never)
      .scrollPosition($scrollPosition)
      .onChange(of: outputLines.count) { _, _ in
        scrollPosition.scrollTo(edge: .bottom)
      }
      .onChange(of: isKeyboardFocused) { _, focused in
        if focused {
          Task {
            try? await Task.sleep(for: .milliseconds(100))
            scrollPosition.scrollTo(edge: .bottom)
          }
        }
      }
      .onChange(of: currentInput) { _, _ in
        onUpdateGhostText(cursorAtEnd)
        onClearTabState()
      }
      .onChange(of: cursorPosition) { _, _ in
        onUpdateGhostText(cursorAtEnd)
      }
      .onChange(of: tabSuggestions) { _, newSuggestions in
        if newSuggestions != nil {
          scrollPosition.scrollTo(edge: .bottom)
        }
      }

      HiddenTextViewFocusable(
        text: $currentInput,
        isFocused: $isKeyboardFocused,
        cursorPosition: $cursorPosition,
        onSubmit: onSubmit,
        onHistoryUp: onHistoryUp,
        onHistoryDown: onHistoryDown,
        onRightArrowAtEnd: onRightArrowAtEnd,
        onTabComplete: onTabComplete
      )
      .frame(width: 1, height: 1)
      .opacity(0.01)

      if scrollPosition.isPositionedByUser {
        CLIScrollToBottomButton {
          scrollPosition.scrollTo(edge: .bottom)
        }
      }
    }
    .background(Color(.secondarySystemBackground))
    .contentShape(.rect)
    // onTapGesture is intentional: a Button in .background can't receive
    // taps through the ScrollView, and this is a non-semantic "tap anywhere
    // to focus keyboard" gesture, not a discrete button action.
    .onTapGesture {
      isKeyboardFocused = true
    }
    .safeAreaInset(edge: .bottom) {
      if isKeyboardFocused {
        CLIInputAccessoryView(
          isWaiting: isWaitingForResponse,
          showSessionsButton: showSessionsButton,
          onHistoryUp: onHistoryUp,
          onHistoryDown: onHistoryDown,
          onTabComplete: onTabComplete,
          onMoveLeft: onMoveLeft,
          onMoveRight: onMoveRight,
          onPaste: onPaste,
          onSessions: onSessions,
          onCancel: onCancel,
          onDismiss: onDismiss
        )
        .padding(.bottom, {
          if #available(iOS 26.0, *) {
            8
          } else {
            0
          }
        }())
      }
    }
    .onKeyPress(.upArrow) {
      onHistoryUp()
      return .handled
    }
    .onKeyPress(.downArrow) {
      onHistoryDown()
      return .handled
    }
    .onKeyPress(.tab, phases: [.down]) { _ in
      onTabComplete()
      return .handled
    }
    .onKeyPress(.escape) {
      if tabSelectionIndex != nil {
        onClearTabState()
        return .handled
      }
      if isWaitingForResponse {
        onCancel()
      } else {
        isKeyboardFocused = false
      }
      return .handled
    }
    .onKeyPress(phases: [.down]) { keyPress in
      if keyPress.key == "k", keyPress.modifiers.contains(.command) {
        onClear()
        return .handled
      }
      return .ignored
    }
    .onAppear {
      isKeyboardFocused = true
    }
    .onDisappear {
      // Clear the focus request when leaving so the gated accessory bar
      // can't persist across navigation and re-mount on return.
      isKeyboardFocused = false
    }
  }

  private var inlinePrompt: some View {
    HStack(spacing: 0) {
      if !isWaitingForResponse {
        Text(promptText)
          .font(terminalFont)
          .foregroundStyle(.primary)
          .accessibilityLabel(L10n.Tools.Tools.Cli.commandPrompt)
          .accessibilityValue(promptText)

        Text(textBeforeCursor)
          .font(terminalFont)
          .accessibilityLabel(L10n.Tools.Tools.Cli.commandInput)

        if isKeyboardFocused {
          Rectangle()
            .fill(Color.primary)
            .frame(width: 2, height: 14)
        }

        Text(textAfterCursor)
          .font(terminalFont)

        if cursorAtEnd {
          Text(ghostText)
            .font(terminalFont)
            .foregroundStyle(.secondary)
            .accessibilityHidden(true)
        }
      } else {
        Rectangle()
          .fill(Color.primary)
          .frame(width: 8, height: 14)
      }
    }
  }

  private var textBeforeCursor: String {
    let index = currentInput.index(currentInput.startIndex, offsetBy: min(cursorPosition, currentInput.count))
    return String(currentInput[..<index])
  }

  private var textAfterCursor: String {
    let index = currentInput.index(currentInput.startIndex, offsetBy: min(cursorPosition, currentInput.count))
    return String(currentInput[index...])
  }

  private var cursorAtEnd: Bool {
    cursorPosition >= currentInput.count
  }
}

private struct CLIScrollToBottomButton: View {
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      Image(systemName: "arrow.down.circle.fill")
        .font(.title)
        .symbolRenderingMode(.hierarchical)
        .foregroundStyle(.primary)
    }
    .accessibilityLabel(L10n.Tools.Tools.Cli.jumpToBottom)
    .padding()
  }
}
