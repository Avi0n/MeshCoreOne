import Foundation
import OSLog
import MC1Services
import UIKit

@Observable
@MainActor
final class CLIToolViewModel {
    private static let maxOutputLines = 1000
    private static let maxHistoryEntries = 100
    static let logger = Logger(subsystem: "com.mc1", category: "CLIToolViewModel")

    // MARK: - State

    private(set) var outputLines: [CLIOutputLine] = []
    private(set) var commandHistory: [String] = []
    private(set) var historyIndex: Int?
    var activeSession: CLISession?
    var remoteSessions: [CLISession] = []
    var isWaitingForResponse = false
    private var hasShownWelcome = false

    var currentInput: String = ""
    private var countdownTask: Task<Void, Never>?
    var remainingSeconds: Int?
    var pendingLoginContact: ContactDTO?

    // MARK: - Completion State

    let completionEngine = CLICompletionEngine()
    var ghostText: String = ""
    private var nodeNamesTask: Task<Void, Never>?

    /// Current tab completion suggestions (nil when hidden)
    var tabSuggestions: [String]?

    /// Selected index within suggestions (nil = not in selection mode)
    var tabSelectionIndex: Int?

    // MARK: - Task Management

    private var currentCommandTask: Task<Void, Never>?

    // MARK: - Dependencies

    private var repeaterAdminServiceProvider: @MainActor () -> RepeaterAdminService? = { nil }
    private var remoteNodeServiceProvider: @MainActor () -> RemoteNodeService? = { nil }
    private var dataStoreProvider: @MainActor () -> PersistenceStoreProtocol? = { nil }
    private var radioIDProvider: @MainActor () -> UUID? = { nil }

    // Both old and new providers read the same live state, so change detection
    // needs the instance seen at the previous configure. Weak, so a torn-down
    // container's deallocated service reads as a change.
    private weak var lastConfiguredAdminService: RepeaterAdminService?

    var repeaterAdminService: RepeaterAdminService? { repeaterAdminServiceProvider() }
    var remoteNodeService: RemoteNodeService? { remoteNodeServiceProvider() }
    var dataStore: PersistenceStoreProtocol? { dataStoreProvider() }
    var radioID: UUID? { radioIDProvider() }

    var localDeviceName: String = ""

    // MARK: - Prompt

    var promptText: String {
        if let seconds = remainingSeconds {
            return L10n.Tools.Tools.Cli.loggingIn(seconds)
        }

        if isWaitingForResponse {
            return ""
        }

        if pendingLoginContact != nil {
            return "\(L10n.Tools.Tools.Cli.passwordPrompt) "
        }

        guard let session = activeSession else {
            return "\(L10n.Tools.Tools.Cli.disconnected)\(L10n.Tools.Tools.Cli.promptSuffix) "
        }

        if session.isLocal {
            return "\(session.name)\(L10n.Tools.Tools.Cli.promptSuffix) "
        } else {
            return "@\(session.name)\(L10n.Tools.Tools.Cli.promptSuffix) "
        }
    }

    // MARK: - Setup

    func configure(
        repeaterAdminService: @escaping @MainActor () -> RepeaterAdminService?,
        remoteNodeService: @escaping @MainActor () -> RemoteNodeService?,
        dataStore: @escaping @MainActor () -> PersistenceStoreProtocol?,
        radioID: @escaping @MainActor () -> UUID?,
        localDeviceName: String
    ) {
        self.localDeviceName = localDeviceName
        remoteNodeServiceProvider = remoteNodeService
        dataStoreProvider = dataStore
        radioIDProvider = radioID

        let incomingService = repeaterAdminService()
        repeaterAdminServiceProvider = repeaterAdminService

        if lastConfiguredAdminService !== incomingService {
            if incomingService != nil && activeSession == nil {
                activeSession = .local(deviceName: localDeviceName)
                showWelcomeBanner()
            } else if incomingService == nil {
                activeSession = nil
                remoteSessions.removeAll()
            }
        }
        lastConfiguredAdminService = incomingService

        // Update node names for completion
        nodeNamesTask?.cancel()
        nodeNamesTask = Task {
            await updateNodeNamesForCompletion()
        }
    }

