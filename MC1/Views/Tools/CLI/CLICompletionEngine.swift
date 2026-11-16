import Foundation
import MC1Services

@Observable
@MainActor
final class CLICompletionEngine {
  // MARK: - Command Definitions

  private static let builtInCommands = [
    "help", "clear"
  ]

  /// App-CLI session management; the node CLI is single-session and passes
  /// these straight to firmware, which has no such commands.
  private static let sessionCommands = [
    "session", "logout"
  ]

  private static let localOnlyCommands = [
    "login", "nodes", "channels"
  ]

  /// Local-session radio commands interpreted app-side. Remote repeater and
  /// room sessions use `repeaterCommands`. A chat contact's radio uses `radioCommands`.
  private static let localRadioCommands = [
    "advert", "advert.zerohop", "board", "clock", "floodadv", "get", "reboot", "set", "ver"
  ]

  /// Verbs a chat contact's radio accepts over Remote CLI.
  private static let radioCommands = [
    "get", "set", "reboot", "poweroff", "shutdown", "board", "ver"
  ]

  /// Keys that are opcode-mapped on the paired radio and CLI on a remote radio.
  /// `freq` is get-only and is added at the call site.
  private static let remoteRadioKeys: Set<String> = [
    "name", "radio", "tx", "path.hash.mode", "multi.acks"
  ]

  private static let localGetKeys = CLILocalKey.allCases.map(\.rawValue)

  /// The `get custom` dump verb, offered alongside typed keys on a local `get`.
  private static let customVarDumpKey = "custom"

  /// Every typed key the parser's `set` switch accepts: all cases minus the
  /// read-only `public.key`/`bat`. Derived so it can't drift from the enum.
  private static let localSetKeys = CLILocalKey.allCases
    .filter { $0 != .publicKey && $0 != .bat }
    .map(\.rawValue)

  /// Per MeshCore CLI Reference - commands available via remote session
  private static let repeaterCommands = [
    "ver", "board", "clock", "clkreboot",
    "neighbors", "get", "set", "sensor", "password",
    "log", "reboot", "power", "room.post", "advert", "advert.zerohop", "setperm", "tempradio", "neighbor.remove",
    "region", "gps", "powersaving", "clear", "discover.neighbors",
    "start"
  ]

  private static let sensorCommands = [
    "ver", "board", "clock", "clkreboot", "get", "set", "sensor", "password",
    "reboot", "poweroff", "shutdown", "power", "advert", "advert.zerohop", "gps"
  ]
  private static let sensorGetKeys = ["role", "name", "radio", "tx", "lat", "lon", "public.key"]
  private static let sensorSetKeys = ["name", "radio", "tx", "lat", "lon"]

  private static let sessionSubcommands = ["list", "local"]

  private static let sensorSubcommands = ["get", "list", "set"]

  private static let logSubcommands = ["start", "stop", "erase"]

  private static let clearSubcommands = ["stats"]

  private static let clockSubcommands = ["sync"]

  /// Per MeshCore CLI Reference - region subcommands
  private static let regionSubcommands = [
    "load", "get", "put", "remove", "allowf", "denyf", "home", "default", "save", "list", "def"
  ]

  /// Per MeshCore CLI Reference - gps subcommands
  private static let gpsSubcommands = ["on", "off", "sync", "setloc", "advert", "diag"]

  private static let gpsAdvertValues = ["none", "share", "prefs"]

  private static let startSubcommands = ["ota"]

  private static let regionListValues = ["allowed", "denied"]

  private static let onOffValues = ["on", "off"]

  private static let multiAcksValues = ["0", "1"]

  private static let bridgeSourceValues = ["tx", "rx"]

