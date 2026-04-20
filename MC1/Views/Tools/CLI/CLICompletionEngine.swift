import Foundation

@MainActor
@Observable
final class CLICompletionEngine {

    // MARK: - Command Definitions

    private static let builtInCommands = [
        "help", "clear", "session", "logout"
    ]

    private static let localOnlyCommands = [
        "login", "nodes", "channels"
    ]

    // Per MeshCore CLI Reference - commands available via remote session
    private static let repeaterCommands = [
        "ver", "board", "clock", "clkreboot",
        "neighbors", "get", "set", "password",
        "log", "reboot", "advert", "advert.zerohop", "setperm", "tempradio", "neighbor.remove",
        "region", "gps", "powersaving", "clear", "discover.neighbors"
    ]

    private static let sessionSubcommands = ["list", "local"]

    private static let logSubcommands = ["start", "stop", "erase"]

    private static let clearSubcommands = ["stats"]

    private static let clockSubcommands = ["sync"]

    // Per MeshCore CLI Reference - region subcommands
    private static let regionSubcommands = [
        "load", "get", "put", "remove", "allowf", "denyf", "home", "save"
    ]

    // Per MeshCore CLI Reference - gps subcommands
    private static let gpsSubcommands = ["on", "off", "sync", "setloc", "advert"]

    private static let gpsAdvertValues = ["none", "share", "prefs"]

    private static let powersavingValues = ["on", "off"]

    // Per MeshCore CLI Reference - all get/set parameters
    private static let getSetParams = [
        "acl", "name", "radio", "tx", "repeat", "lat", "lon",
        "af", "flood.max", "int.thresh", "agc.reset.interval",
        "multi.acks", "advert.interval", "flood.advert.interval",
        "guest.password", "allow.read.only",
        "rxdelay", "txdelay", "direct.txdelay",
        "bridge.enabled", "bridge.delay", "bridge.source",
        "bridge.baud", "bridge.secret", "bridge.type",
        "adc.multiplier", "public.key", "prv.key", "role", "freq",
        "path.hash.mode", "loop.detect", "bootloader.ver"
    ]

    private static let pathHashModeValues = ["0", "1", "2"]

    private static let loopDetectValues = ["off", "minimal", "moderate", "strict"]

    // MARK: - Node Names

    private(set) var nodeNames: [String] = []

    func updateNodeNames(_ names: [String]) {
        nodeNames = names
    }

    // MARK: - Completion Logic

    func completions(
        for input: String,
        isLocal: Bool,
        includeRepeaterCommandsInLocal: Bool = false
    ) -> [String] {
        let trimmed = input.trimmingCharacters(in: .whitespaces)

        // Empty or just spaces - return all applicable commands
        if trimmed.isEmpty {
            return availableCommands(
                isLocal: isLocal,
                includeRepeaterCommandsInLocal: includeRepeaterCommandsInLocal
            ).sorted()
        }

        let parts = trimmed.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        let command = parts[0].lowercased()

        // Single word - complete command name
        if parts.count == 1 && !input.hasSuffix(" ") {
            return availableCommands(
                isLocal: isLocal,
                includeRepeaterCommandsInLocal: includeRepeaterCommandsInLocal
            )
                .filter { $0.hasPrefix(command) }
                .sorted()
        }

        // Command with space - complete arguments
        let argPrefix = parts.count > 1 ? parts[1].lowercased() : ""
        let endsWithSpace = input.hasSuffix(" ")
        return completeArguments(for: command, parts: parts, prefix: argPrefix, endsWithSpace: endsWithSpace)
    }

    private func completeArguments(
        for command: String,
        parts: [String],
        prefix: String,
        endsWithSpace: Bool
    ) -> [String] {
        // Determine which argument position we're completing
        // parts.count includes command, so parts.count - 1 = number of args started
        // If endsWithSpace, we're starting a NEW argument (position = parts.count)
        // If !endsWithSpace, we're still typing the CURRENT argument (position = parts.count - 1)
        let argPosition = endsWithSpace ? parts.count : parts.count - 1

        switch command {
        case "session", "login", "log", "powersaving", "clear", "region", "clock":
            // 1-arg commands: only complete when argPosition == 1
            guard argPosition == 1 else { return [] }
            return completeFirstArg(for: command, prefix: prefix)

        case "get", "set":
            if argPosition == 1 {
                return Self.getSetParams.filter { $0.hasPrefix(prefix) }.sorted()
            }
            if command == "set", argPosition == 2, parts.count >= 2 {
                return completeSetValue(param: parts[1].lowercased(), prefix: parts.count > 2 ? parts[2].lowercased() : "")
            }
            return []

        case "gps":
            return completeGpsArgs(argPosition: argPosition, parts: parts, prefix: prefix)

        default:
            return []
        }
    }

    private func completeFirstArg(for command: String, prefix: String) -> [String] {
        switch command {
        case "session":
            return completeSessionArgs(prefix: prefix)
        case "login":
            return completeLoginArgs(prefix: prefix)
        case "log":
            return Self.logSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
        case "powersaving":
            return Self.powersavingValues.filter { $0.hasPrefix(prefix) }.sorted()
        case "clear":
            return Self.clearSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
        case "region":
            return Self.regionSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
        case "clock":
            return Self.clockSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
        default:
            return []
        }
    }

    private func availableCommands(isLocal: Bool, includeRepeaterCommandsInLocal: Bool) -> [String] {
        var commands = Self.builtInCommands

        if isLocal {
            commands.append(contentsOf: Self.localOnlyCommands)
            if includeRepeaterCommandsInLocal {
                commands.append(contentsOf: Self.repeaterCommands)
            }
        } else {
            commands.append(contentsOf: Self.repeaterCommands)
        }

        return commands
    }

    private func completeSessionArgs(prefix: String) -> [String] {
        var suggestions = Self.sessionSubcommands.filter { $0.hasPrefix(prefix) }
        suggestions.append(contentsOf: nodeNames.filter { $0.lowercased().hasPrefix(prefix) })
        return suggestions.sorted()
    }

    private func completeLoginArgs(prefix: String) -> [String] {
        return nodeNames.filter { $0.lowercased().hasPrefix(prefix) }.sorted()
    }

    private func completeSetValue(param: String, prefix: String) -> [String] {
        switch param {
        case "path.hash.mode":
            return Self.pathHashModeValues.filter { $0.hasPrefix(prefix) }.sorted()
        case "loop.detect":
            return Self.loopDetectValues.filter { $0.hasPrefix(prefix) }.sorted()
        default:
            return []
        }
    }

    private func completeGpsArgs(argPosition: Int, parts: [String], prefix: String) -> [String] {
        switch argPosition {
        case 1:
            // First argument: subcommand
            return Self.gpsSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
        case 2 where parts.count >= 2 && parts[1].lowercased() == "advert":
            // Second argument for "gps advert": value
            let valuePrefix = parts.count > 2 ? parts[2].lowercased() : ""
            return Self.gpsAdvertValues.filter { $0.hasPrefix(valuePrefix) }.sorted()
        default:
            // Command complete or non-advert subcommand (no second arg)
            return []
        }
    }
}
