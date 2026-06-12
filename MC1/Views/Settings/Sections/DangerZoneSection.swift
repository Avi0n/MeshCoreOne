import SwiftUI
import MC1Services

/// Destructive device actions
struct DangerZoneSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = DangerZoneViewModel()

    var body: some View {
        Section {
            Button(role: .destructive) {
                Task { await viewModel.fetchUnfavoritedCount(connectionManager: appState.connectionManager) }
            } label: {
                if viewModel.isRemovingUnfavorited {
                    HStack {
                        ProgressView()
                        Text(L10n.Settings.DangerZone.removing)
                    }
                } else if viewModel.showRemoveSuccess {
                    Label(L10n.Settings.DangerZone.removed, systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                } else {
                    Label(L10n.Settings.DangerZone.removeUnfavorited, systemImage: "person.2.slash")
                }
            }
            .radioDisabled(
                for: appState.connectionState,
                or: viewModel.isRemovingUnfavorited || viewModel.showRemoveSuccess
            )

            Button(role: .destructive) {
                viewModel.showingForgetConfirmation = true
            } label: {
                Label(L10n.Settings.DangerZone.forgetDevice, systemImage: "trash")
            }

            Button(role: .destructive) {
                viewModel.showingResetAlert = true
            } label: {
                if viewModel.isResetting {
                    HStack {
                        ProgressView()
                        Text(L10n.Settings.DangerZone.resetting)
                    }
                } else {
                    Label(L10n.Settings.DangerZone.factoryReset, systemImage: "exclamationmark.triangle")
                }
            }
            .radioDisabled(for: appState.connectionState, or: viewModel.isResetting)
        } header: {
            Text(L10n.Settings.DangerZone.header)
        } footer: {
            Text(L10n.Settings.DangerZone.footer)
        }
        .themedRowBackground(theme)
        .confirmationDialog(
            L10n.Settings.DangerZone.Dialog.Forget.title,
            isPresented: $viewModel.showingForgetConfirmation,
            titleVisibility: .visible
        ) {
            Button(L10n.Settings.DangerZone.Dialog.Forget.keepData, role: .destructive) {
                forgetDevice(deleteData: false)
            }
            Button(L10n.Settings.DangerZone.Dialog.Forget.deleteAll, role: .destructive) {
                forgetDevice(deleteData: true)
            }
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
        } message: {
            Text(L10n.Settings.DangerZone.Dialog.Forget.message)
        }
        .alert(L10n.Settings.DangerZone.Alert.Reset.title, isPresented: $viewModel.showingResetAlert) {
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
            Button(L10n.Settings.DangerZone.Alert.Reset.confirm, role: .destructive) {
                Task {
                    if await viewModel.factoryReset(
                        settingsService: appState.services?.settingsService,
                        deviceID: appState.connectedDevice?.id,
                        connectionManager: appState.connectionManager
                    ) {
                        dismiss()
                    }
                }
            }
        } message: {
            Text(L10n.Settings.DangerZone.Alert.Reset.message)
        }
        .alert(
            L10n.Settings.DangerZone.Alert.RemoveUnfavorited.title,
            isPresented: $viewModel.showingRemoveUnfavoritedAlert
        ) {
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
            Button(L10n.Settings.DangerZone.Alert.RemoveUnfavorited.confirm, role: .destructive) {
                viewModel.removeUnfavoritedNodes(connectionManager: appState.connectionManager)
            }
        } message: {
            Text(L10n.Settings.DangerZone.Alert.RemoveUnfavorited.message(viewModel.unfavoritedCount))
        }
        .alert(
            L10n.Settings.DangerZone.Alert.RemoveUnfavorited.resultTitle,
            isPresented: $viewModel.showRemoveResult
        ) {
            Button(L10n.Localizable.Common.ok) { }
        } message: {
            Text(viewModel.removeResult ?? "")
        }
        .onDisappear { viewModel.cancelPendingRemoval() }
        .errorAlert($viewModel.errorMessage)
    }

    private func forgetDevice(deleteData: Bool) {
        Task {
            if await viewModel.forgetDevice(connectionManager: appState.connectionManager, deleteData: deleteData) {
                dismiss()
            }
        }
    }
}
