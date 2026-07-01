import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "NodeStatusViewModel")

/// Shared logic for repeater and room status view models.
/// Owns retry machinery, display formatters, delta properties, OCV settings,
/// telemetry handling, and snapshot persistence.
@Observable
@MainActor
final class NodeStatusViewModel {
  // MARK: - Properties

  /// Current session
  var session: RemoteNodeSessionDTO?

  /// Public key for direct telemetry (no remote session).
  /// Used for chat nodes that don't require login.
  private var directPublicKey: Data?

  /// The public key to use for requests and history — prefers session, falls back to direct.
  var effectivePublicKey: Data? {
    session?.publicKey ?? directPublicKey
  }

  /// 6-byte prefix for response matching.
  var effectivePublicKeyPrefix: Data? {
    session?.publicKeyPrefix ?? directPublicKey?.prefix(6)
  }

  /// Last received status
  var status: RemoteNodeStatus?

  /// Last received telemetry
  var telemetry: TelemetryResponse?

  /// Cached decoded data points to avoid repeated LPP decoding.
  private(set) var cachedDataPoints: [LPPDataPoint] = []

  /// Loading states
  var isLoadingStatus = false
  var isLoadingTelemetry = false

  /// Whether a status response has been applied since the sheet opened.
  /// Drives lazy loading of the status counters section.
  var statusLoaded = false

  /// Whether the status disclosure group is expanded
  var statusExpanded = false

  /// Whether telemetry has been loaded at least once (for refresh logic)
  var telemetryLoaded = false

  /// Whether the telemetry disclosure group is expanded
  var telemetryExpanded = false

  /// Error text owned by the status counters section, scoped so a status
  /// failure surfaces only under the status section once sections load independently.
  var statusSectionError: String?

  /// Error text owned by the telemetry section, scoped so a telemetry
  /// failure surfaces only under the telemetry section once sections load independently.
  var telemetrySectionError: String?

  // MARK: - OCV Curve Properties

  var isBatteryCurveExpanded = false
  var selectedOCVPreset: OCVPreset = .liIon
  var ocvValues: [Int] = OCVPreset.liIon.ocvArray
  var ocvError: String?
  private var contactID: UUID?

  // MARK: - Dependencies

  private var contactServiceProvider: @MainActor () -> ContactService? = { nil }
  var contactService: ContactService? {
    contactServiceProvider()
  }

  private var nodeSnapshotServiceProvider: @MainActor () -> NodeSnapshotService? = { nil }
  var nodeSnapshotService: NodeSnapshotService? {
    nodeSnapshotServiceProvider()
  }

  // MARK: - Snapshot State

  /// Previous status-bearing snapshot for the status deltas, sourced from the last
  /// snapshot that actually captured status so a neighbor- or telemetry-only row
  /// can't blank the delta.
  private(set) var previousStatusSnapshot: NodeStatusSnapshotDTO?

  /// Previous neighbor-bearing snapshot for the neighbor SNR delta. Owned by the
  /// neighbor load path so the delta does not depend on the status section being
  /// expanded, and sourced from the last snapshot that actually captured neighbors.
  private(set) var previousNeighborSnapshot: NodeStatusSnapshotDTO?

  // MARK: - Initialization

  func configure(
    contactService: @escaping @MainActor () -> ContactService?,
    nodeSnapshotService: @escaping @MainActor () -> NodeSnapshotService?
  ) {
    contactServiceProvider = contactService
    nodeSnapshotServiceProvider = nodeSnapshotService
  }

  /// Configure for direct telemetry access (no login session).
  /// Used for chat nodes that can be queried without authentication.
  func configureForDirectTelemetry(publicKey: Data) {
    directPublicKey = publicKey
  }

  // MARK: - Transient Retry Machinery

  private static let requestTimeout: Duration = RemoteOperationTimeoutPolicy.binaryMaximum

  private static let transientRetryDelays: [Duration] = [
    .milliseconds(500),
    .seconds(1),
    .seconds(2),
  ]

  func isTransientError(_ error: Error) -> Bool {
    let meshError: MeshCoreError
    switch error {
    case let remoteError as RemoteNodeError:
      guard case let .sessionError(inner) = remoteError else { return false }
      meshError = inner
    case let binaryError as BinaryProtocolError:
      guard case let .sessionError(inner) = binaryError else { return false }
      meshError = inner
    default:
      return false
    }
    guard case let .deviceError(code) = meshError else { return false }
    return code == FirmwareDeviceErrorCode.remoteNodeNoResponseYet
  }