  /// Per MeshCore CLI Reference - all get/set parameters
  private static let getSetParams = [
    "acl", "name", "radio", "tx", "repeat", "lat", "lon",
    "af", "dutycycle", "flood.max", "flood.max.advert", "flood.max.unscoped",
    "int.thresh", "agc.reset.interval",
    "multi.acks", "advert.interval", "flood.advert.interval",
    "guest.password", "allow.read.only",
    "rxdelay", "txdelay", "direct.txdelay",
    "bridge.enabled", "bridge.delay", "bridge.source",
    "bridge.baud", "bridge.secret", "bridge.type",
    "adc.multiplier", "public.key", "prv.key", "role", "freq",
    "path.hash.mode", "loop.detect", "bootloader.ver",
    "owner.info", "radio.rxgain", "radio.fem.rxgain", "radio.fem.txgain", "cad", "extra.sf", "bridge.channel",
    "pwrmgt.support", "pwrmgt.source", "pwrmgt.bootreason", "pwrmgt.bootmv"
  ]

  // Serial-only params excluded from remote session completions
  private static let serialOnlyGetParams: Set<String> = ["prv.key", "acl"]
  private static let serialOnlySetParams: Set<String> = ["freq"]

  private static let pathHashModeValues = ["0", "1", "2"]

  private static let loopDetectValues = ["off", "minimal", "moderate", "strict"]

  // MARK: - Node Names

  private(set) var nodeNames: [String] = []

  func updateNodeNames(_ names: [String]) {
    nodeNames = names
  }

  // MARK: - Custom Var Keys

  /// Custom-var keys learned on connection and from each successful local
  /// custom-var fetch. Advisory only; offered on bare `get`/`set` locally.
  private(set) var customVarKeys: [String] = []

  func updateCustomVarKeys(_ keys: [String]) {
    customVarKeys = keys
  }

  /// When true, local get/set completions include Radio CLI keys.
  var radioCLIEnabled = false

  // MARK: - Completion Logic

  func applying(
    _ suggestion: String,
    to input: String,
    isLocal: Bool,
    remoteTarget: CLIRemoteTarget?
  ) -> String {
    var parts = input.split(separator: " ", omittingEmptySubsequences: false).dropLast().map(String.init)
    parts.append(suggestion)
    let completed = parts.joined(separator: " ")
    let words = completed.split(separator: " ")
    let command = words.first.map { $0.lowercased() } ?? ""
    let isAppCommand = Self.builtInCommands.contains(command) || Self.sessionCommands.contains(command)
    let isRadioCLI = (remoteTarget == .radio && !isAppCommand)
      || (isLocal && radioCLIEnabled && RadioCLIPassthrough.shouldSend(completed))
    guard isRadioCLI else { return completed + " " }

    // Completed firmware commands must match exactly; a setter's separator is part of its value syntax.
    let needsArgument: Bool = switch command {
    case "get":
      words.count == 1
    case "set":
      words.count == 1 || (words.count == 2 && String(words[1]) != RadioCLIKey.wifiClear)
    default:
      false
    }
    return completed + (needsArgument ? " " : "")
  }

  func completions(
    for input: String,
    isLocal: Bool,
    remoteTarget: CLIRemoteTarget? = nil,
    includeSessionCommands: Bool = true
  ) -> [String] {
    let trimmed = input.trimmingCharacters(in: .whitespaces)

    // Empty or just spaces - return all applicable commands
    if trimmed.isEmpty {
      return availableCommands(
        isLocal: isLocal,
        remoteTarget: remoteTarget,
        includeSessionCommands: includeSessionCommands
      ).sorted()
    }

    let parts = trimmed.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
    let command = parts[0].lowercased()

    // Single word - complete command name
    if parts.count == 1 && !input.hasSuffix(" ") {
      return availableCommands(
        isLocal: isLocal,
        remoteTarget: remoteTarget,
        includeSessionCommands: includeSessionCommands
      )
      .filter { $0.hasPrefix(command) }
      .sorted()
    }

    // Command with space - complete arguments
    let argPrefix = parts.count > 1 ? parts[1].lowercased() : ""
    let endsWithSpace = input.hasSuffix(" ")
    return completeArguments(
      for: command,
      parts: parts,
      prefix: argPrefix,
      endsWithSpace: endsWithSpace,
      isLocal: isLocal,
      remoteTarget: remoteTarget
    )
  }

