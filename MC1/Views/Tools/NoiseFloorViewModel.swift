import SwiftUI
import MeshCore
import MC1Services

struct NoiseFloorReading: Identifiable {
    let id: UUID
    let timestamp: Date
    let noiseFloor: Int16
    let lastRSSI: Int8
    let lastSNR: Double
}

struct NoiseFloorStatistics {
    let min: Int16
    let max: Int16
    let average: Double
}

enum NoiseFloorQuality: Equatable {
    case excellent
    case good
    case fair
    case poor
    case unknown

    static func from(noiseFloor: Int16) -> NoiseFloorQuality {
        switch noiseFloor {
        case ...(-100): return .excellent
        case ...(-90): return .good
        case ...(-80): return .fair
        default: return .poor
        }
    }

    var label: String {
        switch self {
        case .excellent: L10n.Tools.Tools.NoiseFloor.Quality.excellent
        case .good: L10n.Tools.Tools.NoiseFloor.Quality.good
        case .fair: L10n.Tools.Tools.NoiseFloor.Quality.fair
        case .poor: L10n.Tools.Tools.NoiseFloor.Quality.poor
        case .unknown: L10n.Tools.Tools.NoiseFloor.Quality.unknown
        }
    }

    var color: Color {
        switch self {
        case .excellent: .green
        case .good: .blue
        case .fair: .orange
        case .poor: .red
        case .unknown: .secondary
        }
    }

    var icon: String {
        switch self {
        case .excellent: "checkmark.circle.fill"
        case .good: "circle.fill"
        case .fair: "exclamationmark.circle.fill"
        case .poor: "xmark.circle.fill"
        case .unknown: "questionmark.circle"
        }
    }
}

@Observable
@MainActor
final class NoiseFloorViewModel {
    var currentReading: NoiseFloorReading?
    var readings: [NoiseFloorReading] = []
    var isPolling = false
    var errorMessage: String?

    private let maxReadings = 200
    private let pollingInterval: Duration = .seconds(1.5)

    // Re-evaluated each poll tick so a disconnect mid-poll surfaces immediately.
    private var sessionProvider: @MainActor () -> MeshCoreSession? = { nil }
    private var pollingTask: Task<Void, Never>?
    // Ignored so the statistics getter can fill the cache during view body
    // evaluation without mutating observed state; readings drives invalidation.
    @ObservationIgnored private var cachedStatistics: NoiseFloorStatistics?

    var statistics: NoiseFloorStatistics? {
        if let cached = cachedStatistics { return cached }
        guard !readings.isEmpty else { return nil }
        let values = readings.map { $0.noiseFloor }
        let computed = NoiseFloorStatistics(
            min: values.min()!,
            max: values.max()!,
            average: Double(values.reduce(0) { $0 + Int($1) }) / Double(values.count)
        )
        cachedStatistics = computed
        return computed
    }

    var qualityLevel: NoiseFloorQuality {
        guard let reading = currentReading else { return .unknown }
        return NoiseFloorQuality.from(noiseFloor: reading.noiseFloor)
    }

    func appendReading(_ reading: NoiseFloorReading) {
        currentReading = reading
        readings.append(reading)
        if readings.count > maxReadings {
            readings.removeFirst()
        }
        cachedStatistics = nil
        errorMessage = nil
    }

    func startPolling(sessionProvider: @escaping @MainActor () -> MeshCoreSession?) {
        self.sessionProvider = sessionProvider
        guard pollingTask == nil else { return }
        isPolling = true

        pollingTask = Task { [weak self] in
            while true {
                do {
                    guard let self else { break }
                    await self.fetchReading()
                    try await Task.sleep(for: self.pollingInterval)
                } catch {
                    break
                }
            }
        }
    }

    func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
        isPolling = false
    }

    private func fetchReading() async {
        guard let session = sessionProvider() else {
            errorMessage = L10n.Tools.Tools.NoiseFloor.Error.disconnected
            return
        }

        do {
            let stats = try await session.getStatsRadio()
            let reading = NoiseFloorReading(
                id: UUID(),
                timestamp: .now,
                noiseFloor: stats.noiseFloor,
                lastRSSI: stats.lastRSSI,
                lastSNR: stats.lastSNR
            )
            appendReading(reading)
        } catch {
            self.errorMessage = L10n.Tools.Tools.NoiseFloor.Error.unableToRead
        }
    }
}
