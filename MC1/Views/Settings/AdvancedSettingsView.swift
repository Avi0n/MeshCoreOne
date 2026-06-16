import SwiftUI
import MC1Services

/// Advanced settings sheet for power users
struct AdvancedSettingsView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss

    @State private var showingImportKeySheet = false
    @State private var showingRegenerateSheet = false

    var body: some View {
        List {
            // Manual Radio Configuration
            AdvancedRadioSection()

            // Path Hash Mode (firmware v10+)
            if appState.connectedDevice?.supportsPathHashMode == true {
                PathHashModeSection()
            }

            // Default Flood Scope (firmware v11+)
            if appState.connectedDevice?.supportsDefaultFloodScope == true {
                DefaultFloodScopeSection()
            }

            // Nodes Settings
            NodesSettingsSection()

            // Auto-Remove Old Nodes
            StaleNodeCleanupSection()

            // Telemetry Settings
            TelemetrySettingsSection()

            // Direct Messages Settings
            DirectMessagesSettingsSection()

            // Config Export/Import
            ConfigExportImportSection()

            // Device Actions
            DeviceActionsSection()

            // Identity
            DeviceIdentitySection(
                showingImportKeySheet: $showingImportKeySheet,
                showingRegenerateSheet: $showingRegenerateSheet
            )

            // Danger Zone
            DangerZoneSection()
        }
        .themedCanvas(theme)
        .settingsSubpageDestinations()
        .sheet(isPresented: $showingImportKeySheet) {
            ImportKeySheet()
        }
        .sheet(isPresented: $showingRegenerateSheet) {
            RegenerateIdentitySheet()
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(L10n.Settings.AdvancedSettings.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button(L10n.Localizable.Common.done) {
                    UIApplication.shared.sendAction(
                        #selector(UIResponder.resignFirstResponder),
                        to: nil,
                        from: nil,
                        for: nil
                    )
                }
            }
        }
        .task(id: refreshTaskID) {
            await refreshDeviceSettings()
        }
        .onChange(of: appState.connectedDevice) { _, newDevice in
            if newDevice == nil {
                dismiss()
            }
        }
    }

    private var refreshTaskID: String {
        let deviceID = appState.connectedDevice?.id.uuidString ?? "none"
        let syncPhase = appState.connectionUI.currentSyncPhase.map { String(describing: $0) } ?? "none"
        return "\(deviceID)-\(String(describing: appState.connectionState))-\(syncPhase)"
    }

    /// Fetch fresh device settings to ensure cache is up-to-date
    private func refreshDeviceSettings() async {
        // Wait until contact/channel sync contention is over before sending startup reads.
        guard appState.canRunSettingsStartupReads,
              let settingsService = appState.services?.settingsService else { return }
        _ = try? await settingsService.getSelfInfo()

        // Only refresh autoAddConfig on v1.12+ firmware
        if appState.connectedDevice?.supportsAutoAddConfig == true {
            try? await settingsService.refreshAutoAddConfig()
        }

        if appState.connectedDevice?.supportsDefaultFloodScope == true {
            _ = try? await settingsService.getDefaultFloodScope()
        }
    }

}
