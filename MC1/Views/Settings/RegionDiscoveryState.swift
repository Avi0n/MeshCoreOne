import MC1Services
import SwiftUI

/// Navigation controls discovery activity so row removal does not cancel the run.
/// Only the active run may publish results.
@Observable
@MainActor
final class RegionDiscoveryState {
  private(set) var isDiscovering = false
  private(set) var discoveryMessage: String?

  /// Replaced in tests so a run can suspend without radio traffic.
  @ObservationIgnored var discover: (@MainActor () async -> RegionDiscoveryService.Outcome)?

  @ObservationIgnored private var sessionProvider: @MainActor () -> MeshCoreSession? = { nil }
  private var session: MeshCoreSession? {
    sessionProvider()
  }

  @ObservationIgnored private var contactServiceProvider: @MainActor () -> ContactService? = { nil }
  private var contactService: ContactService? {
    contactServiceProvider()
  }

  @ObservationIgnored private var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  private var connectedDevice: DeviceDTO? {
    connectedDeviceProvider()
  }

  @ObservationIgnored private var connectionStateProvider: @MainActor () -> DeviceConnectionState = { .disconnected }
  private var connectionState: DeviceConnectionState {
    connectionStateProvider()
  }

  @ObservationIgnored private var connectionManager: ConnectionManager?
  @ObservationIgnored private var dataStore: PersistenceStore?
  @ObservationIgnored private var task: Task<Void, Never>?
  @ObservationIgnored private var activeRun: Run?
  @ObservationIgnored private var workflowActive = false
  @ObservationIgnored private var admittedRadioID: UUID?
  @ObservationIgnored private var admittedSession: ObjectIdentifier?
  private var regionsRevision = 0

  var knownRegions: [String] {
    _ = regionsRevision
    return connectedDevice?.knownRegions ?? []
  }

  isolated deinit {
    task?.cancel()
  }

  func configure(
    session: @escaping @MainActor () -> MeshCoreSession?,
    contactService: @escaping @MainActor () -> ContactService?,
    connectedDevice: @escaping @MainActor () -> DeviceDTO?,
    connectionState: @escaping @MainActor () -> DeviceConnectionState,
    connectionManager: ConnectionManager,
    dataStore: PersistenceStore?
  ) {
    sessionProvider = session
    contactServiceProvider = contactService
    connectedDeviceProvider = connectedDevice
    connectionStateProvider = connectionState
    self.connectionManager = connectionManager
    self.dataStore = dataStore
  }

  /// `true` while Settings is showing Advanced or Manage Regions.
  /// Becoming inactive cancels the run and keeps the last message. Becoming active does not start a run.
  func setWorkflowActive(_ isActive: Bool) {
    guard workflowActive != isActive else { return }
    workflowActive = isActive
    if isActive {
      captureAdmission()
    } else {
      invalidateRun()
    }
  }

  /// A different radio or session cancels the current run.
  func noteConnectionChanged() {
    let radioID = connectedDevice?.radioID
    let sessionIdentity = session.map(ObjectIdentifier.init)
    if let activeRun, activeRun.radioID != radioID || activeRun.sessionIdentity != sessionIdentity {
      invalidateRun()
    }
    if workflowActive {
      admittedRadioID = radioID
      admittedSession = sessionIdentity
    }
  }

  func noteRegionsChanged() {
    regionsRevision += 1
  }

  func start() {
    guard workflowActive, let session, let device = connectedDevice else { return }
    let operation: @MainActor () async -> RegionDiscoveryService.Outcome
    if let discover {
      operation = discover
    } else {
      guard let contactService else { return }
      let dataStore = dataStore
      operation = {
        await RegionDiscoveryService.discover(
          session: session,
          contactService: contactService,
          dataStore: dataStore,
          radioID: device.radioID,
          knownRegions: device.knownRegions,
          supportsAdHocRequest: device.supportsAdHocRepeaterRequest
        )
      }
    }
    let run = Run(
      token: UUID(),
      radioID: device.radioID,
      sessionIdentity: ObjectIdentifier(session)
    )
    task?.cancel()
    activeRun = run
    isDiscovering = true
    discoveryMessage = nil
    task = Task { @MainActor [weak self] in
      let outcome = await operation()
      guard let self, !Task.isCancelled else { return }
      self.finish(outcome, run: run)
    }
  }

  func addKnownRegion(_ region: String) {
    guard admitsCallback, let connectionManager else { return }
    connectionManager.addKnownRegion(region)
    noteRegionsChanged()
  }

  func removeKnownRegion(_ region: String) {
    guard admitsCallback, let connectionManager else { return }
    connectionManager.removeKnownRegion(region)
    noteRegionsChanged()
  }

  private struct Run: Equatable {
    let token: UUID
    let radioID: UUID
    let sessionIdentity: ObjectIdentifier
  }

  private func captureAdmission() {
    admittedRadioID = connectedDevice?.radioID
    admittedSession = session.map(ObjectIdentifier.init)
  }

  private var admitsCallback: Bool {
    guard workflowActive, connectionState.isConnected,
          let device = connectedDevice, let session else { return false }
    return device.radioID == admittedRadioID && ObjectIdentifier(session) == admittedSession
  }

  private func invalidateRun() {
    activeRun = nil
    task?.cancel()
    task = nil
    isDiscovering = false
  }

  private func finish(_ outcome: RegionDiscoveryService.Outcome, run: Run) {
    guard activeRun == run else { return }
    guard workflowActive,
          connectedDevice?.radioID == run.radioID,
          session.map(ObjectIdentifier.init) == run.sessionIdentity else {
      if activeRun == run { isDiscovering = false }
      return
    }
    switch outcome {
    case .sendFailed:
      break
    case .noRepeatersResponded:
      discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.noRepeatersResponded
    case .errorLoadingRepeaters:
      discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.errLoadingRepeaters
    case let .completed(newRegions, allRepeatersTableFull):
      if newRegions.isEmpty, allRepeatersTableFull {
        discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.errRadioContactsFull
      } else if newRegions.isEmpty {
        discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.noNewRegions
      } else {
        publishDiscoveredRegions(newRegions, run: run)
      }
    }
    guard activeRun == run else { return }
    isDiscovering = false
  }

  private func publishDiscoveredRegions(_ newRegions: [String], run: Run) {
    for region in newRegions {
      guard activeRun == run else { return }
      guard let connectionManager else { break }
      connectionManager.addKnownRegion(region)
    }
    guard activeRun == run else { return }
    noteRegionsChanged()
  }
}
