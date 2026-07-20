import MC1Services
import SwiftUI
import UIKit

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
        dependencies: CLIToolViewModel.Dependencies(
          repeaterAdminService: { [appState] in appState.services?.repeaterAdminService },
          remoteNodeService: { [appState] in appState.services?.remoteNodeService },
          settingsService: { [appState] in appState.services?.settingsService },
          dataStore: { [appState] in appState.services?.dataStore },
          radioID: { [appState] in appState.connectedDevice?.radioID },
          connectedDevice: { [appState] in appState.connectedDevice }
        ),
        localDeviceName: appState.connectedDevice?.nodeName ?? L10n.Tools.Tools.Cli.defaultDevice,
        sendSelfAdvert: { [appState] flood in
          try await appState.sendSelfAdvert(flood: flood, allowLocationPrompt: false)
        }
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
    CLITerminalView(
      outputLines: viewModel.outputLines,
      promptText: viewModel.promptText,
      ghostText: viewModel.ghostText,
      tabSuggestions: viewModel.tabSuggestions,
      tabSelectionIndex: viewModel.tabSelectionIndex,
      isWaitingForResponse: viewModel.isWaitingForResponse,
      showSessionsButton: true,
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
        if !viewModel.ghostText.isEmpty, cursorPosition >= viewModel.currentInput.count {
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
      onDismiss: { isKeyboardFocused = false },
      onClear: { viewModel.executeCommand("clear") },
      onUpdateGhostText: { cursorAtEnd in viewModel.updateGhostText(cursorAtEnd: cursorAtEnd) },
      onClearTabState: { viewModel.clearTabState() },
      onGetResponseBlock: { viewModel.getResponseBlock(containing: $0) }
    )
  }
}

#Preview {
  NavigationStack {
    CLIToolView()
  }
  .environment(\.appState, AppState())
}
