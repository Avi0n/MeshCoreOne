import SwiftUI
import MC1Services

@Observable
@MainActor
final class DangerZoneViewModel {
    var showingForgetConfirmation = false
    var showingResetAlert = false
    var isResetting = false
    var errorMessage: String?
    var showingRemoveUnfavoritedAlert = false
    var isRemovingUnfavorited = false
    var showRemoveSuccess = false
    var unfavoritedCount = 0
    var showRemoveResult = false
    var removeResult: String?

    private var removeTask: Task<Void, Never>?

    /// Grace period for the radio to reboot after a factory reset before local cleanup.
    private static let resetRebootGracePeriod: Duration = .seconds(1)
    /// How long the transient "Removed" confirmation stays on the button label.
    private static let removeSuccessDisplayDuration: Duration = .seconds(1.5)

    func cancelPendingRemoval() {
        removeTask?.cancel()
    }

    /// Returns true when the device was forgotten and the hosting page should dismiss.
    func forgetDevice(connectionManager: ConnectionManager, deleteData: Bool) async -> Bool {
        do {
            try await connectionManager.forgetDevice(deleteData: deleteData)
            return true
        } catch {
            errorMessage = error.userFacingMessage
            return false
        }
    }

    /// Returns true when the reset flow finished and the hosting page should dismiss.
    /// A nil service or device ID mirrors a disconnected state.
    func factoryReset(settingsService: SettingsService?, deviceID: UUID?, connectionManager: ConnectionManager) async -> Bool {
        guard let settingsService,
              let deviceID else {
            errorMessage = L10n.Settings.DangerZone.Error.servicesUnavailable
            return false
        }

        isResetting = true
        defer { isResetting = false }

        // Send reset command. The device typically reboots before responding,
        // so a timeout/connection error here is expected, not a failure.
        do {
            try await settingsService.factoryReset()
            try await Task.sleep(for: Self.resetRebootGracePeriod)
        } catch {
            // Expected: device reboots before sending OK response
        }

        // Always clean up: remove from ASK, disconnect, delete from SwiftData
        await connectionManager.forgetDevice(id: deviceID)
        return true
    }

    func fetchUnfavoritedCount(connectionManager: ConnectionManager) async {
        do {
            unfavoritedCount = try await connectionManager.unfavoritedNodeCount()
            if unfavoritedCount == 0 {
                removeResult = L10n.Settings.DangerZone.Alert.RemoveUnfavorited.noneFound
                showRemoveResult = true
            } else {
                showingRemoveUnfavoritedAlert = true
            }
        } catch {
            errorMessage = error.userFacingMessage
        }
    }

    func removeUnfavoritedNodes(connectionManager: ConnectionManager) {
        isRemovingUnfavorited = true
        removeTask = Task {
            defer { isRemovingUnfavorited = false }
            do {
                let result = try await connectionManager.removeUnfavoritedNodes()
                isRemovingUnfavorited = false
                if result.removed == result.total {
                    withAnimation { showRemoveSuccess = true }
                    try await Task.sleep(for: Self.removeSuccessDisplayDuration)
                    withAnimation { showRemoveSuccess = false }
                } else {
                    removeResult = L10n.Settings.DangerZone.Alert.RemoveUnfavorited
                        .partial(result.removed, result.total)
                    showRemoveResult = true
                }
            } catch {
                if !(error is CancellationError) {
                    errorMessage = error.userFacingMessage
                }
            }
        }
    }
}
