import SwiftUI
import MC1Services
import TipKit

/// Main settings screen — navigation-link rows for device settings, always-visible app settings
struct SettingsView: View {
    @Environment(\.appState) private var appState
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var showingDeviceSelection = false
    private var demoModeManager = DemoModeManager.shared

    var body: some View {
        if horizontalSizeClass == .regular {
            NavigationSplitView {
                SettingsListContent(
                    showingDeviceSelection: $showingDeviceSelection,
                    demoModeManager: demoModeManager,
                    isSidebar: true
                )
            } detail: {
                ContentUnavailableView(L10n.Settings.selectSetting, systemImage: "gear")
            }
        } else {
            NavigationStack {
                SettingsListContent(
                    showingDeviceSelection: $showingDeviceSelection,
                    demoModeManager: demoModeManager,
                    isSidebar: false
                )
            }
        }
    }
}

// MARK: - Settings List Content

private struct SettingsListContent: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.openURL) private var openURL
    @Binding var showingDeviceSelection: Bool
    @Bindable var demoModeManager: DemoModeManager
    /// `true` when this list is the iPad split-view sidebar column, whose `.sidebar` style draws
    /// rows transparent; themed card rows are flattened to canvas to match the system default there.
    let isSidebar: Bool
    @State private var exportedLogFile: ExportedLogFile?
    private let liveActivityTip = LiveActivityTip()

    var body: some View {
        List {
            if let device = appState.connectedDevice {
                MyDeviceSection(device: device, isSidebar: isSidebar)
            } else {
                NoDeviceSection(showingDeviceSelection: $showingDeviceSelection, isSidebar: isSidebar)
            }

            Section {
                NavigationLink {
                    NotificationSettingsView()
                } label: {
                    TintedLabel(L10n.Settings.Notifications.header, systemImage: "bell.badge")
                }

                NavigationLink {
                    ChatSettingsView()
                } label: {
                    TintedLabel(L10n.Settings.ChatSettings.title, systemImage: "bubble.left.and.bubble.right")
                }

                AppearanceSection()

                TipView(liveActivityTip, arrowEdge: .bottom)

                Toggle(isOn: Binding(
                    get: { appState.liveActivityManager.isEnabled },
                    set: { newValue in
                        appState.liveActivityManager.isEnabled = newValue
                        Task {
                            if !newValue {
                                await appState.liveActivityManager.endActivity()
                            } else if appState.connectionState.isConnected {
                                await appState.wireServicesIfConnected()
                            }
                        }
                    }
                )) {
                    TintedLabel(L10n.Settings.LiveActivity.title, systemImage: "platter.filled.bottom.and.arrow.down.iphone")
                }

                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        openURL(url)
                    }
                } label: {
                    SettingsRow(
                        L10n.Settings.Language.title,
                        systemImage: "globe",
                        detail: currentLanguageDisplayName
                    )
                }

                NavigationLink {
                    OfflineMapSettingsView()
                } label: {
                    TintedLabel(L10n.Settings.OfflineMaps.title, systemImage: "map.fill")
                }

                NavigationLink {
                    BackupRestoreView(
                        connectionManager: appState.connectionManager,
                        onImportRestoredData: { [appState] in appState.notifyDataRestored() },
                        onChannelDraftSlotsAffected: { [appState] slotsByRadio in
                            appState.draftStore.clearChannelDrafts(slotsByRadio: slotsByRadio)
                        }
                    )
                } label: {
                    TintedLabel(L10n.Settings.Settings.Backup.title, systemImage: "archivebox.fill")
                }
            } header: {
                Text(L10n.Settings.AppSettings.header)
            }
            .themedRowBackground(theme, flatten: isSidebar)

            AboutSection(isSidebar: isSidebar)

            DiagnosticsSection(exportedFile: $exportedLogFile, isSidebar: isSidebar)

            if demoModeManager.isUnlocked {
                Section {
                    Toggle(L10n.Settings.DemoMode.enabled, isOn: $demoModeManager.isEnabled)
                } header: {
                    Text(L10n.Settings.DemoMode.header)
                } footer: {
                    Text(L10n.Settings.DemoMode.footer)
                }
                .themedRowBackground(theme, flatten: isSidebar)
            }

            #if DEBUG
            Section {
                Button {
                    appState.onboarding.resetOnboarding()
                } label: {
                    TintedLabel("Reset Onboarding", systemImage: "arrow.counterclockwise")
                }
            } header: {
                Text("Debug")
            }
            .themedRowBackground(theme, flatten: isSidebar)
            #endif

            Section {
            } footer: {
                let version = Bundle.main.appVersion
                let build = Bundle.main.appBuild
                VStack {
                    Text(L10n.Settings.version(version))
                    Text(L10n.Settings.build(build))
                }
                .padding(.top, -8)
                .padding(.bottom)
                .frame(maxWidth: .infinity)
            }
            .themedRowBackground(theme, flatten: isSidebar)
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Settings.title)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                BLEStatusIndicatorView()
            }
        }
        .sheet(isPresented: $showingDeviceSelection) {
            DeviceSelectionSheet()
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
        .sheet(item: $exportedLogFile) { file in
            ActivityView(activityItems: [file.url])
        }
    }

    private var currentLanguageDisplayName: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        return Locale.current.localizedString(forLanguageCode: code) ?? code
    }

}

