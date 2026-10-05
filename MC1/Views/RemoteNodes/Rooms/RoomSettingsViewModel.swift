import MC1Services
import OSLog
import SwiftUI

@Observable
@MainActor
final class RoomSettingsViewModel {
  // MARK: - Shared Helper

  var helper = NodeSettingsViewModel()

  // MARK: - Room Access (guest password + read-only)

  var guestPassword: String?
  var allowReadOnly: Bool?
  private var originalGuestPassword: String?
  private var originalAllowReadOnly: Bool?
  var isLoadingRoomAccess = false
  var roomAccessError = false
  var isApplyingRoomAccess = false
  var roomAccessApplySuccess = false
  var isRoomAccessExpanded = false

  var roomAccessLoaded: Bool {
    guestPassword != nil || allowReadOnly != nil
  }

  var roomAccessModified: Bool {
    (guestPassword != nil && guestPassword != originalGuestPassword) ||
      (allowReadOnly != nil && allowReadOnly != originalAllowReadOnly)
  }

  // MARK: - Behavior (advert intervals + flood)

  var advertIntervalMinutes: Int?
  var floodAdvertIntervalHours: Int?
  var floodMaxHops: Int?
  private var originalAdvertIntervalMinutes: Int?
  private var originalFloodAdvertIntervalHours: Int?
  private var originalFloodMaxHops: Int?
  var isLoadingBehavior = false
  var behaviorError = false
  var isApplyingBehavior = false
  var behaviorApplySuccess = false
  var isBehaviorExpanded = false

  var advertIntervalError: String?
  var floodAdvertIntervalError: String?
  var floodMaxHopsError: String?

  var behaviorLoaded: Bool {
    advertIntervalMinutes != nil || floodAdvertIntervalHours != nil || floodMaxHops != nil
  }