  private func remainingBudget(until deadline: ContinuousClock.Instant) -> Duration? {
    let remaining = deadline - .now
    return remaining > .zero ? remaining : nil
  }

  private func waitForRetry(delay: Duration, until deadline: ContinuousClock.Instant) async throws {
    guard let remaining = remainingBudget(until: deadline) else {
      throw RemoteNodeError.timeout
    }
    try await Task.sleep(for: min(delay, remaining))
  }

  func performWithTransientRetries<T>(
    operationName: String,
    operation: @escaping @Sendable (Duration) async throws -> T
  ) async throws -> T {
    let deadline = ContinuousClock.now.advanced(by: Self.requestTimeout)
    var delayIterator = Self.transientRetryDelays.makeIterator()

    while true {
      guard let timeout = remainingBudget(until: deadline) else {
        logger.warning("\(operationName, privacy: .public) request exhausted its shared timeout budget")
        throw RemoteNodeError.timeout
      }

      do {
        return try await operation(timeout)
      } catch {
        guard isTransientError(error), let delay = delayIterator.next() else {
          throw error
        }
        try await waitForRetry(delay: delay, until: deadline)
      }
    }
  }

  /// Drive a section request through the shared retry budget, owning the
  /// loading flag and section-error scaffold that the admin status view models
  /// otherwise repeat verbatim. The `setLoading`/`setError` closures target the
  /// section's own state (some live on this helper, some on the view model);
  /// `onSuccess` applies the response. A `RemoteNodeError.timeout` surfaces the
  /// shared timed-out string, any other error its user-facing message.
  func runRetryingSectionRequest<T>(
    operationName: String,
    setLoading: @MainActor (Bool) -> Void,
    setError: @MainActor (String?) -> Void,
    operation: @escaping @Sendable (Duration) async throws -> T,
    onSuccess: @MainActor (T) async -> Void
  ) async {
    setLoading(true)
    setError(nil)
    do {
      let response = try await performWithTransientRetries(operationName: operationName, operation: operation)
      await onSuccess(response)
    } catch RemoteNodeError.timeout {
      setError(L10n.RemoteNodes.RemoteNodes.Status.requestTimedOut)
    } catch {
      setError(error.userFacingMessage)
    }
    setLoading(false)
  }

  // MARK: - Status Response Handling

  /// Handle a status response, saving a snapshot with role-specific fields.
  /// `rxAirtimeSeconds` and `receiveErrors` are present in all wire frames
  /// but rooms pass `nil` to skip persistence of repeater-specific metrics.
  func handleStatusResponse(
    _ response: RemoteNodeStatus,
    rxAirtimeSeconds: UInt32? = nil,
    receiveErrors: UInt32? = nil,
    postedCount: UInt16? = nil,
    postPushCount: UInt16? = nil
  ) async {
    guard let expectedPrefix = session?.publicKeyPrefix,
          response.publicKeyPrefix == expectedPrefix else {
      return
    }
    status = response
    statusLoaded = true
    isLoadingStatus = false
    statusSectionError = nil

    guard let nodeSnapshotService, let session else { return }

    previousStatusSnapshot = await nodeSnapshotService.previousStatusSnapshot(
      for: session.publicKey,
      before: .now
    )

    let metrics = NodeStatusMetrics(
      status: response,
      rxAirtimeSeconds: rxAirtimeSeconds,
      receiveErrors: receiveErrors,
      postedCount: postedCount,
      postPushCount: postPushCount
    )
    _ = await nodeSnapshotService.recordSnapshot(
      nodePublicKey: session.publicKey,
      status: metrics
    )
  }

  /// Capture neighbor data onto the node's current in-window snapshot, creating
  /// one if none exists yet. Safe to call before a status response: the store
  /// enriches the latest in-window row or inserts a neighbor-bearing snapshot.
  func enrichNeighbors(_ entries: [NeighborSnapshotEntry]) async {
    guard let nodeSnapshotService, let nodePublicKey = effectivePublicKey else { return }
    // Capture the prior neighbor-bearing snapshot before persisting this reading,
    // so the delta baseline is the previous distinct capture, not this one.
    previousNeighborSnapshot = await nodeSnapshotService.previousNeighborSnapshot(for: nodePublicKey)
    _ = await nodeSnapshotService.recordSnapshot(
      nodePublicKey: nodePublicKey,
      neighbors: entries
    )
  }

