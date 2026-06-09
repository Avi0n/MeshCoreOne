import SwiftUI
import MC1Services
import os.log

private let logger = Logger(subsystem: "com.mc1", category: "SavedPaths")

@MainActor @Observable
final class SavedPathsViewModel {

    // MARK: - State

    var savedPaths: [SavedTracePathDTO] = []
    var isLoading = false
    var errorMessage: String?

    // MARK: - Dependencies

    private var appState: AppState?

    // MARK: - Configuration

    func configure(appState: AppState) {
        self.appState = appState
    }

    // MARK: - Data Loading

    func loadSavedPaths() async {
        guard let appState,
              let radioID = appState.connectedDevice?.radioID,
              let dataStore = appState.services?.dataStore else { return }

        isLoading = true
        errorMessage = nil

        do {
            savedPaths = try await dataStore.fetchSavedTracePaths(radioID: radioID)
            logger.info("Loaded \(self.savedPaths.count) saved paths")
        } catch {
            logger.error("Failed to load saved paths: \(error.localizedDescription)")
            errorMessage = "Failed to load saved paths"
        }

        isLoading = false
    }

    // MARK: - Actions

    func renamePath(_ path: SavedTracePathDTO, to newName: String) async {
        guard let dataStore = appState?.services?.dataStore else { return }

        do {
            try await dataStore.updateSavedTracePathName(id: path.id, name: newName)
            await loadSavedPaths()
        } catch {
            logger.error("Failed to rename path: \(error.localizedDescription)")
            errorMessage = "Failed to rename path"
        }
    }

    func deletePath(_ path: SavedTracePathDTO) async {
        guard let dataStore = appState?.services?.dataStore else { return }

        do {
            try await dataStore.deleteSavedTracePath(id: path.id)
            savedPaths.removeAll { $0.id == path.id }
            logger.info("Deleted saved path: \(path.name)")
        } catch {
            logger.error("Failed to delete path: \(error.localizedDescription)")
            errorMessage = "Failed to delete path"
        }
    }
}
