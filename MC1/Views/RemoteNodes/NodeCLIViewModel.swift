import Foundation
import MC1Services
import UIKit

@Observable
@MainActor
final class NodeCLIViewModel {
  private static let maxOutputLines = 1000
  private static let maxHistoryEntries = 100
  private static let rebootTimeout = RemoteOperationTimeoutPolicy.fireAndForgetCLI
  private static let defaultCommandTimeout: Duration = .seconds(10)

  // MARK: - Terminal State

  private(set) var outputLines: [CLIOutputLine] = []
  private(set) var commandHistory: [String] = []
  private(set) var historyIndex: Int?
  var currentInput: String = ""
  var isWaitingForResponse = false

  // MARK: - Completion State

  let completionEngine = CLICompletionEngine()
  var ghostText: String = ""
  var tabSuggestions: [String]?
  var tabSelectionIndex: Int?

  // MARK: - Dependencies

  private var sessionName: String = ""
  private var remoteTarget: CLIRemoteTarget?
  private var sendRawCommand: (@MainActor (_ command: String, _ timeout: Duration) async throws -> String)?
  private var currentCommandTask: Task<Void, Never>?
  private var hasConfigured = false
  private var commandEpoch = Epoch()

  func reset() {
    commandEpoch.bump()
    currentCommandTask?.cancel()
    currentCommandTask = nil
    outputLines.removeAll()
    commandHistory.removeAll()
    currentInput = ""
    isWaitingForResponse = false
    hasConfigured = false
  }

  // MARK: - Prompt

  var promptText: String {
    if isWaitingForResponse { return "" }
    return "@\(sessionName)\(L10n.Tools.Tools.Cli.promptSuffix) "
  }

  // MARK: - Setup