  // MARK: - Telemetry Response Handling

  func handleTelemetryResponse(_ response: TelemetryResponse) async {
    guard let expectedPrefix = effectivePublicKeyPrefix,
          response.publicKeyPrefix == expectedPrefix else {
      return
    }
    telemetry = response
    cachedDataPoints = response.dataPoints.filter { $0.channel != 0 }
    isLoadingTelemetry = false
    telemetryLoaded = true
    telemetrySectionError = nil

    let entries: [TelemetrySnapshotEntry] = cachedDataPoints.compactMap { dp in
      let numericValue: Double? = switch dp.value {
      case let .float(value):
        value
      case let .integer(value):
        Double(value)
      default:
        nil
      }
      guard let value = numericValue else { return nil }
      return TelemetrySnapshotEntry(channel: Int(dp.channel), type: dp.typeName, value: value)
    }
    guard !entries.isEmpty,
          let nodeSnapshotService,
          let nodePublicKey = effectivePublicKey else { return }

    _ = await nodeSnapshotService.recordSnapshot(
      nodePublicKey: nodePublicKey,
      telemetry: entries
    )
  }

  // MARK: - Telemetry Grouping

  var hasMultipleChannels: Bool {
    let channels = Set(cachedDataPoints.map(\.channel))
    return channels.count > 1
  }

  var groupedDataPoints: [(channel: UInt8, dataPoints: [LPPDataPoint])] {
    Dictionary(grouping: cachedDataPoints, by: \.channel)
      .sorted { $0.key < $1.key }
      .map { (channel: $0.key, dataPoints: $0.value) }
  }

  // MARK: - Display Formatters

  static let emDash = "—"
  private static let secondsPerMinute: UInt32 = 60
  private static let secondsPerHour: UInt32 = 3600
  private static let secondsPerDay: UInt32 = 86400

  var uptimeDisplay: String {
    guard let uptime = status?.uptimeSeconds else { return Self.emDash }
    return Self.formatDuration(uptime)
  }

  var airtimeDisplay: String {
    guard let status else { return Self.emDash }
    let tx = Self.formatDuration(status.airtime)
    let rx = Self.formatDuration(status.rxAirtime)
    return "TX \(tx) / RX \(rx)"
  }

  private static let airtimePercentFractionDigits = 1

  var airtimePercentDisplay: String {
    guard let status, status.uptimeSeconds > 0 else { return Self.emDash }
    let denom = Double(status.uptimeSeconds)
    let txPercent = Double(status.airtime) / denom * 100
    let rxPercent = Double(status.rxAirtime) / denom * 100
    return "TX \(Self.formatPercent(txPercent)) / RX \(Self.formatPercent(rxPercent))"
  }

  private static func formatPercent(_ value: Double) -> String {
    value.formatted(.number.precision(.fractionLength(airtimePercentFractionDigits))) + "%"
  }

  private static func formatDuration(_ seconds: UInt32) -> String {
    let days = Int(seconds / secondsPerDay)
    let hours = Int((seconds % secondsPerDay) / secondsPerHour)
    let minutes = Int((seconds % secondsPerHour) / secondsPerMinute)

    if days > 0 {
      if days == 1 {
        return L10n.RemoteNodes.RemoteNodes.Status.uptime1Day(hours, minutes)
      } else {
        return L10n.RemoteNodes.RemoteNodes.Status.uptimeDays(days, hours, minutes)
      }
    } else if hours > 0 {
      return L10n.RemoteNodes.RemoteNodes.Status.uptimeHours(hours, minutes)
    }
    return L10n.RemoteNodes.RemoteNodes.Status.uptimeMinutes(minutes)
  }

  var batteryDisplay: String {
    guard let mv = status?.batteryMillivolts else { return Self.emDash }
    let volts = Double(mv) / 1000.0
    let battery = BatteryInfo(level: Int(mv))
    let percent = battery.percentage(using: ocvValues)
    return "\(volts.formatted(.number.precision(.fractionLength(3))))V (\(percent)%)"
  }

  var lastRSSIDisplay: String {
    guard let rssi = status?.lastRSSI else { return Self.emDash }
    return "\(rssi) dBm"
  }

  var lastSNRDisplay: String {
    guard let snr = status?.lastSNR else { return Self.emDash }
    return "\(snr.formatted(.number.precision(.fractionLength(1)))) dB"
  }