  private func completeArguments(
    for command: String,
    parts: [String],
    prefix: String,
    endsWithSpace: Bool,
    isLocal: Bool,
    remoteTarget: CLIRemoteTarget?
  ) -> [String] {
    // parts.count includes the command, so parts.count - 1 is how many args have been started.
    // A trailing space starts the next argument (position = parts.count); otherwise we are still typing the current one.
    let argPosition = endsWithSpace ? parts.count : parts.count - 1

    if remoteTarget == .sensor, !isLocal {
      let options: [String] = switch command {
      case "get": Self.sensorGetKeys
      case "set": Self.sensorSetKeys
      case "sensor": Self.sensorSubcommands
      case "clock": Self.clockSubcommands
      case "gps": ["on", "off"]
      default: []
      }
      return argPosition == 1 ? options.filter { $0.hasPrefix(prefix) }.sorted() : []
    }

    switch command {
    case "session", "login", "log", "powersaving", "clear", "clock", "start":
      // 1-arg commands: only complete when argPosition == 1
      guard argPosition == 1 else { return [] }
      return completeFirstArg(for: command, prefix: prefix)

    case "get":
      if argPosition == 1 {
        if isLocal {
          var typed = Self.localGetKeys + [Self.customVarDumpKey]
          if radioCLIEnabled {
            typed.append(contentsOf: RadioCLIPassthrough.getSetKeys)
            typed.append(contentsOf: RadioCLIPassthrough.getOnlyKeys)
          }
          return localKeyCompletions(typed: typed, parts: parts)
        }
        if remoteTarget == .radio {
          return radioCLIKeys(isGet: true, prefix: prefix)
        }
        return remoteGetSetKeys(excluding: Self.serialOnlyGetParams, remoteTarget: remoteTarget, prefix: prefix)
      }
      return []

    case "set":
      if argPosition == 1 {
        if isLocal {
          var typed = Self.localSetKeys
          if radioCLIEnabled {
            typed.append(contentsOf: RadioCLIPassthrough.getSetKeys)
            typed.append(contentsOf: RadioCLIPassthrough.setOnlyKeys)
          }
          return localKeyCompletions(typed: typed, parts: parts)
        }
        if remoteTarget == .radio {
          return radioCLIKeys(isGet: false, prefix: prefix)
        }
        return remoteGetSetKeys(excluding: Self.serialOnlySetParams, remoteTarget: remoteTarget, prefix: prefix)
      }
      if argPosition == 2, parts.count >= 2 {
        return completeSetValue(param: parts[1].lowercased(), prefix: parts.count > 2 ? parts[2].lowercased() : "")
      }
      return []

    case "sensor":
      // The `sensor` namespace targets a remote node; it is not a local command.
      if argPosition == 1, !isLocal {
        return Self.sensorSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
      }
      return []

    case "gps":
      return completeGpsArgs(argPosition: argPosition, parts: parts, prefix: prefix)

    case "region":
      return completeRegionArgs(argPosition: argPosition, parts: parts, prefix: prefix)

    default:
      return []
    }
  }

  private func completeFirstArg(for command: String, prefix: String) -> [String] {
    switch command {
    case "session":
      completeSessionArgs(prefix: prefix)
    case "login":
      completeLoginArgs(prefix: prefix)
    case "log":
      Self.logSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
    case "powersaving":
      Self.onOffValues.filter { $0.hasPrefix(prefix) }.sorted()
    case "clear":
      Self.clearSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
    case "clock":
      Self.clockSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
    case "start":
      Self.startSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
    default:
      []
    }
  }

