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

  var regions: [RepeaterRegionEntry] = []
  var originalRegions: [RepeaterRegionEntry]?
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
  var isLoadingDefaultScope = false
  /// `region default` exists on MeshCore repeater v1.15.0+. Unknown version is unsupported.
  var supportsRegionDefaultScope: Bool {
    helper.firmwareVersion?.isAtLeast(
      major: Firmware.regionDefaultScopeMinMajor,
      minor: Firmware.regionDefaultScopeMinMinor
    ) == true
  }

  private enum Firmware {
    static let regionDefaultScopeMinMajor = 1
    static let regionDefaultScopeMinMinor = 15
  }

  // MARK: - Expansion State (repeater-only sections)

  var isBehaviorExpanded = false
  var isRegionsExpanded = false

  // MARK: - Dependencies

  private var repeaterAdminServiceProvider: @MainActor () -> RepeaterAdminService? = { nil }
  var repeaterAdminService: RepeaterAdminService? {
    repeaterAdminServiceProvider()
  }

  let logger = Logger(subsystem: "com.mc1", category: "RepeaterSettings")

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

    helper.onPreFetchNodeInfo = { [weak self] in
      await self?.fetchNodeInfo()
    }

    registerBehaviorLateRecovery()

    // Register CLI handler for late responses
    await repeaterAdminService.setCLIHandler { [weak self] message, contact in
      await MainActor.run {
        guard let self, self.matches(contact) else { return }
        self.helper.handleCommonLateResponse(message.text)
      }
    }

    // Detached so configure returns immediately and the node CLI send
    // closure wires without waiting on the owner-info round-trip (matches
    // RoomSettingsViewModel's detached device-info fetch).
    if !didScheduleNodeInfo, helper.firmwareVersion == nil, helper.ownerInfo == nil {
      didScheduleNodeInfo = true
      Task { await fetchNodeInfo() }
    }
  }

  private func matches(_ contact: ContactDTO) -> Bool {
    guard let session = helper.session else { return false }
    return session.publicKey.prefix(6) == contact.publicKey.prefix(6)
  }

  func reset() {
    behaviorEpoch.bump()
    behaviorTicket = nil
    regionsEpoch.bump()
    helper.reset()
    repeaterEnabled = nil
    originalRepeaterEnabled = nil
    advertIntervalMinutes = nil
    originalAdvertIntervalMinutes = nil
    floodAdvertIntervalHours = nil
    originalFloodAdvertIntervalHours = nil
    floodMaxHops = nil
    originalFloodMaxHops = nil
    behaviorError = false
    isLoadingBehavior = false
    isLoadingNodeInfo = false
    didScheduleNodeInfo = false
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
  private var didScheduleNodeInfo = false
  private var behaviorEpoch = Epoch()
  private var behaviorTicket: Epoch.Ticket?
  private var regionsEpoch = Epoch()

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
      guard let self, let behaviorTicket, behaviorTicket.isCurrent(in: behaviorEpoch) else { return }
      guard case let .repeatMode(enabled) = value else { return }
      _ = behaviorTicket.publish(enabled, current: &repeaterEnabled, baseline: &originalRepeaterEnabled, in: behaviorEpoch)
      behaviorError = false
      if behaviorSectionComplete { isLoadingBehavior = false }
    }
    helper.registerLateRecovery(query: "get advert.interval") { [weak self] value in
      guard let self, let behaviorTicket, behaviorTicket.isCurrent(in: behaviorEpoch) else { return }
      guard case let .advertInterval(minutes) = value else { return }
      _ = behaviorTicket.publish(
        minutes,
        current: &advertIntervalMinutes,
        baseline: &originalAdvertIntervalMinutes,
        in: behaviorEpoch
      )
      if behaviorSectionComplete { isLoadingBehavior = false }
    }
    helper.registerLateRecovery(query: "get flood.advert.interval") { [weak self] value in
      guard let self, let behaviorTicket, behaviorTicket.isCurrent(in: behaviorEpoch) else { return }
      guard case let .floodAdvertInterval(hours) = value else { return }
      _ = behaviorTicket.publish(
        hours,
        current: &floodAdvertIntervalHours,
        baseline: &originalFloodAdvertIntervalHours,
        in: behaviorEpoch
      )
      if behaviorSectionComplete { isLoadingBehavior = false }
    }
    helper.registerLateRecovery(query: "get flood.max") { [weak self] value in
      guard let self, let behaviorTicket, behaviorTicket.isCurrent(in: behaviorEpoch) else { return }
      guard case let .floodMax(hops) = value else { return }
      _ = behaviorTicket.publish(hops, current: &floodMaxHops, baseline: &originalFloodMaxHops, in: behaviorEpoch)
      if behaviorSectionComplete { isLoadingBehavior = false }
    }
  }

  // MARK: - Behavior Fetch/Apply

  func fetchBehaviorSettings() async {
    behaviorEpoch.bump()
    let ticket = behaviorEpoch.ticket()
    behaviorTicket = ticket
    isLoadingBehavior = true
    behaviorError = false
    var hadTimeout = false

    do {
      let response = try await helper.sendAndWait("get repeat")
      guard ticket.isCurrent(in: behaviorEpoch) else { return }
      if case let .repeatMode(enabled) = CLIResponse.parse(response, forQuery: "get repeat") {
        _ = ticket.publish(enabled, current: &repeaterEnabled, baseline: &originalRepeaterEnabled, in: behaviorEpoch)
      }
    } catch is CancellationError {
      return
    } catch {
      guard ticket.isCurrent(in: behaviorEpoch) else { return }
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get repeat mode: \(error)")
    }

    do {
      let response = try await helper.sendAndWait("get advert.interval")
      guard ticket.isCurrent(in: behaviorEpoch) else { return }
      if case let .advertInterval(minutes) = CLIResponse.parse(response, forQuery: "get advert.interval") {
        _ = ticket.publish(
          minutes,
          current: &advertIntervalMinutes,
          baseline: &originalAdvertIntervalMinutes,
          in: behaviorEpoch
        )
      }
    } catch is CancellationError {
      return
    } catch {
      guard ticket.isCurrent(in: behaviorEpoch) else { return }
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get advert interval: \(error)")
    }

    do {
      let response = try await helper.sendAndWait("get flood.advert.interval")
      guard ticket.isCurrent(in: behaviorEpoch) else { return }
      if case let .floodAdvertInterval(hours) = CLIResponse.parse(response, forQuery: "get flood.advert.interval") {
        _ = ticket.publish(
          hours,
          current: &floodAdvertIntervalHours,
          baseline: &originalFloodAdvertIntervalHours,
          in: behaviorEpoch
        )
      }
    } catch is CancellationError {
      return
    } catch {
      guard ticket.isCurrent(in: behaviorEpoch) else { return }
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get flood advert interval: \(error)")
    }

    do {
      let response = try await helper.sendAndWait("get flood.max")
      guard ticket.isCurrent(in: behaviorEpoch) else { return }
      if case let .floodMax(hops) = CLIResponse.parse(response, forQuery: "get flood.max") {
        _ = ticket.publish(hops, current: &floodMaxHops, baseline: &originalFloodMaxHops, in: behaviorEpoch)
      }
    } catch is CancellationError {
      return
    } catch {
      guard ticket.isCurrent(in: behaviorEpoch) else { return }
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get flood max: \(error)")
    }

    guard ticket.isCurrent(in: behaviorEpoch) else { return }
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

    helper.applyEpoch.bump()
    let ticket = helper.applyEpoch.ticket()
    let sentRepeaterEnabled = repeaterEnabled
    let sentAdvert = advertIntervalMinutes
    let sentFloodAdvert = floodAdvertIntervalHours
    let sentFloodMax = floodMaxHops
    helper.isApplying = true
    helper.errorMessage = nil

    do {
      var allSucceeded = true

      if let repeaterEnabled, repeaterEnabled != originalRepeaterEnabled {
        let response = try await helper.sendAndWait("set repeat \(repeaterEnabled ? "on" : "off")")
        guard ticket.isCurrent(in: helper.applyEpoch) else { return }
        if case .ok = CLIResponse.parse(response) {
          _ = ticket.adoptApplied(
            sentRepeaterEnabled,
            current: repeaterEnabled,
            baseline: &originalRepeaterEnabled,
            in: helper.applyEpoch
          )
        } else {
          allSucceeded = false
        }
      }

      if let advertIntervalMinutes, advertIntervalMinutes != originalAdvertIntervalMinutes {
        let response = try await helper.sendAndWait("set advert.interval \(advertIntervalMinutes)")
        guard ticket.isCurrent(in: helper.applyEpoch) else { return }
        if case .ok = CLIResponse.parse(response) {
          _ = ticket.adoptApplied(
            sentAdvert,
            current: advertIntervalMinutes,
            baseline: &originalAdvertIntervalMinutes,
            in: helper.applyEpoch
          )
        } else {
          allSucceeded = false
        }
      }

      if let floodAdvertIntervalHours, floodAdvertIntervalHours != originalFloodAdvertIntervalHours {
        let response = try await helper.sendAndWait("set flood.advert.interval \(floodAdvertIntervalHours)")
        guard ticket.isCurrent(in: helper.applyEpoch) else { return }
        if case .ok = CLIResponse.parse(response) {
          _ = ticket.adoptApplied(
            sentFloodAdvert,
            current: floodAdvertIntervalHours,
            baseline: &originalFloodAdvertIntervalHours,
            in: helper.applyEpoch
          )
        } else {
          allSucceeded = false
        }
      }

      if let floodMaxHops, floodMaxHops != originalFloodMaxHops {
        let response = try await helper.sendAndWait("set flood.max \(floodMaxHops)")
        guard ticket.isCurrent(in: helper.applyEpoch) else { return }
        if case .ok = CLIResponse.parse(response) {
          _ = ticket.adoptApplied(sentFloodMax, current: floodMaxHops, baseline: &originalFloodMaxHops, in: helper.applyEpoch)
        } else {
          allSucceeded = false
        }
      }

      guard ticket.isCurrent(in: helper.applyEpoch) else { return }
      if allSucceeded {
        await helper.flashSuccess(
          setApplying: { helper.isApplying = $0 },
          setSuccess: { behaviorApplySuccess = $0 }
        )
        return
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch is CancellationError {
      guard ticket.isCurrent(in: helper.applyEpoch) else { return }
    } catch {
      guard ticket.isCurrent(in: helper.applyEpoch) else { return }
      helper.errorMessage = error.userFacingMessage
    }

    guard ticket.isCurrent(in: helper.applyEpoch) else { return }
    helper.isApplying = false
  }
}
