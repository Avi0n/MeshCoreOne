import MC1Services
import OSLog
import SwiftUI

@Observable
@MainActor
final class RepeaterSettingsViewModel {
  // MARK: - Shared Helper

  var helper = NodeSettingsViewModel()

  // MARK: - Repeater-Only: Behavior Settings

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

  var repeaterEnabled: Bool?
  private var originalRepeaterEnabled: Bool?
  var isLoadingBehavior = false
  var behaviorError = false
  var behaviorLoaded: Bool {
    repeaterEnabled != nil || behavior.hasValues
  }

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

  var behaviorApplySuccess = false

  var behaviorSettingsModified: Bool {
    (repeaterEnabled != nil && repeaterEnabled != originalRepeaterEnabled) || behavior.isModified
  }

  var hasUncommittedSettingsEdits: Bool {
    helper.hasUncommittedSharedSettingsEdits || behaviorSettingsModified
  }

  func revertUncommittedSettingsEdits() {
    helper.revertUncommittedSharedSettingsEdits()
    behavior.revert()
    repeaterEnabled = originalRepeaterEnabled
  }

  func bindSettingsVisitReset() {
    helper.onCollapseExtraSettingsSections = { [weak self] in
      self?.isBehaviorExpanded = false
      self?.isRegionsExpanded = false
    }
    helper.onClearCachedExtraSettings = { [weak self] in
      self?.clearCachedRepeaterSettings()
    }
  }

  private func clearCachedRepeaterSettings() {
    behavior.clear()
    repeaterEnabled = nil
    originalRepeaterEnabled = nil
    isLoadingBehavior = false
    behaviorError = false
    isLoadingRegions = false
    regionsError = false
    isLoadingDefaultScope = false
    guard !hasUnsavedRegionChanges else { return }
    regions = []
    originalRegions = nil
    defaultScopeName = nil
    defaultScopeLoaded = false
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
    installTransport(repeaterAdminService: repeaterAdminService, session: session)
    seedUnloadedName(session.name)
    guard let repeaterAdminService = repeaterAdminService() else { return }
    await registerCLIHandler(on: repeaterAdminService)
    if helper.firmwareVersion == nil, !isLoadingNodeInfo {
      Task { await fetchNodeInfo() }
    }
  }

  /// Points send closures and the CLI handler at the service the provider returns now.
  /// Does not read node info. A container change is not a new visit.
  func rebind(repeaterAdminService: @escaping @MainActor () -> RepeaterAdminService?, session: RemoteNodeSessionDTO) async {
    installTransport(repeaterAdminService: repeaterAdminService, session: session)
    guard let repeaterAdminService = repeaterAdminService() else { return }
    await registerCLIHandler(on: repeaterAdminService)
  }

  private func installTransport(
    repeaterAdminService: @escaping @MainActor () -> RepeaterAdminService?,
    session: RemoteNodeSessionDTO
  ) {
    bindSettingsVisitReset()
    repeaterAdminServiceProvider = repeaterAdminService
    helper.configure(
      session: session,
      sendCommand: { [weak self] id, command, timeout in
        guard let service = self?.repeaterAdminService else { throw NodeSettingsError.noService }
        return try await service.sendCommand(sessionID: id, command: command, timeout: timeout)
      },
      sendRawCommand: { [weak self] id, command, timeout in
        guard let service = self?.repeaterAdminService else { throw NodeSettingsError.noService }
        return try await service.sendRawCommand(sessionID: id, command: command, timeout: timeout)
      }
    )
    helper.onRevertUncommittedSettingsEdits = { [weak self] in
      self?.revertUncommittedSettingsEdits()
    }
    helper.onPreFetchNodeInfo = { [weak self] in
      await self?.fetchNodeInfo()
    }
    registerBehaviorLateRecovery()
  }

  private func registerCLIHandler(on repeaterAdminService: RepeaterAdminService) async {
    await repeaterAdminService.setCLIHandler { [weak self] message, _ in
      await MainActor.run {
        self?.helper.handleCommonLateResponse(message.text)
      }
    }
  }