  /// Configures the node CLI with its display name and send closure.
  /// Idempotent: the connection banner is appended only on the first call,
  /// so toggling the Settings/CLI segment does not re-banner.
  func configure(
    sessionName: String,
    remoteTarget: CLIRemoteTarget,
    sendRawCommand: @escaping @MainActor (_ command: String, _ timeout: Duration) async throws -> String
  ) {
    self.sessionName = sessionName
    self.remoteTarget = remoteTarget
    self.sendRawCommand = sendRawCommand
    guard !hasConfigured else { return }
    hasConfigured = true
    let banner = remoteTarget == .radio
      ? L10n.RemoteNodes.RemoteNodes.NodeCli.radioBanner(sessionName)
      : L10n.RemoteNodes.RemoteNodes.NodeCli.bannerConnected(sessionName)
    appendOutput(banner, type: .response)
    appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.bannerHint, type: .response)
    appendOutput("", type: .response)
  }

  // MARK: - Command Execution

  func executeCommand(_ command: String) {
    let normalized = command.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !isWaitingForResponse else { return }
    let promptPrefix = promptText.trimmingCharacters(in: .whitespaces)

    guard !normalized.isEmpty else {
      appendOutput(promptPrefix, type: .command)
      return
    }

    let transmitted = transmittedLine(original: command, normalized: normalized)
    addToHistory(transmitted)
    appendOutput("\(promptPrefix) \(transmitted)", type: .command)

    let parts = normalized.split(separator: " ", maxSplits: 1).map(String.init)
    let cmd = parts[0].lowercased()
    let args = parts.count > 1 ? parts[1] : ""

    isWaitingForResponse = true
    // Compared with `currentCommandTask` so a newer command stays waiting.
    var task: Task<Void, Never>!
    task = Task {
      defer { clearWaitingIfCurrent(task) }
      guard !Task.isCancelled else { return }
      await handleCommand(cmd, args: args, raw: transmitted)
    }
    currentCommandTask = task
    currentInput = ""
  }

  /// Radio CLI sends the submitted line. Help, clear, and the other targets keep the trimmed line.
  private func transmittedLine(original: String, normalized: String) -> String {
    guard remoteTarget == .radio else { return normalized }
    let parts = normalized.split(separator: " ", maxSplits: 1).map(String.init)
    let verb = parts[0].lowercased()
    let args = parts.count > 1 ? parts[1] : ""
    if verb == "help" || (verb == "clear" && args.isEmpty) {
      return normalized
    }
    return original
  }

  func cancelCurrentCommand() {
    // A reply already in hand must not print after cancel.
    commandEpoch.bump()
    currentCommandTask?.cancel()
    currentCommandTask = nil
    if isWaitingForResponse {
      isWaitingForResponse = false
      appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.cancelled, type: .error)
    }
  }

  private func clearWaitingIfCurrent(_ task: Task<Void, Never>) {
    guard currentCommandTask == task else { return }
    currentCommandTask = nil
    isWaitingForResponse = false
  }

  private func handleCommand(_ cmd: String, args: String, raw: String) async {
    switch cmd {
    case "help": showHelp()
    case "clear" where args.isEmpty: outputLines.removeAll()
    default: await sendCommand(raw)
    }
  }

  private func sendCommand(_ command: String) async {
    guard let sendRawCommand else { return }
    let ticket = commandEpoch.ticket()

    // These verbs often get no reply. A real reply is still shown.
    if let sentLine = noReplySentLine(for: command) {
      do {
        let response = try await sendRawCommand(command, Self.rebootTimeout)
        guard ticket.isCurrent(in: commandEpoch) else { return }
        if response.isEmpty {
          appendOutput(sentLine, type: .success)
        } else {
          appendOutput(response, type: .response)
        }
      } catch RemoteNodeError.timeout {
        guard ticket.isCurrent(in: commandEpoch) else { return }
        appendOutput(sentLine, type: .success)
      } catch is CancellationError {
      } catch {
        guard ticket.isCurrent(in: commandEpoch) else { return }
        appendOutput(error.localizedDescription, type: .error)
      }
      return
    }

    do {
      let response = try await sendRawCommand(command, Self.defaultCommandTimeout)
      guard ticket.isCurrent(in: commandEpoch), !Task.isCancelled else { return }
      appendOutput(response, type: .response)
    } catch is CancellationError {
    } catch RemoteNodeError.timeout where remoteTarget == .radio {
      guard ticket.isCurrent(in: commandEpoch) else { return }
      appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.radioNoReply(sessionName), type: .error)
    } catch {
      guard ticket.isCurrent(in: commandEpoch) else { return }
      appendOutput(error.localizedDescription, type: .error)
    }
  }

  /// Repeater and room commands with no reply share `RemoteCLINoReplyCommand`.
  /// A radio matches the whole command, including case.
  private func noReplySentLine(for command: String) -> String? {
    switch remoteTarget {
    case .repeater, .room:
      switch RemoteCLINoReplyCommand(command) {
      case .reboot:
        L10n.RemoteNodes.RemoteNodes.NodeCli.rebootSent
      case .powerOff:
        L10n.RemoteNodes.RemoteNodes.NodeCli.powerOffSent
      case nil:
        nil
      }
    case .radio:
      switch command {
      case "reboot":
        L10n.RemoteNodes.RemoteNodes.NodeCli.rebootSent
      case "poweroff", "shutdown":
        L10n.RemoteNodes.RemoteNodes.NodeCli.powerOffSent
      default:
        nil
      }
    case nil:
      nil
    }
  }

  private func showHelp() {
    appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.helpHeader, type: .response)
    appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.helpHelp, type: .response)
    appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.helpClear, type: .response)
    appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.helpClearStats, type: .response)
    appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.helpReboot, type: .response)
    appendOutput(L10n.RemoteNodes.RemoteNodes.NodeCli.helpPassthrough, type: .response)
  }

  // MARK: - History

  private func addToHistory(_ command: String) {
    commandHistory.append(command)
    if commandHistory.count > Self.maxHistoryEntries {
      commandHistory.removeFirst()
    }
    historyIndex = nil
  }

  func historyUp() {
    guard !commandHistory.isEmpty else { return }
    if let index = historyIndex {
      if index > 0 { historyIndex = index - 1 }
    } else {
      historyIndex = commandHistory.count - 1
    }
    if let index = historyIndex { currentInput = commandHistory[index] }
  }

  func historyDown() {
    guard let index = historyIndex else { return }
    if index < commandHistory.count - 1 {
      historyIndex = index + 1
      currentInput = commandHistory[index + 1]
    } else {
      historyIndex = nil
      currentInput = ""
    }
  }

  // MARK: - Output

  func appendOutput(_ text: String, type: CLIOutputType) {
    outputLines.append(CLIOutputLine(text: text, type: type))
    if outputLines.count > Self.maxOutputLines {
      outputLines.removeFirst()
    }
  }

  /// Returns the full response block containing the given line, stripping
  /// prompt and MeshCore "> " prefixes. Twin of `CLIToolViewModel.getResponseBlock(containing:)`;
  /// keep the two in sync.
  func getResponseBlock(containing line: CLIOutputLine) -> String {
    guard let index = outputLines.firstIndex(where: { $0.id == line.id }) else {
      return line.text
    }
    if line.type == .command {
      if let range = line.text.range(of: "> ") {
        return String(line.text[range.upperBound...])
      }
      return line.text
    }
    var startIndex = index
    while startIndex > 0, outputLines[startIndex - 1].type != .command {
      startIndex -= 1
    }
    var endIndex = index
    while endIndex < outputLines.count - 1, outputLines[endIndex + 1].type != .command {
      endIndex += 1
    }
    return outputLines[startIndex...endIndex]
      .map { $0.text.hasPrefix("> ") ? String($0.text.dropFirst(2)) : $0.text }
      .joined(separator: "\n")
  }

  func pasteFromClipboard(at cursorPosition: Int) {
    guard let text = UIPasteboard.general.string else { return }
    let safePosition = min(cursorPosition, currentInput.count)
    let index = currentInput.index(currentInput.startIndex, offsetBy: safePosition)
    currentInput.insert(contentsOf: text, at: index)
  }
}

