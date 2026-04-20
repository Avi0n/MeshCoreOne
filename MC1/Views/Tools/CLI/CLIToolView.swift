import SwiftUI
import MC1Services
import UIKit

/// Terminal font optimized for mobile screens
private let terminalFont = Font.caption.monospaced()

struct CLIToolView: View {
    @Environment(\.appState) private var appState

    @State private var isKeyboardFocused = false
    @State private var scrollPosition = ScrollPosition(edge: .bottom)
    @State private var cursorPosition: Int = 0

    var body: some View {
        content
            .onAppear {
                if appState.cliToolViewModel == nil {
                    appState.cliToolViewModel = CLIToolViewModel()
                } else {
                    // Restore cursor to end of existing input when returning to CLI
                    cursorPosition = appState.cliToolViewModel?.currentInput.count ?? 0
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        if let viewModel = appState.cliToolViewModel {
            CLIToolContent(
                viewModel: viewModel,
                appState: appState,
                isKeyboardFocused: $isKeyboardFocused,
                scrollPosition: $scrollPosition,
                cursorPosition: $cursorPosition
            )
        } else {
            ProgressView()
        }
    }
}

private struct CLIToolContent: View {
    @Bindable var viewModel: CLIToolViewModel
    let appState: AppState
    @Binding var isKeyboardFocused: Bool
    @Binding var scrollPosition: ScrollPosition
    @Binding var cursorPosition: Int

    var body: some View {
        Group {
            if appState.services?.repeaterAdminService == nil {
                disconnectedState
            } else {
                terminalView
            }
        }
        .navigationTitle(L10n.Tools.Tools.cli)
        .navigationBarTitleDisplayMode(.inline)
        .liquidGlassToolbarBackground()
        .task(id: appState.servicesVersion) {
            viewModel.configure(
                repeaterAdminService: appState.services?.repeaterAdminService,
                remoteNodeService: appState.services?.remoteNodeService,
                dataStore: appState.services?.dataStore,
                deviceID: appState.connectedDevice?.id,
                localDevicePublicKey: appState.connectedDevice?.publicKey,
                localDeviceName: appState.connectedDevice?.nodeName ?? L10n.Tools.Tools.Cli.defaultDevice
            )
        }
    }

    // MARK: - Disconnected State

    private var disconnectedState: some View {
        ContentUnavailableView {
            Label(L10n.Tools.Tools.Cli.notConnected, systemImage: "terminal")
        } description: {
            Text(L10n.Tools.Tools.Cli.notConnectedDescription)
        }
    }

    // MARK: - Terminal View

    private var terminalView: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(viewModel.outputLines) { line in
                        Text(line.text)
                            .font(terminalFont)
                            .foregroundStyle(line.type.color)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                            .id(line.id)
                            .contextMenu {
                                Button {
                                    UIPasteboard.general.string = viewModel.getResponseBlock(containing: line)
                                } label: {
                                    Label(L10n.Tools.Tools.RxLog.copy, systemImage: "doc.on.doc")
                                }
                            }
                    }

                    inlinePrompt
                        .id("prompt")

                    if let suggestions = viewModel.tabSuggestions {
                        let columns = [GridItem(.adaptive(minimum: 120), alignment: .leading)]
                        LazyVGrid(columns: columns, alignment: .leading, spacing: 4) {
                            ForEach(Array(suggestions.enumerated()), id: \.offset) { index, suggestion in
                                Text(suggestion)
                                    .font(terminalFont)
                                    .foregroundStyle(index == viewModel.tabSelectionIndex ? .primary : .secondary)
                                    .accessibilityAddTraits(index == viewModel.tabSelectionIndex ? .isSelected : [])
                                    .padding(.horizontal, 4)
                                    .padding(.vertical, 2)
                                    .background {
                                        if index == viewModel.tabSelectionIndex {
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
            .onChange(of: viewModel.outputLines.count) { _, _ in
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
            .onChange(of: viewModel.currentInput) { _, _ in
                viewModel.updateGhostText(cursorAtEnd: cursorAtEnd)
                viewModel.clearTabState()
            }
            .onChange(of: cursorPosition) { _, _ in
                viewModel.updateGhostText(cursorAtEnd: cursorAtEnd)
            }
            .onChange(of: viewModel.tabSuggestions) { _, newSuggestions in
                if newSuggestions != nil {
                    scrollPosition.scrollTo(edge: .bottom)
                }
            }

            // Hidden UITextView overlay - captures keyboard input
            HiddenTextViewFocusable(
                text: $viewModel.currentInput,
                isFocused: $isKeyboardFocused,
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
                }
            )
            .frame(width: 1, height: 1)
            .opacity(0.01)

            if !scrollPosition.isPositionedByUser {
                EmptyView()
            } else {
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
                    isWaiting: viewModel.isWaitingForResponse,
                    onHistoryUp: {
                        viewModel.historyUp()
                        cursorPosition = viewModel.currentInput.count
                    },
                    onHistoryDown: {
                        viewModel.historyDown()
                        cursorPosition = viewModel.currentInput.count
                    },
                    onTabComplete: {
                        viewModel.tabComplete()
                        cursorPosition = viewModel.currentInput.count
                    },
                    onMoveLeft: {
                        if cursorPosition > 0 {
                            cursorPosition -= 1
                        }
                    },
                    onMoveRight: {
                        if !viewModel.ghostText.isEmpty && cursorAtEnd {
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
                    onSessions: { viewModel.executeCommand("session list") },
                    onCancel: { viewModel.cancelCurrentCommand() },
                    onDismiss: { isKeyboardFocused = false }
                )
            }
        }
        .onKeyPress(.upArrow) {
            viewModel.historyUp()
            cursorPosition = viewModel.currentInput.count
            return .handled
        }
        .onKeyPress(.downArrow) {
            viewModel.historyDown()
            cursorPosition = viewModel.currentInput.count
            return .handled
        }
        .onKeyPress(.tab, phases: [.down]) { _ in
            viewModel.tabComplete()
            cursorPosition = viewModel.currentInput.count
            return .handled
        }
        .onKeyPress(.escape) {
            if viewModel.tabSelectionIndex != nil {
                viewModel.clearTabState()
                return .handled
            }
            if viewModel.isWaitingForResponse {
                viewModel.cancelCurrentCommand()
            } else {
                isKeyboardFocused = false
            }
            return .handled
        }
        .onKeyPress(phases: [.down]) { keyPress in
            if keyPress.key == "k" && keyPress.modifiers.contains(.command) {
                viewModel.executeCommand("clear")
                return .handled
            }
            return .ignored
        }
        .onAppear {
            isKeyboardFocused = true
        }
    }

    // MARK: - Inline Prompt

    private var inlinePrompt: some View {
        HStack(spacing: 0) {
            if !viewModel.isWaitingForResponse {
                Text(viewModel.promptText)
                    .font(terminalFont)
                    .foregroundStyle(.primary)
                    .accessibilityLabel(L10n.Tools.Tools.Cli.commandPrompt)
                    .accessibilityValue(viewModel.promptText)

                // Text before cursor
                Text(textBeforeCursor)
                    .font(terminalFont)
                    .accessibilityLabel(L10n.Tools.Tools.Cli.commandInput)

                // Cursor
                if isKeyboardFocused {
                    Rectangle()
                        .fill(Color.primary)
                        .frame(width: 2, height: 14)
                }

                // Text after cursor
                Text(textAfterCursor)
                    .font(terminalFont)

                // Ghost text (only when cursor at end)
                if cursorAtEnd {
                    Text(viewModel.ghostText)
                        .font(terminalFont)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
            } else {
                // Waiting indicator
                Rectangle()
                    .fill(Color.primary)
                    .frame(width: 8, height: 14)
            }
        }
    }

    private var textBeforeCursor: String {
        let input = viewModel.currentInput
        let index = input.index(input.startIndex, offsetBy: min(cursorPosition, input.count))
        return String(input[..<index])
    }

    private var textAfterCursor: String {
        let input = viewModel.currentInput
        let index = input.index(input.startIndex, offsetBy: min(cursorPosition, input.count))
        return String(input[index...])
    }

    private var cursorAtEnd: Bool {
        cursorPosition >= viewModel.currentInput.count
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

#Preview {
    NavigationStack {
        CLIToolView()
    }
    .environment(\.appState, AppState())
}