  /// Contact display name is only a first-open placeholder. Once owner info
  /// or an edit has set `originalName`, leave the field alone on reconfigure.
  func seedUnloadedName(_ sessionName: String) {
    guard helper.originalName == nil else { return }
    helper.adoptPlaceholderName(sessionName)
  }

  /// Session CLI send. Reads the admin service at send time so a later container is used.
  func makeNodeCLISendClosure(
    session: RemoteNodeSessionDTO
  ) -> (@MainActor (_ command: String, _ timeout: Duration) async throws -> String)? {
    guard helper.session != nil else { return nil }
    let sessionID = session.id
    return { [weak self] command, timeout in
      guard let service = self?.repeaterAdminService else { throw NodeSettingsError.noService }
      return try await service.sendRawCommand(sessionID: sessionID, command: command, timeout: timeout)
    }
  }

  private var isLoadingNodeInfo = false

  private func fetchNodeInfo() async {
    guard !isLoadingNodeInfo, let session = helper.session, let repeaterAdminService else { return }
    isLoadingNodeInfo = true
    defer { isLoadingNodeInfo = false }
    let loadTicket = helper.beginSettingsLoad(fields: [.name, .ownerInfo])
    do {
      let response = try await repeaterAdminService.requestOwnerInfo(sessionID: session.id)
      helper.setNodeInfo(
        firmwareVersion: response.firmwareVersion,
        name: response.nodeName,
        ownerInfo: response.ownerInfo,
        loadTicket: loadTicket
      )
    } catch {
      logger.warning("Failed to fetch node info via binary: \(error)")
    }
  }

  // MARK: - Late Reply Recovery

  private var behaviorSectionComplete: Bool {
    originalRepeaterEnabled != nil && behavior.originalsReady
  }

  private func registerBehaviorLateRecovery() {
    helper.registerLateRecovery(query: "get repeat") { [weak self] value in
      guard let self,
            helper.isSettingsLoadCurrent(query: "get repeat", field: .repeaterEnabled),
            case let .repeatMode(enabled) = value else { return }
      repeaterEnabled = enabled
      originalRepeaterEnabled = enabled
      behaviorError = !behaviorSectionComplete
    }
    behavior.registerLateRecovery(on: helper) { [weak self] in
      self?.behaviorSectionComplete == false
    } setSectionError: { [weak self] hasError in
      self?.behaviorError = hasError
    }
  }

  // MARK: - Behavior Fetch/Apply

  func fetchBehaviorSettings() async {
    await behavior.fetch(
      using: helper,
      extraQueries: [
        SharedNodeBehavior.ExtraQuery(query: "get repeat", field: .repeaterEnabled) { [weak self] response in
          guard let self,
                case let .repeatMode(enabled) = CLIResponse.parse(response, forQuery: "get repeat") else { return }
          self.repeaterEnabled = enabled
          self.originalRepeaterEnabled = enabled
        }
      ],
      setLoading: { self.isLoadingBehavior = $0 },
      setError: { self.behaviorError = $0 }
    )
  }

  func applyBehaviorSettings() async {
    let snapshotRepeaterEnabled = repeaterEnabled
    await behavior.apply(
      using: helper,
      lease: helper.isApplying,
      extraFields: [.repeaterEnabled],
      sendExtras: { [weak self] in
        guard let self else { return true }
        guard let snapshotRepeaterEnabled, snapshotRepeaterEnabled != self.originalRepeaterEnabled else {
          return true
        }
        let response = try await self.helper.sendAndWait(
          "set repeat \(snapshotRepeaterEnabled ? "on" : "off")"
        )
        if case .ok = CLIResponse.parse(response) {
          self.originalRepeaterEnabled = snapshotRepeaterEnabled
          return true
        }
        return false
      },
      setSuccess: { self.behaviorApplySuccess = $0 }
    )
  }
}
