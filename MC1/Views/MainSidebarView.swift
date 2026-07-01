import MC1Services
import SwiftUI

/// The iPad sidebar shell: a sidebar selecting the active `AppTab`, plus that section's columns
/// (list sections are three-column, Map is two-column). Per-section selection that must survive the
/// section-switch teardown lives in `NavigationCoordinator`; the shared view models are hosted here
/// so each section's content and detail columns read one instance.
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

  /// Shared Chats view model, hosted once so content + detail columns share it.
  @State private var chatViewModel = ChatViewModel()

  /// Shared Line of Sight view model, so the Tools content panel and detail map stay in sync.
  @State private var lineOfSightViewModel = LineOfSightViewModel()

  // Shared Settings state, mirroring SettingsView's regular-width path.
  @State private var settingsShowingDeviceSelection = false
  @State private var settingsDemoModeManager = DemoModeManager.shared

  private static let lineOfSightPanelWidthMin: CGFloat = 380
  private static let lineOfSightPanelWidthIdeal: CGFloat = 440
  private static let lineOfSightPanelWidthMax: CGFloat = 560

  // sidebarColumnWidth is pinned on AppSidebar in both shell shapes (via navigationSplitViewColumnWidth)
  // so the sidebar renders at exactly this width whether the section is two- or three-column. Without
  // the pin the two-column Map shell gives its sidebar a wider system default than the three-column
  // list shell, so the sidebar visibly jumps width when switching to Map. detailColumnApproxMinWidth is
  // the detail-column floor feeding the tiling breakpoint below: an approximation of the width the detail
  // column settles at on our supported iPads, validated on device. Both are `nonisolated` because
  // sidebarTileableMinWidth's nonisolated initializer reads them.
  nonisolated static let sidebarColumnWidth: CGFloat = 64
  nonisolated static let detailColumnApproxMinWidth: CGFloat = 320

  /// Content-column floor feeding the tiling breakpoint: the widest minimum any section's content
  /// column takes, so the breakpoint clears for every section. A deliberate, team-owned value kept as
  /// its own constant, not a reuse of lineOfSightPanelWidthMin, so retuning the Line of Sight panel for
  /// comfort cannot shift when the sidebar tiles. `nonisolated` because sidebarTileableMinWidth's
  /// nonisolated initializer reads it.
  nonisolated static let contentColumnTileableMinWidth: CGFloat = 380

  /// Three columns tile only when the container is at least the sum of the three column widths: the
  /// pinned icon sidebar plus a content column at its min plus detail at its min. Below it the system
  /// overlays the sidebar, so the not-wide branch keeps collapse-on-selection rather than leaving a
  /// sidebar overlay on a too-narrow container. `nonisolated` so the @Sendable onGeometryChange
  /// transform can read it; a main-actor-isolated static is not referenceable from a Sendable closure.
  nonisolated static let sidebarTileableMinWidth: CGFloat =
    sidebarColumnWidth + contentColumnTileableMinWidth + detailColumnApproxMinWidth

  /// Pure mapping from container width and section to the desired sidebar visibility: wide tiles the
  /// sidebar (`.all`), narrow collapses to the section's hidden shape, and a sidebar-collapsing tool
  /// overrides both. `nonisolated` so the @Sendable-adjacent geometry action and the layout test can
  /// both call it.
  nonisolated static func sidebarVisibility(
    isWide: Bool,
    toolCollapsesSidebar: Bool,
    sectionCollapsed: NavigationSplitViewVisibility
  ) -> NavigationSplitViewVisibility {
    if toolCollapsesSidebar { return sectionCollapsed }
    return isWide ? .all : sectionCollapsed
  }

  private var selectedTab: AppTab {
    AppTab(rawValue: appState.navigation.selectedTab) ?? .chats
  }

  /// True while the Tools section is showing a tool whose `prefersCollapsedSidebar` is set.
  private var isSidebarCollapsingToolOpen: Bool {
    selectedTab == .tools && appState.navigation.selectedTool?.prefersCollapsedSidebar == true
  }

  private func desiredSidebarVisibility(isWide: Bool) -> NavigationSplitViewVisibility {
    Self.sidebarVisibility(
      isWide: isWide,
      toolCollapsesSidebar: isSidebarCollapsingToolOpen,
      sectionCollapsed: selectedTab.collapsedSidebarVisibility
    )
  }

  /// Width override for the Tools content column: applied only when the Line of Sight analysis panel
  /// is open, where the column must widen to fit the RF figures. In the plain tool-list state this is
  /// nil so the column inherits the system-default content width, matching the Chats/Nodes/Settings
  /// columns, which set no width modifier.
  private var toolsContentWidth: (min: CGFloat, ideal: CGFloat, max: CGFloat)? {
    appState.navigation.selectedTool == .lineOfSight
      ? (Self.lineOfSightPanelWidthMin, Self.lineOfSightPanelWidthIdeal, Self.lineOfSightPanelWidthMax)
      : nil
  }

  var body: some View {
    shell
      // Width is read at the shell because a content column misreports horizontalSizeClass as
      // .compact on regular iPad. The transform stays the bare comparison (allocation-free); the
      // action fires once on first layout and once per threshold crossing (the Bool dedups
      // intermediate resize frames). This action is the single width-driven writer of
      // columnVisibility and runs on first layout, so visibility is decided once measurement exists.
      .onGeometryChange(for: Bool.self) { proxy in
        proxy.size.width >= Self.sidebarTileableMinWidth
      } action: { isWide in
        appState.navigation.isSidebarWide = isWide
        // Wide reveals the tiled sidebar; narrow collapses to the section's hidden shape so a
        // too-narrow container shows a single column rather than a sidebar overlay. selectedTab
        // is read live so the collapsed shape matches the currently mounted shell.
        columnVisibility = desiredSidebarVisibility(isWide: isWide)
      }
      .navigationSplitViewStyle(.balanced)
      .themedChrome(theme)
      .syncingPillOverlay(onDisconnectedTap: { showingDeviceSelection = true })
      .onChange(of: appState.navigation.selectedTab) { _, newValue in
        clearToolSelectionWhenLeavingTools()
        let newTab = AppTab(rawValue: newValue) ?? .chats
        // Backstop for programmatic tab changes (deep links, notification taps) that bypass the
        // sidebar's selection setter. Width-gated and idempotent so it never re-collapses a wide
        // sidebar or duplicates a write the setter already made.
        if !appState.navigation.isSidebarWide, columnVisibility != newTab.collapsedSidebarVisibility {
          columnVisibility = newTab.collapsedSidebarVisibility
        }
        // The collapse drops the tapped sidebar row, so steer VoiceOver into the new content.
        focusedColumn = newTab
        // The radio sits in the section toolbar on every tab, so donate the tip when one waits.
        if appState.navigation.pendingDeviceMenuTipDonation {
          Task {
            await appState.donateDeviceMenuTip()
          }
        }
      }
      .onChange(of: appState.navigation.selectedTool) { _, _ in
        // A tool's sidebar preference can change the desired visibility, so re-derive it whenever
        // the selected tool changes.
        columnVisibility = desiredSidebarVisibility(isWide: appState.navigation.isSidebarWide)
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
          .navigationSplitViewColumnWidth(Self.sidebarColumnWidth)
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
          .navigationSplitViewColumnWidth(Self.sidebarColumnWidth)
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
        .modifier(OptionalColumnWidth(width: toolsContentWidth))
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

/// Applies a content-column width override only when one is supplied, leaving the column at the
/// system default otherwise. The override can't be expressed as a fixed width in the default state,
/// so it is applied conditionally: toggling it re-identifies the wrapped column and the width snaps
/// rather than animating, which is acceptable because the only caller swaps the column's content on
/// the same toggle and drives it from external state.
private struct OptionalColumnWidth: ViewModifier {
  let width: (min: CGFloat, ideal: CGFloat, max: CGFloat)?

  func body(content: Content) -> some View {
    if let width {
      content.navigationSplitViewColumnWidth(min: width.min, ideal: width.ideal, max: width.max)
    } else {
      content
    }
  }
}

#Preview {
  MainSidebarView()
    .environment(\.appState, AppState())
}