  private func availableCommands(
    isLocal: Bool,
    remoteTarget: CLIRemoteTarget?,
    includeSessionCommands: Bool
  ) -> [String] {
    var commands = Self.builtInCommands

    if includeSessionCommands, remoteTarget != .sensor {
      commands.append(contentsOf: Self.sessionCommands)
    }

    if isLocal {
      commands.append(contentsOf: Self.localOnlyCommands)
      commands.append(contentsOf: Self.localRadioCommands)
    } else if remoteTarget == .sensor {
      commands.append(contentsOf: Self.sensorCommands)
    } else if remoteTarget == .radio {
      commands.append(contentsOf: Self.radioCommands)
    } else {
      var remote = Self.repeaterCommands
      if remoteTarget == .repeater {
        remote.removeAll { $0 == "room.post" }
      }
      commands.append(contentsOf: remote)
    }

    return commands
  }

  /// Remote get/set keys for a chat contact's radio. `freq` is get-only.
  private func radioCLIKeys(isGet: Bool, prefix: String) -> [String] {
    var keys = RadioCLIPassthrough.getSetKeys.union(Self.remoteRadioKeys)
    if isGet {
      keys.formUnion(RadioCLIPassthrough.getOnlyKeys)
      keys.insert("freq")
    } else {
      keys.formUnion(RadioCLIPassthrough.setOnlyKeys)
    }
    return keys.filter { $0.hasPrefix(prefix) }.sorted()
  }

  /// Remote get/set keys. A room never offers `repeat`. `nil` keeps the union.
  private func remoteGetSetKeys(
    excluding serialOnly: Set<String>,
    remoteTarget: CLIRemoteTarget?,
    prefix: String
  ) -> [String] {
    Self.getSetParams
      .filter { !serialOnly.contains($0) }
      .filter { remoteTarget != .room || $0 != "repeat" }
      .filter { $0.hasPrefix(prefix) }
      .sorted()
  }

  private func completeSessionArgs(prefix: String) -> [String] {
    var suggestions = Self.sessionSubcommands.filter { $0.hasPrefix(prefix) }
    suggestions.append(contentsOf: nodeNames.filter { $0.lowercased().hasPrefix(prefix) })
    return suggestions.sorted()
  }

  private func completeLoginArgs(prefix: String) -> [String] {
    nodeNames.filter { $0.lowercased().hasPrefix(prefix) }.sorted()
  }

  /// Merges typed keys (lowercase, matched on the folded prefix) with learned
  /// custom-var keys (matched case-insensitively, suggested verbatim) for bare
  /// `get`/`set` on a local session; result deduped and sorted. Reads the raw
  /// argument from `parts` so a mixed-case custom key is offered unchanged.
  private func localKeyCompletions(typed: [String], parts: [String]) -> [String] {
    let lowered = (parts.count > 1 ? parts[1] : "").lowercased()
    let typedMatches = typed.filter { $0.hasPrefix(lowered) }
    let customMatches = customVarKeys.filter { $0.lowercased().hasPrefix(lowered) }
    return Array(Set(typedMatches + customMatches)).sorted()
  }

  private func completeSetValue(param: String, prefix: String) -> [String] {
    switch param {
    case "path.hash.mode":
      Self.pathHashModeValues.filter { $0.hasPrefix(prefix) }.sorted()
    case "loop.detect":
      Self.loopDetectValues.filter { $0.hasPrefix(prefix) }.sorted()
    case "repeat", "allow.read.only", "bridge.enabled",
         "radio.rxgain", "radio.fem.rxgain", "radio.fem.txgain", "cad":
      Self.onOffValues.filter { $0.hasPrefix(prefix) }.sorted()
    case "multi.acks":
      Self.multiAcksValues.filter { $0.hasPrefix(prefix) }.sorted()
    case "bridge.source":
      Self.bridgeSourceValues.filter { $0.hasPrefix(prefix) }.sorted()
    default:
      []
    }
  }

  private func completeRegionArgs(argPosition: Int, parts: [String], prefix: String) -> [String] {
    switch argPosition {
    case 1:
      return Self.regionSubcommands.filter { $0.hasPrefix(prefix) }.sorted()
    case 2 where parts.count >= 2 && parts[1].lowercased() == "list":
      let valuePrefix = parts.count > 2 ? parts[2].lowercased() : ""
      return Self.regionListValues.filter { $0.hasPrefix(valuePrefix) }.sorted()
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
