import SwiftUI
import MC1Services

struct ContentView: View {
    @Environment(\.appState) private var appState
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        @Bindable var connectionUI = appState.connectionUI

        Group {
            if appState.onboarding.hasCompletedOnboarding {
                if horizontalSizeClass == .regular {
                    MainSidebarView()
                } else {
                    MainTabView()
                }
            } else {
                OnboardingView()
            }
        }
        .animation(.default, value: appState.onboarding.hasCompletedOnboarding)
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                appState.handleBecameActive()
            }
        }
        .alert(
            connectionUI.connectionFailedTitle ?? L10n.Localizable.Alert.ConnectionFailed.title,
            isPresented: $connectionUI.showingConnectionFailedAlert
        ) {
            if appState.connectionUI.failedPairingDeviceID != nil {
                switch appState.connectionUI.pairingFailureKind {
                case .authentication:
                    // Auth-failure variant — bond is bad, destructive remove is the recovery
                    Button(L10n.Localizable.Alert.ConnectionFailed.removeAndRetry, role: .destructive) {
                        appState.removeFailedPairingAndRetry()
                    }
                    .accessibilityLabel(L10n.Localizable.Accessibility.Alert.ConnectionFailed.removeAndRetry)
                    Button(L10n.Localizable.Common.cancel, role: .cancel) {
                        appState.connectionUI.failedPairingDeviceID = nil
                    }
                case .transient, .none:
                    // Transient variant — bond is still good, prefer non-destructive retry.
                    // `.none` is unreachable in practice (every pairing-failure path routes
                    // through `presentPairingFailure`, which always sets the kind). Folding
                    // it into the safer branch ensures a missing kind can't promote a working
                    // bond into the destructive recovery.
                    Button(L10n.Localizable.Common.tryAgain) {
                        Task { await appState.retryFailedPairingConnect() }
                    }
                    Button(L10n.Localizable.Alert.ConnectionFailed.removeAndRetry, role: .destructive) {
                        appState.removeFailedPairingAndRetry()
                    }
                    .accessibilityLabel(L10n.Localizable.Accessibility.Alert.ConnectionFailed.removeAndRetry)
                    Button(L10n.Localizable.Common.cancel, role: .cancel) {
                        appState.connectionUI.failedPairingDeviceID = nil
                    }
                }
            } else {
                Button(L10n.Localizable.Common.ok, role: .cancel) { }
            }
        } message: {
            Text(appState.connectionUI.connectionFailedMessage ?? L10n.Localizable.Alert.ConnectionFailed.defaultMessage)
        }
        .alert(
            L10n.Localizable.Alert.CouldNotConnect.title,
            isPresented: Binding(
                get: { appState.connectionUI.otherAppWarningDeviceID != nil },
                set: { if !$0 { appState.connectionUI.otherAppWarningDeviceID = nil } }
            )
        ) {
            Button(L10n.Localizable.Common.ok) {
                appState.connectionUI.otherAppWarningDeviceID = nil
            }
        } message: {
            Text(L10n.Localizable.Alert.CouldNotConnect.otherAppMessage)
        }
        // macOS "Designed for iPad" device picker. `bluetoothScanPicker` is nil on iOS, where
        // AccessorySetupKit presents its own system picker, so this sheet never appears there.
        .sheet(isPresented: Binding(
            get: { appState.connectionManager.bluetoothScanPicker?.isPresenting ?? false },
            set: { if !$0 { appState.connectionManager.bluetoothScanPicker?.cancel() } }
        )) {
            if let scanPicker = appState.connectionManager.bluetoothScanPicker {
                DeviceScannerSheet(picker: scanPicker)
            }
        }
    }
}

// MARK: - Onboarding View

struct OnboardingView: View {
    @Environment(\.appState) private var appState

    var body: some View {
        @Bindable var onboarding = appState.onboarding

        NavigationStack(path: $onboarding.onboardingPath) {
            WelcomeView()
                .navigationDestination(for: OnboardingStep.self) { step in
                    switch step {
                    case .welcome:
                        WelcomeView()
                    case .permissions:
                        PermissionsView()
                    case .pair:
                        DeviceScanView()
                    case .region:
                        RegionStepView()
                    case .preset:
                        PresetStepView()
                    }
                }
        }
    }
}

// MARK: - Main Tab View

struct MainTabView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @State private var showingDeviceSelection = false

    var body: some View {
        @Bindable var navigation = appState.navigation

        TabView(selection: $navigation.selectedTab) {
            Tab(L10n.Localizable.Tabs.chats, systemImage: "message.fill", value: AppTab.chats.rawValue) {
                ChatsView()
            }
            .badge(appState.services?.notificationService.badgeCount ?? 0)

            Tab(L10n.Localizable.Tabs.nodes, systemImage: "flipphone", value: AppTab.nodes.rawValue) {
                ContactsListView()
            }

            Tab(L10n.Localizable.Tabs.map, systemImage: "map.fill", value: AppTab.map.rawValue) {
                MapView()
            }

            Tab(L10n.Localizable.Tabs.tools, systemImage: "wrench.and.screwdriver", value: AppTab.tools.rawValue) {
                ToolsView()
            }

            Tab(L10n.Localizable.Tabs.settings, systemImage: "gear", value: AppTab.settings.rawValue) {
                SettingsView()
            }
        }
        .themedChrome(theme)
        .syncingPillOverlay(onDisconnectedTap: { showingDeviceSelection = true })
        .onChange(of: appState.navigation.selectedTab) { _, _ in
            // Donate pending device menu tip when returning to a valid tab
            if appState.navigation.pendingDeviceMenuTipDonation && appState.navigation.isOnValidTabForDeviceMenuTip {
                Task {
                    await appState.donateDeviceMenuTipIfOnValidTab()
                }
            }
        }
        .sheet(isPresented: $showingDeviceSelection) {
            DeviceSelectionSheet()
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
    }
}

#Preview("Content View - Onboarding") {
    ContentView()
        .environment(\.appState, AppState())
}

#Preview("Content View - Main App") {
    let appState = AppState()
    appState.onboarding.hasCompletedOnboarding = true
    return ContentView()
        .environment(\.appState, appState)
}
