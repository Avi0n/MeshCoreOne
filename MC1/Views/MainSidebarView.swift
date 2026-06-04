import SwiftUI
import MC1Services

/// The iPad sidebar shell: a sidebar selecting the active `AppTab`, plus that section's columns.
/// List sections use a three-column split; Map uses two columns so the sidebar reveal control
/// keeps a home in the detail toolbar (a zero-width middle column would strip it and strand the
/// sidebar closed). Switching tabs rebuilds the inactive section's tree, so per-section selection
/// that must survive that teardown (Chats route, Nodes contact, selected Tool) lives in
/// `NavigationCoordinator`; the shared view models are hosted here so each section's content and
/// detail columns read one instance. Selecting a row collapses the sidebar (Mail-style); while
/// collapsed the radio is surfaced in the section toolbar (gated on `isSidebarCollapsed`) so the
/// connection control stays reachable.
struct MainSidebarView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var showingDeviceSelection = false

    /// Moves the VoiceOver cursor into the active section's content when the sidebar auto-collapses
    /// on selection. The collapse removes the just-tapped sidebar row, which would otherwise strand
    /// VoiceOver focus off-screen; setting this to the selected tab relocates focus to that section's
    /// content (and VoiceOver reads its label). A per-section value rather than a Bool guarantees each
    /// switch is a distinct change so focus re-moves every time; it is a no-op when VoiceOver is off.
    @AccessibilityFocusState private var focusedColumn: AppTab?

    // Shared Chats view model, hosted once so content + detail columns share it.
    @State private var chatViewModel = ChatViewModel()

    // Shared Line of Sight view model, so the Tools content panel and detail map stay in sync.
    @State private var lineOfSightViewModel = LineOfSightViewModel()

    // Shared Settings state, mirroring SettingsView's regular-width path.
    @State private var settingsShowingDeviceSelection = false
    @State private var settingsDemoModeManager = DemoModeManager.shared

    private static let toolListColumnWidthMin: CGFloat = 320
    private static let toolListColumnWidthIdeal: CGFloat = 360
    private static let toolListColumnWidthMax: CGFloat = 420
    private static let lineOfSightPanelWidthMin: CGFloat = 380
    private static let lineOfSightPanelWidthIdeal: CGFloat = 440
    private static let lineOfSightPanelWidthMax: CGFloat = 560

    private var selectedTab: AppTab {
        AppTab(rawValue: appState.navigation.selectedTab) ?? .chats
    }

    /// Widen the Tools content column to fit the Line of Sight analysis panel, narrow it back to a
    /// tool-list width otherwise. The modifier is applied unconditionally with switched values so
    /// the content column's identity stays stable and the width change animates.
    private var toolsContentWidth: (min: CGFloat, ideal: CGFloat, max: CGFloat) {
        appState.navigation.selectedTool == .lineOfSight
            ? (Self.lineOfSightPanelWidthMin, Self.lineOfSightPanelWidthIdeal, Self.lineOfSightPanelWidthMax)
            : (Self.toolListColumnWidthMin, Self.toolListColumnWidthIdeal, Self.toolListColumnWidthMax)
    }

    var body: some View {
        shell
            .navigationSplitViewStyle(.automatic)
            .themedChrome(theme)
            .syncingPillOverlay(onDisconnectedTap: { showingDeviceSelection = true })
            .onChange(of: appState.navigation.selectedTab) { _, newValue in
                clearToolSelectionWhenLeavingTools()
                let newTab = AppTab(rawValue: newValue) ?? .chats
                // Backstop for programmatic tab changes (deep links, notification taps) that bypass
                // the sidebar's selection setter; direct row taps already collapse atomically there.
                columnVisibility = newTab.collapsedSidebarVisibility
                // The collapse drops the tapped sidebar row, so steer VoiceOver into the new content.
                focusedColumn = newTab
                // The radio lives in AppSidebar, so donate the tip when a donation is waiting.
                if appState.navigation.pendingDeviceMenuTipDonation {
                    Task {
                        await appState.donateDeviceMenuTip()
                    }
                }
            }
            .onChange(of: columnVisibility, initial: true) { _, newValue in
                // `initial: true` resyncs on a compact→regular size-class change, which freshly
                // instantiates this view (columnVisibility resets to .all) while the shared
                // NavigationCoordinator may still hold a stale collapsed flag from a prior session.
                appState.navigation.isSidebarCollapsed = newValue != .all
            }
            .onChange(of: appState.connectedDevice) { _, newDevice in
                // An explicit (status-menu) disconnect tears down the connection without firing
                // onConnectionLost, so clearPerRadioSelection never runs for it. Clear a now-dead
                // radio-only tool and per-device settings page here; a radio-to-radio switch (device
                // stays non-nil) is handled by clearPerRadioSelection instead.
                if newDevice == nil {
                    appState.navigation.clearPerDeviceSelection()
                }
            }
            .sheet(isPresented: $showingDeviceSelection) {
                DeviceSelectionSheet()
                    .presentationDetents([.medium])
                    .presentationDragIndicator(.visible)
            }
    }

    // MARK: - Section shell

    @ViewBuilder
    private var shell: some View {
        switch selectedTab {
        case .map:
            NavigationSplitView(columnVisibility: $columnVisibility) {
                AppSidebar(columnVisibility: $columnVisibility)
            } detail: {
                // The map already draws full-bleed (its canvas ignores the safe area), so it
                // extends under the floating sidebar and status bar with real tiles. The
                // backgroundExtensionEffect used for static section backgrounds would instead
                // mirror the map's edges into the safe area, so it is deliberately omitted here.
                MapView()
                    .accessibilityFocused($focusedColumn, equals: .map)
            }
        case .chats, .nodes, .settings, .tools:
            NavigationSplitView(columnVisibility: $columnVisibility) {
                AppSidebar(columnVisibility: $columnVisibility)
            } content: {
                contentColumn
            } detail: {
                detailColumn
            }
        }
    }

    // MARK: - Content column

    @ViewBuilder
    private var contentColumn: some View {
        switch selectedTab {
        case .chats:
            NavigationStack {
                ChatsContentColumn(viewModel: chatViewModel)
                    .accessibilityFocused($focusedColumn, equals: .chats)
            }
            .modifier(SidebarContentColumnBackground(theme: theme))
        case .nodes:
            NavigationStack {
                ContactsContentColumn()
                    .accessibilityFocused($focusedColumn, equals: .nodes)
            }
            .modifier(SidebarContentColumnBackground(theme: theme))
        case .settings:
            SettingsListContent(
                showingDeviceSelection: $settingsShowingDeviceSelection,
                demoModeManager: settingsDemoModeManager,
                isSidebar: true
            )
            .accessibilityFocused($focusedColumn, equals: .settings)
            .modifier(SidebarContentColumnBackground(theme: theme))
        case .tools:
            // Unlike the other sections, Tools applies SidebarContentColumnBackground itself (on the
            // tool list only) so the Line of Sight analysis panel keeps its opaque background.
            ToolsContentColumn(lineOfSightViewModel: lineOfSightViewModel)
                .accessibilityFocused($focusedColumn, equals: .tools)
                .navigationSplitViewColumnWidth(
                    min: toolsContentWidth.min,
                    ideal: toolsContentWidth.ideal,
                    max: toolsContentWidth.max
                )
        case .map:
            // Map renders through the two-column shell, never this column.
            EmptyView()
        }
    }

    // MARK: - Detail column

    @ViewBuilder
    private var detailColumn: some View {
        switch selectedTab {
        case .chats:
            NavigationStack {
                ChatsSplitDetailContent(viewModel: chatViewModel)
            }
            .id(appState.navigation.chatsSelectedRoute?.conversationID)
        case .nodes:
            NavigationStack {
                ContactsDetailColumn()
            }
            .id(appState.navigation.selectedContact?.id)
        case .settings:
            NavigationStack {
                if let setting = appState.navigation.selectedSetting {
                    SettingsDetailView(detail: setting)
                } else {
                    ContentUnavailableView(L10n.Settings.selectSetting, systemImage: "gear")
                }
            }
            .id(appState.navigation.selectedSetting)
        case .tools:
            ToolsDetailColumn(lineOfSightViewModel: lineOfSightViewModel)
        case .map:
            // Map renders through the two-column shell, never this column.
            EmptyView()
        }
    }

    // MARK: - Tool selection lifecycle

    /// Tools selection persists in `NavigationCoordinator`; clear it when leaving the Tools tab so
    /// returning lands on the tool list rather than a previously open tool.
    private func clearToolSelectionWhenLeavingTools() {
        if selectedTab != .tools {
            appState.navigation.selectedTool = nil
        }
    }
}

#Preview {
    MainSidebarView()
        .environment(\.appState, AppState())
}
