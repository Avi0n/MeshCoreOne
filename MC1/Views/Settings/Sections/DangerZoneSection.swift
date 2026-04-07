import SwiftUI
import MC1Services

/// Destructive device actions
struct DangerZoneSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @State private var showingForgetAlert = false
    @State private var showingResetAlert = false
    @State private var isResetting = false
    @State private var errorMessage: String?
    @State private var showingRemoveUnfavoritedAlert = false
    @State private var isRemovingUnfavorited = false
    @State private var showRemoveSuccess = false
    @State private var unfavoritedCount = 0
    @State private var showRemoveResult = false
    @State private var removeResult: String?
    @State private var removeTask: Task<Void, Never>?

    var body: some View {
        Section {
            Button(role: .destructive) {
                fetchUnfavoritedCount()
            } label: {
                if isRemovingUnfavorited {
                    HStack {
                        ProgressView()
                        Text(L10n.Settings.DangerZone.removing)
                    }
                } else if showRemoveSuccess {
                    Label(L10n.Settings.DangerZone.removed, systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                } else {
                    Label(L10n.Settings.DangerZone.removeUnfavorited, systemImage: "person.2.slash")
                }
            }
            .radioDisabled(for: appState.connectionState, or: isRemovingUnfavorited || showRemoveSuccess)

            Button(role: .destructive) {
                showingForgetAlert = true
            } label: {
                Label(L10n.Settings.DangerZone.forgetDevice, systemImage: "trash")
            }

            Button(role: .destructive) {
                showingResetAlert = true
            } label: {
                if isResetting {
                    HStack {
                        ProgressView()
                        Text(L10n.Settings.DangerZone.resetting)
                    }
                } else {
                    Label(L10n.Settings.DangerZone.factoryReset, systemImage: "exclamationmark.triangle")
                }
            }
            .radioDisabled(for: appState.connectionState, or: isResetting)
        } header: {
            Text(L10n.Settings.DangerZone.header)
        } footer: {
            Text(L10n.Settings.DangerZone.footer)
        }
        .alert(L10n.Settings.DangerZone.Alert.Forget.title, isPresented: $showingForgetAlert) {
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
            Button(L10n.Settings.DangerZone.Alert.Forget.confirm, role: .destructive) {
                forgetDevice()
            }
        } message: {
            Text(L10n.Settings.DangerZone.Alert.Forget.message)
        }
        .alert(L10n.Settings.DangerZone.Alert.Reset.title, isPresented: $showingResetAlert) {
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
            Button(L10n.Settings.DangerZone.Alert.Reset.confirm, role: .destructive) {
                factoryReset()
            }
        } message: {
            Text(L10n.Settings.DangerZone.Alert.Reset.message)
        }
        .alert(L10n.Settings.DangerZone.Alert.RemoveUnfavorited.title, isPresented: $showingRemoveUnfavoritedAlert) {
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
            Button(L10n.Settings.DangerZone.Alert.RemoveUnfavorited.confirm, role: .destructive) {
                removeUnfavoritedNodes()
            }
        } message: {
            Text(L10n.Settings.DangerZone.Alert.RemoveUnfavorited.message(unfavoritedCount))
        }
        .alert(L10n.Settings.DangerZone.Alert.RemoveUnfavorited.resultTitle, isPresented: $showRemoveResult) {
            Button(L10n.Localizable.Common.ok) { }
        } message: {
            Text(removeResult ?? "")
        }
        .onDisappear { removeTask?.cancel() }
        .errorAlert($errorMessage)
    }

    private func forgetDevice() {
        Task {
            do {
                try await appState.connectionManager.forgetDevice()
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func factoryReset() {
        guard let settingsService = appState.services?.settingsService else {
            errorMessage = L10n.Settings.DangerZone.Error.servicesUnavailable
            return
        }

        guard let deviceID = appState.connectedDevice?.id else {
            errorMessage = L10n.Settings.DangerZone.Error.servicesUnavailable
            return
        }

        isResetting = true
        Task {
            defer { isResetting = false }

            // Send reset command. The device typically reboots before responding,
            // so a timeout/connection error here is expected — not a failure.
            do {
                try await settingsService.factoryReset()
                try await Task.sleep(for: .seconds(1))
            } catch {
                // Expected: device reboots before sending OK response
            }

            // Always clean up: remove from ASK, disconnect, delete from SwiftData
            await appState.connectionManager.forgetDevice(id: deviceID)
            dismiss()
        }
    }

    private func fetchUnfavoritedCount() {
        Task {
            do {
                unfavoritedCount = try await appState.connectionManager.unfavoritedNodeCount()
                if unfavoritedCount == 0 {
                    removeResult = L10n.Settings.DangerZone.Alert.RemoveUnfavorited.noneFound
                    showRemoveResult = true
                } else {
                    showingRemoveUnfavoritedAlert = true
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func removeUnfavoritedNodes() {
        isRemovingUnfavorited = true
        removeTask = Task {
            defer { isRemovingUnfavorited = false }
            do {
                let result = try await appState.connectionManager.removeUnfavoritedNodes()
                isRemovingUnfavorited = false
                if result.removed == result.total {
                    withAnimation { showRemoveSuccess = true }
                    try await Task.sleep(for: .seconds(1.5))
                    withAnimation { showRemoveSuccess = false }
                } else {
                    removeResult = L10n.Settings.DangerZone.Alert.RemoveUnfavorited
                        .partial(result.removed, result.total)
                    showRemoveResult = true
                }
            } catch {
                if !(error is CancellationError) {
                    errorMessage = error.localizedDescription
                }
            }
        }
    }
}
