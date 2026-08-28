import MC1Services
import OSLog
import SwiftUI

@Observable
@MainActor
final class RepeaterSettingsViewModel {
  // MARK: - Shared Helper

  var helper = NodeSettingsViewModel()

  // MARK: - Repeater-Only: Behavior Settings

  var advertIntervalMinutes: Int?
  var floodAdvertIntervalHours: Int?
  var floodMaxHops: Int?
  var repeaterEnabled: Bool?
  private var originalAdvertIntervalMinutes: Int?
  private var originalFloodAdvertIntervalHours: Int?
  private var originalFloodMaxHops: Int?
  private var originalRepeaterEnabled: Bool?
  var isLoadingBehavior = false
  var behaviorError = false
  var behaviorLoaded: Bool {
    repeaterEnabled != nil || advertIntervalMinutes != nil
  }

  var advertIntervalError: String?
  var floodAdvertIntervalError: String?
  var floodMaxHopsError: String?

  var behaviorApplySuccess = false

  var behaviorSettingsModified: Bool {
    (repeaterEnabled != nil && repeaterEnabled != originalRepeaterEnabled) ||
      (advertIntervalMinutes != nil && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
      (floodAdvertIntervalHours != nil && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
      (floodMaxHops != nil && floodMaxHops != originalFloodMaxHops)
  }

  // MARK: - Repeater-Only: Region Settings

  nonisolated static let wildcardName = "*"
  /// CLI argument when default scope is unset (`region default <null>`).
  private static let firmwareNullToken = "<null>"
  /// GET substring. SET replies use `defaultScopeSetReplyMarker`, which also matches this.
  private static let defaultScopeReplyMarker = "default scope is"
  private static let defaultScopeSetReplyMarker = "default scope is now"

  var regions: [RepeaterRegionEntry] = []
  private var originalRegions: [RepeaterRegionEntry]?
  var isLoadingRegions = false
  var regionsError = false
  var regionsLoaded: Bool {
    originalRegions != nil
  }

  var hasUnsavedRegionChanges = false
  var regionsSaveSuccess = false
  /// Unset when nil. Scopes flood traffic this node originates, not which regions it repeats.
  var defaultScopeName: String?
  /// False until a `region default` reply parses. Distinct from `defaultScopeName == nil`.
  var defaultScopeLoaded = false

  // MARK: - Expansion State (repeater-only sections)

  var isBehaviorExpanded = false
  var isRegionsExpanded = false

  // MARK: - Dependencies

  private var repeaterAdminServiceProvider: @MainActor () -> RepeaterAdminService? = { nil }
  var repeaterAdminService: RepeaterAdminService? {
    repeaterAdminServiceProvider()
  }

  private let logger = Logger(subsystem: "com.mc1", category: "RepeaterSettings")

  // MARK: - Cleanup

  func cleanup() async {
    await repeaterAdminService?.setCLIHandler { _, _ in }
    helper.cleanup()
  }

  // MARK: - Configuration

  /// Nil service mirrors a disconnected state; commands then no-op.
  func configure(repeaterAdminService: @escaping @MainActor () -> RepeaterAdminService?, session: RemoteNodeSessionDTO) async {
    repeaterAdminServiceProvider = repeaterAdminService

    guard let repeaterAdminService = repeaterAdminService() else { return }

    helper.configure(
      session: session,
      sendCommand: { [repeaterAdminService] id, cmd, timeout in
        try await repeaterAdminService.sendCommand(sessionID: id, command: cmd, timeout: timeout)
      },
      sendRawCommand: { [repeaterAdminService] id, cmd, timeout in
        try await repeaterAdminService.sendRawCommand(sessionID: id, command: cmd, timeout: timeout)
      }
    )

    helper.name = session.name

    helper.onPreFetchNodeInfo = { [weak self] in
      await self?.fetchNodeInfo()
    }

    registerBehaviorLateRecovery()

    // Register CLI handler for late responses
    await repeaterAdminService.setCLIHandler { [weak self] message, _ in
      await MainActor.run {
        self?.helper.handleCommonLateResponse(message.text)
      }
    }

    // Detached so configure returns immediately and the node CLI send
    // closure wires without waiting on the owner-info round-trip (matches
    // RoomSettingsViewModel's detached device-info fetch).
    Task { await fetchNodeInfo() }
  }

  /// Builds the node-CLI send closure, pre-binding this session's id and
  /// capturing the private admin service (a thin pass-through to
  /// `RemoteNodeService.sendRawCLICommand`). Returns nil if not configured.
  func makeNodeCLISendClosure(
    session: RemoteNodeSessionDTO
  ) -> (@MainActor (_ command: String, _ timeout: Duration) async throws -> String)? {
    guard let repeaterAdminService else { return nil }
    return { [repeaterAdminService, sessionID = session.id] command, timeout in
      try await repeaterAdminService.sendRawCommand(
        sessionID: sessionID, command: command, timeout: timeout
      )
    }
  }

  private var isLoadingNodeInfo = false

  private func fetchNodeInfo() async {
    guard !isLoadingNodeInfo, let session = helper.session, let repeaterAdminService else { return }
    isLoadingNodeInfo = true
    defer { isLoadingNodeInfo = false }
    do {
      let response = try await repeaterAdminService.requestOwnerInfo(sessionID: session.id)
      helper.setNodeInfo(
        firmwareVersion: response.firmwareVersion,
        name: response.nodeName,
        ownerInfo: response.ownerInfo
      )
    } catch {
      logger.warning("Failed to fetch node info via binary: \(error)")
    }
  }

  // MARK: - Late Reply Recovery

  private var behaviorSectionComplete: Bool {
    originalRepeaterEnabled != nil && originalAdvertIntervalMinutes != nil
      && originalFloodAdvertIntervalHours != nil && originalFloodMaxHops != nil
  }

  private func registerBehaviorLateRecovery() {
    helper.registerLateRecovery(query: "get repeat") { [weak self] value in
      guard let self, case let .repeatMode(enabled) = value else { return }
      repeaterEnabled = enabled
      originalRepeaterEnabled = enabled
      behaviorError = !behaviorSectionComplete
    }
    helper.registerLateRecovery(query: "get advert.interval") { [weak self] value in
      guard let self, case let .advertInterval(minutes) = value else { return }
      advertIntervalMinutes = minutes
      originalAdvertIntervalMinutes = minutes
      behaviorError = !behaviorSectionComplete
    }
    helper.registerLateRecovery(query: "get flood.advert.interval") { [weak self] value in
      guard let self, case let .floodAdvertInterval(hours) = value else { return }
      floodAdvertIntervalHours = hours
      originalFloodAdvertIntervalHours = hours
      behaviorError = !behaviorSectionComplete
    }
    helper.registerLateRecovery(query: "get flood.max") { [weak self] value in
      guard let self, case let .floodMax(hops) = value else { return }
      floodMaxHops = hops
      originalFloodMaxHops = hops
      behaviorError = !behaviorSectionComplete
    }
  }

  // MARK: - Behavior Fetch/Apply

  func fetchBehaviorSettings() async {
    isLoadingBehavior = true
    behaviorError = false
    var hadTimeout = false

    do {
      let response = try await helper.sendAndWait("get repeat")
      if case let .repeatMode(enabled) = CLIResponse.parse(response, forQuery: "get repeat") {
        repeaterEnabled = enabled
        originalRepeaterEnabled = enabled
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get repeat mode: \(error)")
    }

    do {
      let response = try await helper.sendAndWait("get advert.interval")
      if case let .advertInterval(minutes) = CLIResponse.parse(response, forQuery: "get advert.interval") {
        advertIntervalMinutes = minutes
        originalAdvertIntervalMinutes = minutes
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get advert interval: \(error)")
    }

    do {
      let response = try await helper.sendAndWait("get flood.advert.interval")
      if case let .floodAdvertInterval(hours) = CLIResponse.parse(response, forQuery: "get flood.advert.interval") {
        floodAdvertIntervalHours = hours
        originalFloodAdvertIntervalHours = hours
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get flood advert interval: \(error)")
    }

    do {
      let response = try await helper.sendAndWait("get flood.max")
      if case let .floodMax(hops) = CLIResponse.parse(response, forQuery: "get flood.max") {
        floodMaxHops = hops
        originalFloodMaxHops = hops
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get flood max: \(error)")
    }

    if hadTimeout {
      behaviorError = true
    }

    isLoadingBehavior = false
  }

  func applyBehaviorSettings() async {
    let validation = NodeSettingsViewModel.validateBehaviorFields(
      advertInterval: advertIntervalMinutes,
      floodInterval: floodAdvertIntervalHours,
      floodMaxHops: floodMaxHops
    )
    advertIntervalError = validation.advertInterval
    floodAdvertIntervalError = validation.floodInterval
    floodMaxHopsError = validation.floodMaxHops

    if validation.hasErrors { return }

    helper.isApplying = true
    helper.errorMessage = nil

    do {
      var allSucceeded = true

      if let repeaterEnabled, repeaterEnabled != originalRepeaterEnabled {
        let response = try await helper.sendAndWait("set repeat \(repeaterEnabled ? "on" : "off")")
        if case .ok = CLIResponse.parse(response) {
          originalRepeaterEnabled = repeaterEnabled
        } else {
          allSucceeded = false
        }
      }

      if let advertIntervalMinutes, advertIntervalMinutes != originalAdvertIntervalMinutes {
        let response = try await helper.sendAndWait("set advert.interval \(advertIntervalMinutes)")
        if case .ok = CLIResponse.parse(response) {
          originalAdvertIntervalMinutes = advertIntervalMinutes
        } else {
          allSucceeded = false
        }
      }

      if let floodAdvertIntervalHours, floodAdvertIntervalHours != originalFloodAdvertIntervalHours {
        let response = try await helper.sendAndWait("set flood.advert.interval \(floodAdvertIntervalHours)")
        if case .ok = CLIResponse.parse(response) {
          originalFloodAdvertIntervalHours = floodAdvertIntervalHours
        } else {
          allSucceeded = false
        }
      }

      if let floodMaxHops, floodMaxHops != originalFloodMaxHops {
        let response = try await helper.sendAndWait("set flood.max \(floodMaxHops)")
        if case .ok = CLIResponse.parse(response) {
          originalFloodMaxHops = floodMaxHops
        } else {
          allSucceeded = false
        }
      }

      if allSucceeded {
        await helper.flashSuccess(
          setApplying: { helper.isApplying = $0 },
          setSuccess: { behaviorApplySuccess = $0 }
        )
        return
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }

  // MARK: - Region Methods

  func fetchRegions() async {
    isLoadingRegions = true
    regionsError = false

    do {
      let treeResponse = try await helper.sendAndWait("region", timeout: .seconds(10), rawMatching: true)
      let parsed = Self.parseRegionTree(treeResponse)
      regions = parsed
      originalRegions = parsed
      do {
        let defaultReply = try await helper.sendAndWait(
          "region default",
          timeout: .seconds(10),
          rawMatching: true
        )
        if let parsed = Self.parseDefaultScopeReply(defaultReply) {
          applyParsedDefaultScope(parsed)
        } else {
          logger.warning("Unparsed region default reply: \(defaultReply)")
        }
      } catch {
        logger.warning("Failed to fetch default scope: \(error)")
      }
    } catch {
      if case RemoteNodeError.timeout = error {
        regionsError = true
      }
      logger.warning("Failed to fetch regions: \(error)")
    }

    isLoadingRegions = false
  }

  static func parseRegionTree(_ response: String) -> [RepeaterRegionEntry] {
    var entries: [RepeaterRegionEntry] = []
    let lines = response.split(separator: "\n", omittingEmptySubsequences: true)

    for line in lines {
      var text = String(line)
      text = String(text.drop(while: { $0 == " " }))
      guard !text.isEmpty else { continue }

      let floodAllowed: Bool
      if text.hasSuffix(" F") {
        floodAllowed = true
        text = String(text.dropLast(2))
      } else {
        floodAllowed = false
      }

      let isHome: Bool
      if text.hasSuffix("^") {
        isHome = true
        text = String(text.dropLast(1))
      } else {
        isHome = false
      }

      guard !text.isEmpty else { continue }

      entries.append(RepeaterRegionEntry(
        name: text,
        floodAllowed: floodAllowed,
        isHome: isHome
      ))
    }

    return entries
  }

  /// Unset (`<null>` or `*`) versus a named region.
  enum ParsedDefaultScope: Equatable {
    case cleared
    case named(String)
  }

  /// Nil is an unparsed reply, not an unset scope.
  static func parseDefaultScopeReply(_ response: String) -> ParsedDefaultScope? {
    let lines = response.split(separator: "\n", omittingEmptySubsequences: true)
    guard let last = lines.last else { return nil }
    var line = last.trimmingCharacters(in: .whitespaces)
    if line.hasPrefix(">") {
      line = String(line.dropFirst()).trimmingCharacters(in: .whitespaces)
    }
    guard line.localizedCaseInsensitiveContains(defaultScopeReplyMarker) else { return nil }

    let tokens = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
    guard let lastToken = tokens.last else { return nil }
    // Firmware treats a default of `*` as unset, same as `<null>`.
    if lastToken == firmwareNullToken || lastToken == wildcardName { return .cleared }
    return .named(lastToken)
  }

  private func applyParsedDefaultScope(_ parsed: ParsedDefaultScope) {
    switch parsed {
    case .cleared:
      defaultScopeName = nil
    case let .named(name):
      defaultScopeName = name
    }
    defaultScopeLoaded = true
  }

  func toggleRegionFlood(name: String) async {
    guard let index = regions.firstIndex(where: { $0.name == name }) else { return }
    let currentlyAllowed = regions[index].floodAllowed
    let command = currentlyAllowed ? "region denyf \(name)" : "region allowf \(name)"

    helper.isApplying = true
    helper.errorMessage = nil

    do {
      let response = try await helper.sendAndWait(command)
      if case .ok = CLIResponse.parse(response) {
        regions[index].floodAllowed = !currentlyAllowed
        hasUnsavedRegionChanges = true
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }

  func setDefaultScope(name: String?) async {
    if name == Self.wildcardName { return }
    if name == defaultScopeName { return }

    let argument = name ?? Self.firmwareNullToken
    let command = "region default \(argument)"

    helper.isApplying = true
    helper.errorMessage = nil

    do {
      let response = try await helper.sendAndWait(command, rawMatching: true)
      if response.contains(Self.defaultScopeSetReplyMarker) {
        defaultScopeName = name
        defaultScopeLoaded = true
        if let name, let index = regions.firstIndex(where: { $0.name == name }) {
          regions[index].floodAllowed = true
        }
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }

  func addRegion(name: String) async {
    let trimmed = name.trimmingCharacters(in: .whitespaces)
    if let validationError = RegionNameValidator.validate(trimmed, existingRegions: regions.map(\.name)) {
      switch validationError {
      case .empty: return
      case .invalidCharacters, .tooLong, .duplicate:
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed
      }
      return
    }

    helper.isApplying = true
    helper.errorMessage = nil

    do {
      let response = try await helper.sendAndWait("region put \(trimmed)")
      if case .ok = CLIResponse.parse(response) {
        regions.append(RepeaterRegionEntry(
          name: trimmed,
          floodAllowed: true,
          isHome: false
        ))
        hasUnsavedRegionChanges = true
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }

  func removeRegion(name: String) async {
    helper.isApplying = true
    helper.errorMessage = nil
    let wasDefault = defaultScopeName == name

    do {
      let response = try await helper.sendAndWait("region remove \(name)")
      if case .ok = CLIResponse.parse(response) {
        regions.removeAll { $0.name == name }
        hasUnsavedRegionChanges = true
        if wasDefault {
          let clearReply = try await helper.sendAndWait(
            "region default \(Self.firmwareNullToken)",
            rawMatching: true
          )
          if clearReply.contains(Self.defaultScopeSetReplyMarker) {
            defaultScopeName = nil
          } else {
            helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion
          }
        }
      } else if response.contains("not empty") {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.notEmpty
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.removeFailed
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }

  func saveRegions() async {
    helper.isApplying = true
    helper.errorMessage = nil

    do {
      let response = try await helper.sendAndWait("region save")
      if case .ok = CLIResponse.parse(response) {
        hasUnsavedRegionChanges = false
        await helper.flashSuccess(
          setApplying: { helper.isApplying = $0 },
          setSuccess: { regionsSaveSuccess = $0 }
        )
        return
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.saveFailed
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }
}

// MARK: - Region Entry

struct RepeaterRegionEntry: Identifiable, Equatable {
  var id: String {
    name
  }

  let name: String
  var floodAllowed: Bool
  var isHome: Bool
  var isWildcard: Bool {
    name == RepeaterSettingsViewModel.wildcardName
  }
}