// MARK: - My Device Section

private struct MyDeviceSection: View {
    let device: DeviceDTO
    let isSidebar: Bool
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    var body: some View {
        Section {
            NavigationLink {
                DeviceInfoView()
            } label: {
                SettingsRow(L10n.Settings.DeviceInfo.title, systemImage: "cpu", detail: device.nodeName)
            }
            .accessibilityValue(device.nodeName)

            NavigationLink {
                RadioSettingsView()
            } label: {
                SettingsRow(L10n.Settings.Radio.header, systemImage: "antenna.radiowaves.left.and.right", detail: radioDetailText)
            }
            .accessibilityValue(radioDetailText)

            NavigationLink {
                LocationSettingsView()
            } label: {
                SettingsRow(L10n.Settings.Location.header, systemImage: "location", detail: locationDetailText)
            }
            .accessibilityValue(locationDetailText)

            NavigationLink {
                ConnectionSettingsView()
            } label: {
                let isWiFi = appState.connectionManager.currentTransportType == .wifi
                TintedLabel(
                    isWiFi ? L10n.Settings.Wifi.header : L10n.Settings.Bluetooth.header,
                    systemImage: isWiFi ? "wifi" : "wave.3.right"
                )
            }

            NavigationLink {
                AdvancedSettingsView()
            } label: {
                TintedLabel(L10n.Settings.AdvancedSettings.title, systemImage: "gearshape.2")
            }
        } header: {
            Text(L10n.Settings.MyDevice.header)
        }
        .themedRowBackground(theme, flatten: isSidebar)
    }

    private var radioDetailText: String {
        let preset = device.clientRepeat
            ? RadioPresets.matchingRepeatPreset(
                frequencyKHz: device.frequency,
                bandwidthKHz: device.bandwidth,
                spreadingFactor: device.spreadingFactor,
                codingRate: device.codingRate
            )
            : RadioPresets.matchingPreset(
                frequencyKHz: device.frequency,
                bandwidthKHz: device.bandwidth,
                spreadingFactor: device.spreadingFactor,
                codingRate: device.codingRate
            )
        return preset?.name ?? L10n.Settings.BatteryCurve.custom
    }

    private var locationDetailText: String {
        device.sharesLocationPublicly
            ? L10n.Settings.Location.sharingPublicly
            : L10n.Settings.Location.notSharing
    }
}

// MARK: - Settings Row with Detail Text

private struct SettingsRow: View {
    let title: String
    let systemImage: String
    let detail: String

    init(_ title: String, systemImage: String, detail: String) {
        self.title = title
        self.systemImage = systemImage
        self.detail = detail
    }

    var body: some View {
        HStack {
            TintedLabel(title, systemImage: systemImage)
            Spacer()
            Text(detail)
                .foregroundStyle(.secondary)
        }
    }
}

#Preview {
    SettingsView()
        .environment(\.appState, AppState())
}