    func cleanup() {
        currentCommandTask?.cancel()
        currentCommandTask = nil
        nodeNamesTask?.cancel()
        nodeNamesTask = nil
        stopCountdown()
    }

    /// Resets state for new connection while preserving command history
    func reset() {
        cleanup()
        outputLines = []
        currentInput = ""
        ghostText = ""
        activeSession = nil
        remoteSessions = []
        isWaitingForResponse = false
        pendingLoginContact = nil
        hasShownWelcome = false
        clearTabState()
    }

    func stopCountdown() {
        countdownTask?.cancel()
        countdownTask = nil
        remainingSeconds = nil
    }

    func startCountdown(_ seconds: Int) {
        remainingSeconds = seconds
        countdownTask = Task {
            var remaining = seconds
            while remaining > 0 && !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if !Task.isCancelled {
                    remaining -= 1
                    remainingSeconds = remaining
                }
            }
        }
    }

    private func showWelcomeBanner() {
        guard !hasShownWelcome else { return }
        hasShownWelcome = true

        appendOutput(L10n.Tools.Tools.Cli.welcomeLine1, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.welcomeConnected(localDeviceName), type: .response)
        appendOutput(L10n.Tools.Tools.Cli.welcomeHint, type: .response)
        appendOutput("", type: .response)
    }

    // MARK: - Command Execution

    func executeCommand(_ command: String) {
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !isWaitingForResponse else { return }
        let promptPrefix = promptText.trimmingCharacters(in: .whitespaces)

        // Handle password input for pending login
        if let contact = pendingLoginContact {
            // Echo masked password
            appendOutput("\(promptPrefix) ****", type: .command)
            pendingLoginContact = nil
            currentInput = ""

            // Empty password cancels login
            guard !trimmed.isEmpty else {
                appendOutput(L10n.Tools.Tools.Cli.cancelled, type: .error)
                return
            }

            // Claim the busy state synchronously so a second submit can't pass
            // the guard before the spawned task starts running.
            isWaitingForResponse = true
            currentCommandTask = Task {
                defer { isWaitingForResponse = false }
                await completeLogin(contact: contact, password: trimmed)
            }
            return
        }

        // Echo prompt when empty input is submitted, matching terminal behavior
        guard !trimmed.isEmpty else {
            appendOutput(promptPrefix, type: .command)
            return
        }

        addToHistory(trimmed)
        appendOutput("\(promptPrefix) \(trimmed)", type: .command)

        // Parse and execute
        let parts = trimmed.split(separator: " ", maxSplits: 1).map(String.init)
        let cmd = parts[0].lowercased()
        let args = parts.count > 1 ? parts[1] : ""

        // Claim the busy state synchronously so a second submit can't pass the
        // guard before the spawned task starts running.
        isWaitingForResponse = true
        currentCommandTask = Task {
            defer { isWaitingForResponse = false }
            await handleCommand(cmd, args: args, raw: trimmed)
        }

        currentInput = ""
    }

    func cancelCurrentCommand() {
        currentCommandTask?.cancel()
        currentCommandTask = nil
        if isWaitingForResponse {
            isWaitingForResponse = false
            appendOutput(L10n.Tools.Tools.Cli.cancelled, type: .error)
        }
    }

    private func addToHistory(_ command: String) {
        commandHistory.append(command)
        if commandHistory.count > Self.maxHistoryEntries {
            commandHistory.removeFirst()
        }
        historyIndex = nil
    }

    private func handleCommand(_ cmd: String, args: String, raw: String) async {
        // Handle "s1", "s2", etc. as shorthand for "session 1", "session 2"
        if let number = parseSessionShortcut(cmd) {
            handleSessionCommand(String(number))
            return
        }

        switch cmd {
        case "help": showHelp()
        case "clear" where activeSession?.isLocal == true || args.isEmpty: clearOutput()
        case "session": handleSessionCommand(args)
        case "login": await handleLogin(args)
        case "logout": await handleLogout()
        case "nodes" where activeSession?.isLocal == true: await handleNodesCommand()
        case "channels" where activeSession?.isLocal == true: await handleChannelsCommand()
        default: await handleUnknownCommand(cmd, raw: raw)
        }
    }

    private func parseSessionShortcut(_ cmd: String) -> Int? {
        guard cmd.hasPrefix("s"), cmd.count > 1 else { return nil }
        return Int(cmd.dropFirst())
    }

    private func handleUnknownCommand(_ cmd: String, raw: String) async {
        if activeSession?.isLocal == true {
            appendOutput("\(L10n.Tools.Tools.Cli.unknownCommand) \(cmd)", type: .error)
        } else if activeSession != nil {
            await sendRemoteCommand(raw)
        } else {
            appendOutput("\(L10n.Tools.Tools.Cli.unknownCommand) \(cmd)", type: .error)
        }
    }

    // MARK: - Built-in Commands

    private func showHelp() {
        appendOutput(L10n.Tools.Tools.Cli.helpHeader, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.helpLogin, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.helpLogout, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.helpSessionList, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.helpSessionLocal, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.helpSessionName, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.helpSessionShortcut, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.helpClear, type: .response)
        appendOutput(L10n.Tools.Tools.Cli.helpHelp, type: .response)

        if activeSession?.isLocal == true {
            appendOutput(L10n.Tools.Tools.Cli.helpNodes, type: .response)
            appendOutput(L10n.Tools.Tools.Cli.helpChannels, type: .response)
        } else if activeSession != nil {
            appendOutput("", type: .response)
            appendOutput(L10n.Tools.Tools.Cli.helpRepeaterHeader, type: .response)
            appendOutput(L10n.Tools.Tools.Cli.helpRepeaterList1, type: .response)
            appendOutput(L10n.Tools.Tools.Cli.helpRepeaterList2, type: .response)
            appendOutput(L10n.Tools.Tools.Cli.helpRepeaterList3, type: .response)
            appendOutput(L10n.Tools.Tools.Cli.helpRepeaterList4, type: .response)
        }
    }

    private func clearOutput() {
        outputLines.removeAll()
    }

    // MARK: - History Navigation

    func historyUp() {
        guard !commandHistory.isEmpty else { return }

        if let index = historyIndex {
            if index > 0 {
                historyIndex = index - 1
            }
        } else {
            historyIndex = commandHistory.count - 1
        }

        if let index = historyIndex {
            currentInput = commandHistory[index]
        }
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

    // MARK: - Output Management

    func appendOutput(_ text: String, type: CLIOutputType) {
        let line = CLIOutputLine(text: text, type: type)
        outputLines.append(line)

        if outputLines.count > Self.maxOutputLines {
            outputLines.removeFirst()
        }
    }

    // MARK: - Clipboard

    /// Returns the full response block containing the given line.
    /// A response block is all consecutive non-command lines.
    func getResponseBlock(containing line: CLIOutputLine) -> String {
        guard let index = outputLines.firstIndex(where: { $0.id == line.id }) else {
            return line.text
        }

        // Command lines: strip prompt prefix (e.g., "local > " or "@node > ")
        if line.type == .command {
            if let range = line.text.range(of: "> ") {
                return String(line.text[range.upperBound...])
            }
            return line.text
        }

        // Find start of block (walk back until we hit a .command or start)
        var startIndex = index
        while startIndex > 0 && outputLines[startIndex - 1].type != .command {
            startIndex -= 1
        }

        // Find end of block (walk forward until we hit a .command or end)
        var endIndex = index
        while endIndex < outputLines.count - 1 && outputLines[endIndex + 1].type != .command {
            endIndex += 1
        }

        return outputLines[startIndex...endIndex]
            .map { line in
                // Strip "> " prefix from MeshCore CLI responses
                if line.text.hasPrefix("> ") {
                    return String(line.text.dropFirst(2))
                }
                return line.text
            }
            .joined(separator: "\n")
    }

    /// Inserts clipboard text at the given cursor position.
    func pasteFromClipboard(at cursorPosition: Int) {
        guard let text = UIPasteboard.general.string else { return }

        let safePosition = min(cursorPosition, currentInput.count)
        let index = currentInput.index(currentInput.startIndex, offsetBy: safePosition)
        currentInput.insert(contentsOf: text, at: index)
    }
}