  var behaviorModified: Bool {
    (advertIntervalMinutes != nil && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
      (floodAdvertIntervalHours != nil && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
      (floodMaxHops != nil && floodMaxHops != originalFloodMaxHops)
  }

  var hasUncommittedSettingsEdits: Bool {
    helper.hasUncommittedSharedSettingsEdits || behaviorModified || roomAccessModified
  }

  func revertUncommittedSettingsEdits() {
    helper.revertUncommittedSharedSettingsEdits()
    advertIntervalMinutes = originalAdvertIntervalMinutes
    floodAdvertIntervalHours = originalFloodAdvertIntervalHours
    floodMaxHops = originalFloodMaxHops
    guestPassword = originalGuestPassword
    allowReadOnly = originalAllowReadOnly
    advertIntervalError = nil
    floodAdvertIntervalError = nil
    floodMaxHopsError = nil
  }

  func bindSettingsVisitReset() {
    helper.onCollapseExtraSettingsSections = { [weak self] in
      self?.isRoomAccessExpanded = false
      self?.isBehaviorExpanded = false
    }
    helper.onClearCachedExtraSettings = { [weak self] in
      self?.clearCachedRoomSettings()
    }
  }

  private func clearCachedRoomSettings() {
    guestPassword = nil
    allowReadOnly = nil
    originalGuestPassword = nil
    originalAllowReadOnly = nil
    isLoadingRoomAccess = false
    roomAccessError = false
    advertIntervalMinutes = nil
    floodAdvertIntervalHours = nil
    floodMaxHops = nil
    originalAdvertIntervalMinutes = nil
    originalFloodAdvertIntervalHours = nil
    originalFloodMaxHops = nil
    isLoadingBehavior = false
    behaviorError = false
    advertIntervalError = nil
    floodAdvertIntervalError = nil
    floodMaxHopsError = nil
  }

  // MARK: - Dependencies

  private var roomAdminServiceProvider: @MainActor () -> RoomAdminService? = { nil }
  var roomAdminService: RoomAdminService? {
    roomAdminServiceProvider()
  }

  private let logger = Logger(subsystem: "com.mc1", category: "RoomSettings")

  // MARK: - Cleanup

  func cleanup() async {
    await roomAdminService?.setCLIHandler { _, _ in }
    helper.cleanup()
  }

  // MARK: - Configuration

  /// Nil service mirrors a disconnected state; commands then no-op.
  func configure(roomAdminService: @escaping @MainActor () -> RoomAdminService?, session: RemoteNodeSessionDTO) async {
    bindSettingsVisitReset()
    roomAdminServiceProvider = roomAdminService

    guard let roomAdminService = roomAdminService() else { return }

    helper.configure(
      session: session,
      sendCommand: { [roomAdminService] id, cmd, timeout in
        try await roomAdminService.sendCommand(sessionID: id, command: cmd, timeout: timeout)
      },
      sendRawCommand: { [roomAdminService] id, cmd, timeout in
        try await roomAdminService.sendRawCommand(sessionID: id, command: cmd, timeout: timeout)
      }
    )

    helper.onRevertUncommittedSettingsEdits = { [weak self] in
      self?.revertUncommittedSettingsEdits()
    }
    helper.otherSettingsApplyInFlight = { [weak self] in
      guard let self else { return false }
      return isApplyingRoomAccess || isApplyingBehavior
    }

    seedUnloadedIdentity(session.name)

    // Room doesn't have binary protocol for node info — firmware fetched via CLI
    helper.onPreFetchNodeInfo = nil

    registerBehaviorLateRecovery()
    registerRoomAccessLateRecovery()

    // Register CLI handler for late responses
    await roomAdminService.setCLIHandler { [weak self] message, _ in
      await MainActor.run {
        self?.helper.handleCommonLateResponse(message.text)
      }
    }

    refreshDeviceClockIfIdle()
  }

  /// First open seeds both `name` and `originalName` from the contact so Apply
  /// is clean until `get name` or an edit. Later configures leave them alone.
  func seedUnloadedIdentity(_ sessionName: String) {
    guard helper.originalName == nil else { return }
    guard helper.allowsSettingsLoadWrite(.name, ticket: nil) else { return }
    helper.setNodeInfo(firmwareVersion: nil, name: sessionName, ownerInfo: nil)
  }

  /// Reconfigure refreshes device time via `clock`. `fetchDeviceInfo` still
  /// skips `ver` when the firmware version is already known.
  func refreshDeviceClockIfIdle() {
    guard !helper.isLoadingDeviceInfo else { return }
    Task { await helper.fetchDeviceInfo() }
  }

  /// Builds the node-CLI send closure, pre-binding this session's id and
  /// capturing the private admin service (a thin pass-through to
  /// `RemoteNodeService.sendRawCLICommand`). Returns nil if not configured.
  func makeNodeCLISendClosure(
    session: RemoteNodeSessionDTO
  ) -> (@MainActor (_ command: String, _ timeout: Duration) async throws -> String)? {
    guard let roomAdminService else { return nil }
    return { [roomAdminService, sessionID = session.id] command, timeout in
      try await roomAdminService.sendRawCommand(
        sessionID: sessionID, command: command, timeout: timeout
      )
    }
  }

  // MARK: - Late Reply Recovery

  private var behaviorSectionComplete: Bool {
    originalAdvertIntervalMinutes != nil && originalFloodAdvertIntervalHours != nil
      && originalFloodMaxHops != nil
  }

  private func registerBehaviorLateRecovery() {
    helper.registerLateRecovery(query: "get advert.interval") { [weak self] value in
      guard let self,
            helper.isSettingsLoadCurrent(query: "get advert.interval", field: .advertInterval),
            case let .advertInterval(minutes) = value else { return }
      advertIntervalMinutes = minutes
      originalAdvertIntervalMinutes = minutes
      behaviorError = !behaviorSectionComplete
    }
    helper.registerLateRecovery(query: "get flood.advert.interval") { [weak self] value in
      guard let self,
            helper.isSettingsLoadCurrent(query: "get flood.advert.interval", field: .floodAdvertInterval),
            case let .floodAdvertInterval(hours) = value else { return }
      floodAdvertIntervalHours = hours
      originalFloodAdvertIntervalHours = hours
      behaviorError = !behaviorSectionComplete
    }
    helper.registerLateRecovery(query: "get flood.max") { [weak self] value in
      guard let self,
            helper.isSettingsLoadCurrent(query: "get flood.max", field: .floodMaxHops),
            case let .floodMax(hops) = value else { return }
      floodMaxHops = hops
      originalFloodMaxHops = hops
      behaviorError = !behaviorSectionComplete
    }
  }

  private func registerRoomAccessLateRecovery() {
    helper.registerLateRecovery(query: "get guest.password") { [weak self] value in
      guard let self,
            helper.isSettingsLoadCurrent(query: "get guest.password", field: .guestPassword)
      else { return }
      applyGuestPasswordResponse(value)
    }
    helper.registerLateRecovery(query: "get allow.read.only") { [weak self] value in
      guard let self,
            helper.isSettingsLoadCurrent(query: "get allow.read.only", field: .allowReadOnly),
            case let .raw(raw) = value else { return }
      let isOn = raw.lowercased() == "on"
      allowReadOnly = isOn
      originalAllowReadOnly = isOn
    }
  }

  private func applyGuestPasswordResponse(_ value: CLIResponse) {
    switch value {
    case .ok, .error, .unknownCommand:
      guestPassword = ""
      originalGuestPassword = ""
    case let .raw(raw):
      guestPassword = raw
      originalGuestPassword = raw
    default:
      break
    }
  }

  // MARK: - Room Access Fetch/Apply

  func fetchRoomAccess() async {
    let visit = helper.captureSettingsVisit()
    guard helper.isSettingsVisitCurrent(visit) else { return }
    isLoadingRoomAccess = true
    roomAccessError = false
    var hadTimeout = false

    let guestTicket = helper.beginSettingsLoad(query: "get guest.password", fields: [.guestPassword])
    do {
      let response = try await helper.sendAndWait("get guest.password", rawMatching: true)
      if helper.isSettingsLoadCurrent(guestTicket, field: .guestPassword) {
        let parsed = CLIResponse.parse(response, forQuery: "get guest.password")
        switch parsed {
        case .ok, .error, .unknownCommand:
          guestPassword = ""
          originalGuestPassword = ""
        default:
          let trimmed = response.trimmingCharacters(in: .whitespacesAndNewlines)
          let value = trimmed.hasPrefix("> ") ? String(trimmed.dropFirst(2)) : trimmed
          guestPassword = value
          originalGuestPassword = value
        }
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get guest password: \(error)")
    }

    let readOnlyTicket = helper.beginSettingsLoad(
      query: "get allow.read.only",
      fields: [.allowReadOnly]
    )
    do {
      let response = try await helper.sendAndWait("get allow.read.only", rawMatching: true)
      if helper.isSettingsLoadCurrent(readOnlyTicket, field: .allowReadOnly) {
        let parsed = CLIResponse.parse(response, forQuery: "get allow.read.only")
        switch parsed {
        case let .raw(value):
          let isOn = value.lowercased() == "on"
          allowReadOnly = isOn
          originalAllowReadOnly = isOn
        default:
          break
        }
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get allow read only: \(error)")
    }

    helper.finishSettingsLoad(
      visit,
      timedOut: hadTimeout,
      setError: { roomAccessError = $0 },
      setLoading: { isLoadingRoomAccess = $0 }
    )
  }

  func applyRoomAccess() async {
    let snapshotGuestPassword = guestPassword
    let snapshotAllowReadOnly = allowReadOnly
    let owned: [NodeSettingsViewModel.OwnedSettingsField] = [.guestPassword, .allowReadOnly]
    helper.takeApplyOwnership(of: owned)
    isApplyingRoomAccess = true
    helper.errorMessage = nil
    defer {
      isApplyingRoomAccess = false
      helper.releaseApplyOwnership(of: owned)
      helper.revertAbandonedDraftIfIdle()
    }

    do {
      var allSucceeded = true

      if let snapshotGuestPassword, snapshotGuestPassword != originalGuestPassword {
        let response = try await helper.sendAndWait("set guest.password \(snapshotGuestPassword)")
        if case .ok = CLIResponse.parse(response) {
          originalGuestPassword = snapshotGuestPassword
        } else {
          allSucceeded = false
        }
      }

      if let snapshotAllowReadOnly, snapshotAllowReadOnly != originalAllowReadOnly {
        let response = try await helper.sendAndWait(
          "set allow.read.only \(snapshotAllowReadOnly ? "on" : "off")"
        )
        if case .ok = CLIResponse.parse(response) {
          originalAllowReadOnly = snapshotAllowReadOnly
        } else {
          allSucceeded = false
        }
      }

      if allSucceeded {
        await helper.flashSuccess(
          setApplying: { isApplyingRoomAccess = $0 },
          setSuccess: { roomAccessApplySuccess = $0 }
        )
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }
  }

  // MARK: - Behavior Fetch/Apply

  func fetchBehaviorSettings() async {
    let visit = helper.captureSettingsVisit()
    guard helper.isSettingsVisitCurrent(visit) else { return }
    isLoadingBehavior = true
    behaviorError = false
    var hadTimeout = false

    let advertTicket = helper.beginSettingsLoad(query: "get advert.interval", fields: [.advertInterval])
    do {
      let response = try await helper.sendAndWait("get advert.interval")
      if helper.isSettingsLoadCurrent(advertTicket, field: .advertInterval),
         case let .advertInterval(minutes) = CLIResponse.parse(response, forQuery: "get advert.interval") {
        advertIntervalMinutes = minutes
        originalAdvertIntervalMinutes = minutes
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get advert interval: \(error)")
    }

    let floodAdvertTicket = helper.beginSettingsLoad(
      query: "get flood.advert.interval",
      fields: [.floodAdvertInterval]
    )
    do {
      let response = try await helper.sendAndWait("get flood.advert.interval")
      if helper.isSettingsLoadCurrent(floodAdvertTicket, field: .floodAdvertInterval),
         case let .floodAdvertInterval(hours) = CLIResponse.parse(
           response, forQuery: "get flood.advert.interval"
         ) {
        floodAdvertIntervalHours = hours
        originalFloodAdvertIntervalHours = hours
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get flood advert interval: \(error)")
    }

    let floodMaxTicket = helper.beginSettingsLoad(query: "get flood.max", fields: [.floodMaxHops])
    do {
      let response = try await helper.sendAndWait("get flood.max")
      if helper.isSettingsLoadCurrent(floodMaxTicket, field: .floodMaxHops),
         case let .floodMax(hops) = CLIResponse.parse(response, forQuery: "get flood.max") {
        floodMaxHops = hops
        originalFloodMaxHops = hops
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get flood max: \(error)")
    }

    helper.finishSettingsLoad(
      visit,
      timedOut: hadTimeout,
      setError: { behaviorError = $0 },
      setLoading: { isLoadingBehavior = $0 }
    )
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

    let snapshotAdvertInterval = advertIntervalMinutes
    let snapshotFloodAdvertInterval = floodAdvertIntervalHours
    let snapshotFloodMaxHops = floodMaxHops
    let owned: [NodeSettingsViewModel.OwnedSettingsField] = [
      .advertInterval, .floodAdvertInterval, .floodMaxHops
    ]
    helper.takeApplyOwnership(of: owned)
    isApplyingBehavior = true
    helper.errorMessage = nil
    defer {
      isApplyingBehavior = false
      helper.releaseApplyOwnership(of: owned)
      helper.revertAbandonedDraftIfIdle()
    }

    do {
      var allSucceeded = true

      if let snapshotAdvertInterval, snapshotAdvertInterval != originalAdvertIntervalMinutes {
        let response = try await helper.sendAndWait("set advert.interval \(snapshotAdvertInterval)")
        if case .ok = CLIResponse.parse(response) {
          originalAdvertIntervalMinutes = snapshotAdvertInterval
        } else {
          allSucceeded = false
        }
      }

      if let snapshotFloodAdvertInterval,
         snapshotFloodAdvertInterval != originalFloodAdvertIntervalHours {
        let response = try await helper.sendAndWait(
          "set flood.advert.interval \(snapshotFloodAdvertInterval)"
        )
        if case .ok = CLIResponse.parse(response) {
          originalFloodAdvertIntervalHours = snapshotFloodAdvertInterval
        } else {
          allSucceeded = false
        }
      }

      if let snapshotFloodMaxHops, snapshotFloodMaxHops != originalFloodMaxHops {
        let response = try await helper.sendAndWait("set flood.max \(snapshotFloodMaxHops)")
        if case .ok = CLIResponse.parse(response) {
          originalFloodMaxHops = snapshotFloodMaxHops
        } else {
          allSucceeded = false
        }
      }

      if allSucceeded {
        await helper.flashSuccess(
          setApplying: { isApplyingBehavior = $0 },
          setSuccess: { behaviorApplySuccess = $0 }
        )
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }
  }
}