// MARK: - Completion

extension NodeCLIViewModel {
  /// Node CLI always operates on a remote session, so completion uses the
  /// remote command set (`isLocal: false`) and excludes the app-CLI
  /// session-management commands the node firmware can't handle.
  func updateGhostText(cursorAtEnd: Bool) {
    guard !currentInput.isEmpty, cursorAtEnd else { ghostText = ""; return }
    let suggestions = completionEngine.completions(
      for: currentInput,
      isLocal: false,
      remoteTarget: remoteTarget,
      includeSessionCommands: false
    )
    guard let first = suggestions.first else { ghostText = ""; return }
    let parts = currentInput.split(separator: " ", omittingEmptySubsequences: false)
    let lastPart = parts.last.map(String.init) ?? ""
    ghostText = first.lowercased().hasPrefix(lastPart.lowercased())
      ? String(first.dropFirst(lastPart.count))
      : ""
  }

  func acceptGhostText() {
    guard !ghostText.isEmpty else { return }
    currentInput += ghostText
    ghostText = ""
  }

  @discardableResult
  func tabComplete() -> [String]? {
    if let suggestions = tabSuggestions, !suggestions.isEmpty {
      if let currentIndex = tabSelectionIndex {
        tabSelectionIndex = (currentIndex + 1) % suggestions.count
      } else {
        tabSelectionIndex = 0
      }
      return suggestions
    }
    let suggestions = completionEngine.completions(
      for: currentInput,
      isLocal: false,
      remoteTarget: remoteTarget,
      includeSessionCommands: false
    )
    guard !suggestions.isEmpty else {
      tabSuggestions = nil
      tabSelectionIndex = nil
      return nil
    }
    if suggestions.count == 1 {
      applyCompletion(suggestions[0])
      return nil
    }
    tabSuggestions = suggestions
    tabSelectionIndex = nil
    return suggestions
  }

  func applySelectedSuggestion() -> Bool {
    guard let suggestions = tabSuggestions,
          let index = tabSelectionIndex,
          index < suggestions.count else {
      return false
    }
    applyCompletion(suggestions[index])
    clearTabState()
    return true
  }

  func clearTabState() {
    tabSuggestions = nil
    tabSelectionIndex = nil
  }

  private func applyCompletion(_ suggestion: String) {
    currentInput = completionEngine.applying(
      suggestion,
      to: currentInput,
      isLocal: false,
      remoteTarget: remoteTarget
    )
    ghostText = ""
  }
}
