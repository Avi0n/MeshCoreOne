import MC1Services
import SwiftUI

@Observable
@MainActor
final class DeviceInfoTelemetryViewModel: TelemetryDisclosurePresenting {
  struct ReadyScope: Equatable {
    let binaryProtocolService: BinaryProtocolService
    let presentationRevision: Int
    let rootNavigationGeneration: Int

    static func == (lhs: ReadyScope, rhs: ReadyScope) -> Bool {
      lhs.binaryProtocolService === rhs.binaryProtocolService
        && lhs.presentationRevision == rhs.presentationRevision
        && lhs.rootNavigationGeneration == rhs.rootNavigationGeneration
    }
  }

  private(set) var telemetry: TelemetryResponse?
  private(set) var cachedDataPoints: [LPPDataPoint] = []
  private(set) var isLoadingTelemetry = false
  private(set) var telemetryLoaded = false
  var telemetryExpanded = false
  private(set) var telemetrySectionError: String?
  private(set) var displayName: String?
  private(set) var ocvValues: [Int] = []

  var mapLabel: String? {
    guard let displayName else { return nil }
    let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
  }

  private var deviceProvider: (@MainActor () -> DeviceDTO?)?
  private var readyScopeProvider: (@MainActor () -> ReadyScope?)?
  private var nodeSnapshotServiceProvider: (@MainActor () -> NodeSnapshotService?)?

  private var device: DeviceDTO? {
    deviceProvider?()
  }

  private var readyScope: ReadyScope? {
    readyScopeProvider?()
  }

  private var nodeSnapshotService: NodeSnapshotService? {
    nodeSnapshotServiceProvider?()
  }

  private struct InflightRequest {
    let token: UUID
    let deviceID: UUID
    let radioID: UUID
    let publicKey: Data
    let scope: ReadyScope
  }

  private var requestToken: UUID?
  private var boundDeviceID: UUID?
  private var boundRadioID: UUID?
  private var boundPublicKey: Data?
  private var boundServiceIdentity: ObjectIdentifier?
  private var boundPresentationRevision: Int?
  private var boundRootNavigationGeneration: Int?

  func configure(
    device: @escaping @MainActor () -> DeviceDTO?,
    readyScope: @escaping @MainActor () -> ReadyScope?,
    nodeSnapshotService: @escaping @MainActor () -> NodeSnapshotService?
  ) {
    deviceProvider = device
    readyScopeProvider = readyScope
    nodeSnapshotServiceProvider = nodeSnapshotService
  }

  func synchronizePresentation() {
    let currentDevice = device
    let currentScope = readyScope
    let serviceIdentity = currentScope.map { ObjectIdentifier($0.binaryProtocolService) }
    displayName = currentDevice?.nodeName
    ocvValues = currentDevice?.activeOCVArray ?? []
    let sameDevice = currentDevice?.id == boundDeviceID
      && currentDevice?.radioID == boundRadioID
      && currentDevice?.publicKey == boundPublicKey
    let sameScope = serviceIdentity == boundServiceIdentity
      && currentScope?.presentationRevision == boundPresentationRevision
      && currentScope?.rootNavigationGeneration == boundRootNavigationGeneration
    guard !sameDevice || !sameScope else { return }

    requestToken = nil
    telemetry = nil
    cachedDataPoints = []
    isLoadingTelemetry = false
    telemetryLoaded = false
    telemetrySectionError = nil
    telemetryExpanded = false
    boundDeviceID = currentDevice?.id
    boundRadioID = currentDevice?.radioID
    boundPublicKey = currentDevice?.publicKey
    boundServiceIdentity = serviceIdentity
    boundPresentationRevision = currentScope?.presentationRevision
    boundRootNavigationGeneration = currentScope?.rootNavigationGeneration
  }

  func loadTelemetry() async {
    synchronizePresentation()
    guard requestToken == nil,
          let scope = readyScope,
          let current = device,
          current.publicKey.count == ProtocolLimits.publicKeySize else { return }

    let inflight = InflightRequest(
      token: UUID(),
      deviceID: current.id,
      radioID: current.radioID,
      publicKey: current.publicKey,
      scope: scope
    )
    requestToken = inflight.token
    isLoadingTelemetry = true
    telemetrySectionError = nil
    await perform(inflight)
  }

  func fetchHistory() async -> [NodeStatusSnapshotDTO] {
    guard let service = nodeSnapshotService,
          let current = device,
          current.publicKey.count == ProtocolLimits.publicKeySize else { return [] }
    return await service.fetchSelfTelemetry(
      radioID: current.radioID,
      nodePublicKey: current.publicKey
    )
  }

  private func perform(_ inflight: InflightRequest) async {
    do {
      let response = try await inflight.scope.binaryProtocolService.getSelfTelemetry()
      try Task.checkCancellation()
      await apply(response, inflight: inflight)
    } catch is CancellationError {
      finish(inflight)
    } catch {
      guard stillCurrent(inflight) else { return }
      telemetrySectionError = Self.message(for: error)
      finish(inflight)
    }
  }

  private func apply(_ response: TelemetryResponse, inflight: InflightRequest) async {
    guard stillCurrent(inflight) else { return }
    guard let reading = TelemetryReading.accepted(from: response, expectedPublicKey: inflight.publicKey) else {
      finish(inflight)
      return
    }
    telemetry = response
    cachedDataPoints = reading.dataPoints
    telemetryLoaded = true
    telemetrySectionError = nil

    let entries = reading.entries.isEmpty ? nil : reading.entries
    if entries != nil || reading.location != nil, let service = nodeSnapshotService {
      _ = await service.recordSelfTelemetry(
        deviceID: inflight.deviceID,
        radioID: inflight.radioID,
        nodePublicKey: inflight.publicKey,
        telemetry: entries,
        location: reading.location
      )
    }
    finish(inflight)
  }

  /// Recheck readiness because reconnect can retain the Device and service.
  private func stillCurrent(_ inflight: InflightRequest) -> Bool {
    guard requestToken == inflight.token,
          readyScope == inflight.scope,
          let current = device,
          current.id == inflight.deviceID,
          current.radioID == inflight.radioID,
          current.publicKey == inflight.publicKey else {
      return false
    }
    return true
  }

  private func finish(_ inflight: InflightRequest) {
    guard requestToken == inflight.token else { return }
    requestToken = nil
    isLoadingTelemetry = false
  }

  private static func message(for error: Error) -> String {
    if case BinaryProtocolError.sessionError(MeshCoreError.timeout) = error {
      return L10n.Localizable.Error.MeshCore.timeout
    }
    return error.userFacingMessage
  }
}
