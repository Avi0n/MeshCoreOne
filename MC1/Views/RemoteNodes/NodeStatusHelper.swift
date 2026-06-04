import OSLog
import MC1Services
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "NodeStatusHelper")

/// Shared logic for repeater and room status view models.
/// Owns retry machinery, display formatters, delta properties, OCV settings,
/// telemetry handling, and snapshot persistence.
@Observable
@MainActor
final class NodeStatusHelper {

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

    /// Error message if any
    var errorMessage: String?

    /// Error text owned by the status counters section. Kept separate from
    /// `errorMessage` so a status failure surfaces only under the status section
    /// once sections load independently.
    var statusSectionError: String?

    /// Error text owned by the telemetry section. Kept separate from
    /// `errorMessage` so a telemetry failure surfaces only under the telemetry
    /// section once sections load independently.
    var telemetrySectionError: String?

    // MARK: - OCV Curve Properties

    var isBatteryCurveExpanded = false
    var selectedOCVPreset: OCVPreset = .liIon
    var ocvValues: [Int] = OCVPreset.liIon.ocvArray
    var ocvError: String?
    private var contactID: UUID?

    // MARK: - Dependencies

    private var contactService: ContactService?
    private(set) var nodeSnapshotService: NodeSnapshotService?

    // MARK: - Snapshot State

    /// ID of the snapshot to enrich with telemetry and neighbor data.
    /// A status apply sets it from the status snapshot; a telemetry apply with
    /// no status snapshot yet sets it from a telemetry-only snapshot it persists.
    private var currentSnapshotID: UUID?

    /// Previous snapshot for delta display
    private(set) var previousSnapshot: NodeStatusSnapshotDTO?

    // MARK: - Initialization

    func configure(contactService: ContactService?, nodeSnapshotService: NodeSnapshotService?) {
        self.contactService = contactService
        self.nodeSnapshotService = nodeSnapshotService
    }

    /// Configure for direct telemetry access (no login session).
    /// Used for chat nodes that can be queried without authentication.
    func configureForDirectTelemetry(publicKey: Data) {
        self.directPublicKey = publicKey
    }

    // MARK: - Transient Retry Machinery

    private static let requestTimeout: Duration = RemoteOperationTimeoutPolicy.binaryMaximum

    private static let transientRetryDelays: [Duration] = [
        .milliseconds(500),
        .seconds(1),
        .seconds(2),
    ]

    func isTransientError(_ error: Error) -> Bool {
        guard let remoteError = error as? RemoteNodeError,
              case .sessionError(let meshError) = remoteError,
              case .deviceError(let code) = meshError else {
            return false
        }
        return code == 10
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
        self.status = response
        self.statusLoaded = true
        self.isLoadingStatus = false
        self.statusSectionError = nil

        guard let nodeSnapshotService, let session else { return }

        let prev = await nodeSnapshotService.previousSnapshot(
            for: session.publicKey,
            before: .now
        )
        self.previousSnapshot = prev

        let snapshotID = await nodeSnapshotService.saveStatusSnapshot(
            nodePublicKey: session.publicKey,
            batteryMillivolts: response.batteryMillivolts,
            lastSNR: response.lastSNR,
            lastRSSI: Int16(clamping: response.lastRSSI),
            noiseFloor: Int16(clamping: response.noiseFloor),
            uptimeSeconds: response.uptimeSeconds,
            rxAirtimeSeconds: rxAirtimeSeconds,
            packetsSent: response.packetsSent,
            packetsReceived: response.packetsReceived,
            receiveErrors: receiveErrors,
            postedCount: postedCount,
            postPushCount: postPushCount
        )
        if let snapshotID {
            self.currentSnapshotID = snapshotID
        } else if let prevID = prev?.id {
            self.currentSnapshotID = prevID
        }
    }

    /// Flush buffered neighbor enrichment data. Called by repeater VM after
    /// status response sets `currentSnapshotID`.
    func flushPendingNeighborEntries(_ entries: [NeighborSnapshotEntry]) {
        guard let snapshotID = currentSnapshotID else { return }
        Task { await nodeSnapshotService?.enrichWithNeighbors(entries, snapshotID: snapshotID) }
    }

    /// Enrich the current snapshot with neighbor data, or return `false` if
    /// the snapshot ID isn't ready yet (caller should buffer).
    func enrichWithNeighbors(_ entries: [NeighborSnapshotEntry]) -> Bool {
        guard let snapshotID = currentSnapshotID else { return false }
        Task { await nodeSnapshotService?.enrichWithNeighbors(entries, snapshotID: snapshotID) }
        return true
    }

    // MARK: - Telemetry Response Handling

    func handleTelemetryResponse(_ response: TelemetryResponse) {
        guard let expectedPrefix = effectivePublicKeyPrefix,
              response.publicKeyPrefix == expectedPrefix else {
            return
        }
        self.telemetry = response
        self.cachedDataPoints = response.dataPoints.filter { $0.channel != 0 }
        self.isLoadingTelemetry = false
        self.telemetryLoaded = true
        self.telemetrySectionError = nil

        let entries: [TelemetrySnapshotEntry] = cachedDataPoints.compactMap { dp in
            let numericValue: Double?
            switch dp.value {
            case .float(let value):
                numericValue = value
            case .integer(let value):
                numericValue = Double(value)
            default:
                numericValue = nil
            }
            guard let value = numericValue else { return nil }
            return TelemetrySnapshotEntry(channel: Int(dp.channel), type: dp.typeName, value: value)
        }
        guard !entries.isEmpty else { return }

        if let snapshotID = currentSnapshotID {
            Task { await nodeSnapshotService?.enrichWithTelemetry(entries, snapshotID: snapshotID) }
        } else if let nodeSnapshotService, let nodePublicKey = effectivePublicKey {
            // No status snapshot exists yet (telemetry expanded without status).
            // Persist a telemetry-only snapshot so the displayed data survives,
            // and retarget enrichment at it. A status response can set
            // `currentSnapshotID` during the save suspension, so adopt the
            // telemetry-only id only when the field is still nil on resume —
            // a status snapshot is the preferred enrichment target.
            Task {
                let savedID = await nodeSnapshotService.saveTelemetryOnlySnapshot(
                    nodePublicKey: nodePublicKey,
                    telemetryEntries: entries
                )
                if let savedID, self.currentSnapshotID == nil {
                    self.currentSnapshotID = savedID
                }
            }
        }
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
    private static let secondsPerHour: UInt32 = 3_600
    private static let secondsPerDay: UInt32 = 86_400

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
        guard let prev = previousSnapshot else { return nil }
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
              let previous = previousSnapshot?.batteryMillivolts else { return nil }
        return Int(current) - Int(previous)
    }

    var snrDelta: Double? {
        guard let current = status?.lastSNR,
              let previous = previousSnapshot?.lastSNR else { return nil }
        return current - previous
    }

    var rssiDelta: Int? {
        guard let current = status?.lastRSSI,
              let previous = previousSnapshot?.lastRSSI else { return nil }
        return Int(current) - Int(previous)
    }

    var noiseFloorDelta: Int? {
        guard let current = status?.noiseFloor,
              let previous = previousSnapshot?.noiseFloor else { return nil }
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
            ocvError = L10n.RemoteNodes.RemoteNodes.Status.ocvSaveFailed(error.localizedDescription)
        }
    }
}
