import SwiftUI
import MC1Services
import TipKit

/// The settings list itself, shared by the compact `SettingsView` (stack) and the iPad
/// `MainSidebarView` content column (split). `isSidebar` switches the iPad-only selection binding
/// and themed-row flattening; the compact stack pushes via value-based `NavigationLink` instead.
struct SettingsListContent: View {
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

    /// Drives the iPad split's selected settings page. The compact stack leaves it nil — inside a
    /// `NavigationStack` a value-based `NavigationLink` drives the stack path (via `SettingsView`'s
    /// `navigationDestination`), not the list selection — so the row capsule stays iPad-only.
    private var settingSelection: Binding<SettingsDetail?> {
        Binding(
            get: { appState.navigation.selectedSetting },
            set: { appState.navigation.selectedSetting = $0 }
        )
    }

    var body: some View {
        settingsList
            .themedCanvas(theme)
            .navigationTitle(L10n.Settings.title)
            .toolbar {
                bleStatusToolbarItem(isVisible: !isSidebar || appState.navigation.isSidebarCollapsed)
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

    /// Only the iPad sidebar column carries a selection binding, where it drives the detail pane. The
    /// compact stack must omit it: a `List(selection:)` turns a row tap into a selection instead of
    /// letting the value-based `NavigationLink` push, which would break compact navigation.
    @ViewBuilder
    private var settingsList: some View {
        if isSidebar {
            List(selection: settingSelection) { sections }
        } else {
            List { sections }
        }
    }

    @ViewBuilder
    private var sections: some View {
        if let device = appState.connectedDevice {
            MyDeviceSection(device: device, isSidebar: isSidebar)
        } else {
            NoDeviceSection(showingDeviceSelection: $showingDeviceSelection, isSidebar: isSidebar)
        }

        Section {
            SettingsDetailRow(detail: .notifications, isSidebar: isSidebar) {
                TintedLabel(L10n.Settings.Notifications.header, systemImage: "bell.badge")
            }

            SettingsDetailRow(detail: .chats, isSidebar: isSidebar) {
                TintedLabel(L10n.Settings.ChatSettings.title, systemImage: "bubble.left.and.bubble.right")
            }

            AppearanceSection(isSidebar: isSidebar)

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

            SettingsDetailRow(detail: .offlineMaps, isSidebar: isSidebar) {
                TintedLabel(L10n.Settings.OfflineMaps.title, systemImage: "map.fill")
            }

            SettingsDetailRow(detail: .backup, isSidebar: isSidebar) {
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
            SettingsDetailRow(detail: .deviceInfo, isSidebar: isSidebar) {
                SettingsRow(L10n.Settings.DeviceInfo.title, systemImage: "cpu", detail: device.nodeName)
            }
            .accessibilityValue(device.nodeName)

            SettingsDetailRow(detail: .radio, isSidebar: isSidebar) {
                SettingsRow(L10n.Settings.Radio.header, systemImage: "antenna.radiowaves.left.and.right", detail: radioDetailText)
            }
            .accessibilityValue(radioDetailText)

            SettingsDetailRow(detail: .location, isSidebar: isSidebar) {
                SettingsRow(L10n.Settings.Location.header, systemImage: "location", detail: locationDetailText)
            }
            .accessibilityValue(locationDetailText)

            SettingsDetailRow(detail: .connection, isSidebar: isSidebar) {
                let isWiFi = appState.connectionManager.currentTransportType == .wifi
                TintedLabel(
                    isWiFi ? L10n.Settings.Wifi.header : L10n.Settings.Bluetooth.header,
                    systemImage: isWiFi ? "wifi" : "wave.3.right"
                )
            }

            SettingsDetailRow(detail: .advanced, isSidebar: isSidebar) {
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