  var noiseFloorDisplay: String {
    guard let nf = status?.noiseFloor else { return Self.emDash }
    return "\(nf) dBm"
  }

  var packetsSentDisplay: String {
    guard let count = status?.packetsSent else { return Self.emDash }
    return count.formatted()
  }

  var packetsReceivedDisplay: String {
    guard let count = status?.packetsReceived else { return Self.emDash }
    return count.formatted()
  }

  // MARK: - Delta Display

  var previousSnapshotTimestamp: String? {
    guard let prev = previousStatusSnapshot else { return nil }
    let interval = prev.timestamp.distance(to: .now)
    let secondsPerHour = TimeInterval(Self.secondsPerHour)
    let secondsPerDay = TimeInterval(Self.secondsPerDay)
    if interval < secondsPerHour {
      return L10n.RemoteNodes.RemoteNodes.History.vsMinutesAgo(Int(interval / 60))
    } else if interval < secondsPerDay {
      return L10n.RemoteNodes.RemoteNodes.History.vsHoursAgo(Int(interval / secondsPerHour))
    } else {
      return L10n.RemoteNodes.RemoteNodes.History.vsDate(prev.timestamp.formatted(.dateTime.month().day()))
    }
  }

  var batteryDeltaMV: Int? {
    guard let current = status?.batteryMillivolts,
          let previous = previousStatusSnapshot?.batteryMillivolts else { return nil }
    return Int(current) - Int(previous)
  }

  var snrDelta: Double? {
    guard let current = status?.lastSNR,
          let previous = previousStatusSnapshot?.lastSNR else { return nil }
    return current - previous
  }

  var rssiDelta: Int? {
    guard let current = status?.lastRSSI,
          let previous = previousStatusSnapshot?.lastRSSI else { return nil }
    return Int(current) - Int(previous)
  }

  var noiseFloorDelta: Int? {
    guard let current = status?.noiseFloor,
          let previous = previousStatusSnapshot?.noiseFloor else { return nil }
    return Int(current) - Int(previous)
  }

  // MARK: - History

  func fetchHistory() async -> [NodeStatusSnapshotDTO] {
    guard let nodeSnapshotService, let publicKey = effectivePublicKey else {
      logger.warning("fetchHistory: nodeSnapshotService or public key is nil")
      return []
    }
    return await nodeSnapshotService.fetchSnapshots(for: publicKey)
  }

  // MARK: - OCV Settings

  /// Load OCV settings for a contact by public key. Skips reload if already loaded.
  func loadOCVSettings(publicKey: Data, radioID: UUID) async {
    guard contactID == nil else { return }
    guard let contactService else { return }

    do {
      if let contact = try await contactService.getContact(radioID: radioID, publicKey: publicKey) {
        contactID = contact.id

        if let presetName = contact.ocvPreset {
          if presetName == OCVPreset.custom.rawValue, let customString = contact.customOCVArrayString {
            let parsed = customString.split(separator: ",")
              .compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            if parsed.count == 11 {
              ocvValues = parsed
              selectedOCVPreset = .custom
              return
            }
          }
          if let preset = OCVPreset(rawValue: presetName) {
            selectedOCVPreset = preset
            ocvValues = preset.ocvArray
            return
          }
        }

        selectedOCVPreset = .liIon
        ocvValues = OCVPreset.liIon.ocvArray
      }
    } catch {
      ocvError = L10n.RemoteNodes.RemoteNodes.Status.ocvLoadFailed
    }
  }

  func saveOCVSettings(preset: OCVPreset, values: [Int]) async {
    guard let contactService,
          let contactID else {
      ocvError = L10n.RemoteNodes.RemoteNodes.Status.ocvSaveNoContact
      return
    }

    ocvError = nil

    do {
      if preset == .custom {
        let customString = values.map(String.init).joined(separator: ",")
        try await contactService.updateContactOCVSettings(
          contactID: contactID,
          preset: OCVPreset.custom.rawValue,
          customArray: customString
        )
      } else {
        try await contactService.updateContactOCVSettings(
          contactID: contactID,
          preset: preset.rawValue,
          customArray: nil
        )
      }

      selectedOCVPreset = preset
      ocvValues = values
    } catch {
      ocvError = L10n.RemoteNodes.RemoteNodes.Status.ocvSaveFailed(error.userFacingMessage)
    }
  }
}
