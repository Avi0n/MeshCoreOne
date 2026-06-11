import SwiftUI
import MC1Services
import os.log

private let logger = Logger(subsystem: "com.mc1", category: "SavedPathDetail")

@MainActor @Observable
final class SavedPathDetailViewModel {

    // MARK: - State

    var savedPath: SavedTracePathDTO
    var isLoading = false

    // MARK: - Computed

    var sortedRuns: [TracePathRunDTO] {
        savedPath.runs.sorted { $0.date > $1.date }
    }

    var successfulRuns: [TracePathRunDTO] {
        sortedRuns.filter { $0.success }
    }

    var bestRoundTrip: Int? {
        successfulRuns.map(\.roundTripMs).min()
    }

    var averageRoundTrip: Int? {
        savedPath.averageRoundTripMs
    }

    var successRateText: String {
        "\(savedPath.successRate)%"
    }

    // MARK: - Initialization

    init(savedPath: SavedTracePathDTO) {
        self.savedPath = savedPath
    }

    // MARK: - Dependencies

    private var appState: AppState?

    /// Hash size per hop from when the path was saved (1, 2, or 3 bytes)
    var hashSize: Int {
        savedPath.hashSize
    }

    func configure(appState: AppState) {
        self.appState = appState
    }

    // MARK: - Actions

    func refresh() async {
        guard let dataStore = appState?.services?.dataStore else { return }

        isLoading = true
        do {
            if let updated = try await dataStore.fetchSavedTracePath(id: savedPath.id) {
                savedPath = updated
            }
        } catch {
            logger.error("Failed to refresh: \(error.localizedDescription)")
        }
        isLoading = false
    }
}
