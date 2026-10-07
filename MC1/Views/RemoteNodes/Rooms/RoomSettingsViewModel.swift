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
  var isApplyingRoomAccess = ApplyLease()
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

  var behavior = SharedNodeBehavior()
  var advertIntervalMinutes: Int? {
    get { behavior.advertIntervalMinutes }
    set { behavior.advertIntervalMinutes = newValue }
  }

  var floodAdvertIntervalHours: Int? {
    get { behavior.floodAdvertIntervalHours }
    set { behavior.floodAdvertIntervalHours = newValue }
  }

  var floodMaxHops: Int? {
    get { behavior.floodMaxHops }
    set { behavior.floodMaxHops = newValue }
  }

  var isLoadingBehavior = false
  var behaviorError = false
  var isApplyingBehavior = ApplyLease()
  var behaviorApplySuccess = false
  var isBehaviorExpanded = false

  var advertIntervalError: String? {
    get { behavior.advertIntervalError }
    set { behavior.advertIntervalError = newValue }
  }

  var floodAdvertIntervalError: String? {
    get { behavior.floodAdvertIntervalError }
    set { behavior.floodAdvertIntervalError = newValue }
  }

  var floodMaxHopsError: String? {
    get { behavior.floodMaxHopsError }
    set { behavior.floodMaxHopsError = newValue }
  }

  var behaviorLoaded: Bool {
    behavior.hasValues
  }

  var behaviorModified: Bool {
    behavior.isModified
  }

  var hasUncommittedSettingsEdits: Bool {
    helper.hasUncommittedSharedSettingsEdits || behaviorModified || roomAccessModified
  }

  func revertUncommittedSettingsEdits() {
    helper.revertUncommittedSharedSettingsEdits()
    behavior.revert()
    guestPassword = originalGuestPassword
    allowReadOnly = originalAllowReadOnly
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
    behavior.clear()
    isLoadingBehavior = false
    behaviorError = false
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
    installTransport(roomAdminService: roomAdminService, session: session)
    seedUnloadedIdentity(session.name)
    guard let roomAdminService = roomAdminService() else { return }
    await registerCLIHandler(on: roomAdminService)
    refreshDeviceClockIfIdle()
  }

  /// Points send closures and the CLI handler at the service the provider returns now.
  /// Does not send `clock` or `ver`. A container change is not a new visit.
  func rebind(roomAdminService: @escaping @MainActor () -> RoomAdminService?, session: RemoteNodeSessionDTO) async {
    installTransport(roomAdminService: roomAdminService, session: session)
    guard let roomAdminService = roomAdminService() else { return }
    await registerCLIHandler(on: roomAdminService)
  }

  private func installTransport(
    roomAdminService: @escaping @MainActor () -> RoomAdminService?,
    session: RemoteNodeSessionDTO
  ) {
    bindSettingsVisitReset()
    roomAdminServiceProvider = roomAdminService
    helper.configure(
      session: session,
      sendCommand: { [weak self] id, command, timeout in
        guard let service = self?.roomAdminService else { throw NodeSettingsError.noService }
        return try await service.sendCommand(sessionID: id, command: command, timeout: timeout)
      },
      sendRawCommand: { [weak self] id, command, timeout in
        guard let service = self?.roomAdminService else { throw NodeSettingsError.noService }
        return try await service.sendRawCommand(sessionID: id, command: command, timeout: timeout)
      }
    )
    helper.onRevertUncommittedSettingsEdits = { [weak self] in
      self?.revertUncommittedSettingsEdits()
    }
    helper.otherSettingsApplyInFlight = { [weak self] in
      guard let self else { return false }
      return isApplyingRoomAccess.inFlight || isApplyingBehavior.inFlight
    }
    // Room firmware comes from CLI `ver`, not a binary prefetch.
    helper.onPreFetchNodeInfo = nil
    registerBehaviorLateRecovery()
    registerRoomAccessLateRecovery()
  }

  private func registerCLIHandler(on roomAdminService: RoomAdminService) async {
    await roomAdminService.setCLIHandler { [weak self] message, _ in
      await MainActor.run {
        self?.helper.handleCommonLateResponse(message.text)
      }
    }
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

  /// Session CLI send. Reads the admin service at send time so a later container is used.
  func makeNodeCLISendClosure(
    session: RemoteNodeSessionDTO
  ) -> (@MainActor (_ command: String, _ timeout: Duration) async throws -> String)? {
    guard helper.session != nil else { return nil }
    let sessionID = session.id
    return { [weak self] command, timeout in
      guard let service = self?.roomAdminService else { throw NodeSettingsError.noService }
      return try await service.sendRawCommand(sessionID: sessionID, command: command, timeout: timeout)
    }
  }

  // MARK: - Late Reply Recovery

  private func registerBehaviorLateRecovery() {
    behavior.registerLateRecovery(on: helper) { [weak self] in
      self?.behavior.originalsReady == false
    } setSectionError: { [weak self] hasError in
      self?.behaviorError = hasError
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

    let guestTimedOut = await helper.loadCLIField(
      query: "get guest.password",
      field: .guestPassword,
      rawMatching: true
    ) { [weak self] response in
      self?.applyGuestPasswordResponse(CLIResponse.parse(response, forQuery: "get guest.password"))
    }
    if guestTimedOut { hadTimeout = true }

    let readOnlyTimedOut = await helper.loadCLIField(
      query: "get allow.read.only",
      field: .allowReadOnly,
      rawMatching: true
    ) { [weak self] response in
      guard let self,
            case let .raw(value) = CLIResponse.parse(response, forQuery: "get allow.read.only") else { return }
      let isOn = value.lowercased() == "on"
      self.allowReadOnly = isOn
      self.originalAllowReadOnly = isOn
    }
    if readOnlyTimedOut { hadTimeout = true }

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
    let claims = helper.takeApplyOwnership(of: [.guestPassword, .allowReadOnly])
    let leaseID = isApplyingRoomAccess.begin()
    helper.errorMessage = nil
    defer { helper.finishApply(isApplyingRoomAccess, id: leaseID, claims: claims) }

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
          lease: isApplyingRoomAccess,
          id: leaseID,
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
    await behavior.fetch(
      using: helper,
      setLoading: { self.isLoadingBehavior = $0 },
      setError: { self.behaviorError = $0 }
    )
  }

  func applyBehaviorSettings() async {
    await behavior.apply(
      using: helper,
      lease: isApplyingBehavior,
      setSuccess: { self.behaviorApplySuccess = $0 }
    )
  }
}
