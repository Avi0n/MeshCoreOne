import SwiftUI
import UIKit

/// Hosts the terminal for a single managed node, owning the per-instance local
/// state the terminal needs and wiring callbacks to `NodeCLIViewModel`.
struct NodeCLIView: View {
    @Bindable var viewModel: NodeCLIViewModel

    @State private var isKeyboardFocused = false
    @State private var scrollPosition = ScrollPosition(edge: .bottom)
    @State private var cursorPosition: Int = 0

    var body: some View {
        CLITerminalView(
            outputLines: viewModel.outputLines,
            promptText: viewModel.promptText,
            ghostText: viewModel.ghostText,
            tabSuggestions: viewModel.tabSuggestions,
            tabSelectionIndex: viewModel.tabSelectionIndex,
            isWaitingForResponse: viewModel.isWaitingForResponse,
            showSessionsButton: false,
            currentInput: $viewModel.currentInput,
            isKeyboardFocused: $isKeyboardFocused,
            scrollPosition: $scrollPosition,
            cursorPosition: $cursorPosition,
            onSubmit: {
                if viewModel.applySelectedSuggestion() {
                    cursorPosition = viewModel.currentInput.count
                } else {
                    viewModel.executeCommand(viewModel.currentInput)
                }
            },
            onHistoryUp: {
                viewModel.historyUp()
                cursorPosition = viewModel.currentInput.count
            },
            onHistoryDown: {
                viewModel.historyDown()
                cursorPosition = viewModel.currentInput.count
            },
            onRightArrowAtEnd: {
                if !viewModel.ghostText.isEmpty {
                    viewModel.acceptGhostText()
                    cursorPosition = viewModel.currentInput.count
                }
            },
            onTabComplete: {
                viewModel.tabComplete()
                cursorPosition = viewModel.currentInput.count
            },
            onMoveLeft: {
                if cursorPosition > 0 { cursorPosition -= 1 }
            },
            onMoveRight: {
                if !viewModel.ghostText.isEmpty && cursorPosition >= viewModel.currentInput.count {
                    viewModel.acceptGhostText()
                    cursorPosition = viewModel.currentInput.count
                } else if cursorPosition < viewModel.currentInput.count {
                    cursorPosition += 1
                }
            },
            onPaste: {
                viewModel.pasteFromClipboard(at: cursorPosition)
                cursorPosition = min(
                    cursorPosition + (UIPasteboard.general.string?.count ?? 0),
                    viewModel.currentInput.count
                )
            },
            onSessions: {},
            onCancel: { viewModel.cancelCurrentCommand() },
            onDismiss: { isKeyboardFocused = false },
            onClear: { viewModel.executeCommand("clear") },
            onUpdateGhostText: { cursorAtEnd in viewModel.updateGhostText(cursorAtEnd: cursorAtEnd) },
            onClearTabState: { viewModel.clearTabState() },
            onGetResponseBlock: { viewModel.getResponseBlock(containing: $0) }
        )
        // Restore the cursor to the end of surviving input when the view is
        // recreated by a Settings/CLI segment toggle; cursorPosition is
        // view-local @State and resets to 0, but currentInput lives on the VM.
        .onAppear { cursorPosition = viewModel.currentInput.count }
    }
}
