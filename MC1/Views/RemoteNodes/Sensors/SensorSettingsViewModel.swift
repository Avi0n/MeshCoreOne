import MC1Services
import SwiftUI

@Observable
@MainActor
final class SensorSettingsViewModel {
  private(set) var settings: [SensorSetting] = []
  private(set) var isLoading = false
  private(set) var isMutating = false
  private(set) var hasLoaded = false
  private(set) var requiresReload = false
  private(set) var currentSession: RemoteNodeSessionDTO?
  private(set) var statusMessage: String?
  var errorMessage: String?

  private var drafts: [String: String] = [:]
  private var draftRevisions: [String: UInt] = [:]
  private var session: RemoteNodeSessionDTO?
  private var boundServiceID: ObjectIdentifier?
  private var remoteNodeServiceProvider: @MainActor () -> RemoteNodeService? = { nil }
  private var dataStoreProvider: @MainActor () -> PersistenceStore? = { nil }
  private var connectionStateProvider: @MainActor () -> DeviceConnectionState = { .disconnected }
  private var remoteNodeService: RemoteNodeService? {
    remoteNodeServiceProvider()
  }

  private var dataStore: PersistenceStore? {
    dataStoreProvider()
  }

  private var connectionState: DeviceConnectionState {
    connectionStateProvider()
  }

  private var epoch = Epoch()
  private var loadEpoch = Epoch()
  private var observationTask: Task<Void, Never>?
  private var mutationTask: Task<Void, Never>?

  subscript(draft key: String) -> String {
    get { drafts[key] ?? "" }
    set {
      drafts[key] = newValue
      draftRevisions[key, default: 0] &+= 1
    }
  }

  var isAuthorized: Bool {
    connectionState == .ready && remoteNodeService.map(ObjectIdentifier.init) == boundServiceID
      && currentSession?.isConnected == true && currentSession?.isAdmin == true
  }

  var canSend: Bool {
    isAuthorized && !isLoading && !isMutating
  }

  func canSave(_ key: String) -> Bool {
    canSend && hasLoaded && !requiresReload && settings.contains { $0.key == key && $0.value != self[draft: key] }
  }

  func isBound(to service: RemoteNodeService?) -> Bool {
    service.map(ObjectIdentifier.init) == boundServiceID
  }

  func configure(
    session: RemoteNodeSessionDTO,
    remoteNodeService: @escaping @MainActor () -> RemoteNodeService?,
    dataStore: @escaping @MainActor () -> PersistenceStore?,
    connectionState: @escaping @MainActor () -> DeviceConnectionState
  ) {
    remoteNodeServiceProvider = remoteNodeService
    dataStoreProvider = dataStore
    connectionStateProvider = connectionState
    let service = remoteNodeService()
    guard self.session?.id != session.id || !isBound(to: service) else { return }
    cancelOwnedWork()
    self.session = session
    boundServiceID = service.map(ObjectIdentifier.init)
    guard let service else { return }
    let ticket = epoch.ticket()
    let events = service.events()
    observationTask = Task { [weak self] in
      await self?.refreshSession(service: service, ticket: ticket)
      for await event in events {
        guard let self, ticket.isCurrent(in: epoch), !Task.isCancelled else { return }
        if case let .sessionStateChanged(id, connected) = event, id == session.id {
          if !connected { currentSession = nil }
          await refreshSession(service: service, ticket: ticket)
        }
      }
      guard let self, ticket.isCurrent(in: epoch) else { return }
      currentSession = nil
    }
  }

  private func refreshSession(service: RemoteNodeService, ticket: Epoch.Ticket) async {
    guard let session, let dataStore else { return }
    let updated = try? await dataStore.fetchRemoteNodeSession(id: session.id)
    guard ticket.isCurrent(in: epoch), !Task.isCancelled, remoteNodeService === service else { return }
    guard let updated, updated.isSensor, updated.radioID == session.radioID,
          updated.publicKey == session.publicKey else {
      currentSession = nil
      return
    }
    currentSession = updated
  }

  func loadIfNeeded() async {
    guard !hasLoaded else { return }
    await reload()
  }

  func reload() async {
    guard canSend, let service = remoteNodeService, let session else { return }
    let ticket = loadEpoch.ticket()
    let operation = epoch.ticket()
    let revisions = draftRevisions
    let baselines = Dictionary(uniqueKeysWithValues: settings.map { ($0.key, $0.value) })
    isLoading = true
    errorMessage = nil
    defer { if ticket.isCurrent(in: loadEpoch) { isLoading = false } }
    do {
      let loaded = try await service.fetchSensorSettings(sessionID: session.id)
      try Task.checkCancellation()
      guard ticket.isCurrent(in: loadEpoch), operation.isCurrent(in: epoch), remoteNodeService === service else { return }
      for setting in loaded {
        if drafts[setting.key] == nil
          || (drafts[setting.key] == baselines[setting.key] && draftRevisions[setting.key] == revisions[setting.key]) {
          drafts[setting.key] = setting.value
        }
      }
      settings = loaded
      hasLoaded = true
      requiresReload = false
    } catch is CancellationError {
    } catch {
      guard ticket.isCurrent(in: loadEpoch), operation.isCurrent(in: epoch), !Task.isCancelled else { return }
      errorMessage = error.userFacingMessage
    }
  }

  func save(_ key: String) {
    guard canSave(key), let setting = settings.first(where: { $0.key == key }) else { return }
    let value = self[draft: key]
    do { _ = try setting.command(setting: value) } catch {
      errorMessage = error.userFacingMessage
      return
    }
    startMutation(.save(setting, value))
  }

  func reboot() {
    startMutation(.reboot)
  }

  private enum Mutation {
    case save(SensorSetting, String)
    case reboot
  }

  private func startMutation(_ mutation: Mutation) {
    guard canSend, let service = remoteNodeService, let session else { return }
    let ticket = epoch.ticket()
    isMutating = true
    errorMessage = nil
    statusMessage = nil
    mutationTask = Task { [weak self] in
      guard let self else { return }
      defer {
        if ticket.isCurrent(in: epoch) { isMutating = false; mutationTask = nil }
      }
      do {
        switch mutation {
        case let .save(setting, value):
          let verified = try await service.setSensorSetting(sessionID: session.id, setting: setting, value: value)
          try Task.checkCancellation()
          guard ticket.isCurrent(in: epoch), remoteNodeService === service else { return }
          if let index = settings.firstIndex(where: { $0.key == verified.key }) { settings[index] = verified }
          statusMessage = L10n.RemoteNodes.RemoteNodes.Sensor.saved
        case .reboot:
          try await service.rebootSensor(sessionID: session.id)
          try Task.checkCancellation()
          guard ticket.isCurrent(in: epoch), remoteNodeService === service else { return }
          currentSession = nil
          statusMessage = L10n.RemoteNodes.RemoteNodes.Sensor.rebootSent
        }
      } catch is CancellationError {
      } catch {
        guard ticket.isCurrent(in: epoch), !Task.isCancelled else { return }
        if case RemoteNodeError.sensorOutcomeUnknown = error { requiresReload = true }
        errorMessage = error.userFacingMessage
      }
    }
  }

  func sendRawCommand(_ command: String, timeout: Duration) async throws -> String {
    guard canSend, let service = remoteNodeService, let session else { throw RemoteNodeError.notConnected }
    let ticket = epoch.ticket()
    isMutating = true
    defer { if ticket.isCurrent(in: epoch) { isMutating = false } }
    do {
      let response = try await service.sendRawCLICommand(sessionID: session.id, command: command, timeout: timeout)
      try Task.checkCancellation()
      guard ticket.isCurrent(in: epoch), remoteNodeService === service else { throw CancellationError() }
      if let noReply = RemoteCLINoReplyCommand.sensorCommand(command) {
        currentSession = nil
        statusMessage = noReply == .reboot
          ? L10n.RemoteNodes.RemoteNodes.Sensor.rebootSent : L10n.RemoteNodes.RemoteNodes.NodeCli.powerOffSent
      }
      return response
    } catch {
      guard ticket.isCurrent(in: epoch), !Task.isCancelled else { throw CancellationError() }
      if case RemoteNodeError.timeout = error { requiresReload = true }
      if case RemoteNodeError.sessionError = error { requiresReload = true }
      throw error
    }
  }

  private func cancelOwnedWork() {
    epoch.bump()
    loadEpoch.bump()
    mutationTask?.cancel()
    mutationTask = nil
    observationTask?.cancel()
    observationTask = nil
    if isMutating { requiresReload = true }
    isMutating = false
    isLoading = false
    hasLoaded = false
    currentSession = nil
  }

  func reset() {
    cancelOwnedWork()
    settings = []
    drafts = [:]
    draftRevisions = [:]
    session = nil
    boundServiceID = nil
    requiresReload = false
    statusMessage = nil
    errorMessage = nil
  }
}
