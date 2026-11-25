import MC1Services
import SwiftUI

struct RxLogView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  @State private var viewModel = RxLogViewModel()
  @State private var showClearConfirmation = false

  /// Points past the resting top before the list counts as scrolled away.
  private static let scrolledAwayThreshold: CGFloat = 8

  private var isWorkspaceActive: Bool {
    appState.navigation.isToolWorkspaceActive(.rxLog)
  }

  private var isConnected: Bool {
    appState.services?.rxLogService != nil
  }

  private var hopLabels: RxLogHopLabels {
    RxLogHopLabels(
      localPublicKeyPrefix: appState.connectedDevice?.publicKeyPrefix,
      nodeNames: viewModel.nodeNames
    )
  }

  var body: some View {
    @Bindable var viewModel = viewModel
    let visible = viewModel.visibleEntries
    screen(visible: visible)
      .navigationTitle(L10n.Tools.Tools.rxLog)
      .searchable(text: searchText, prompt: L10n.Tools.Tools.RxLog.searchPrompt)
      .toolbar { toolbarContent }
      .sheet(item: $viewModel.selectedEntry) { entry in
        RxLogEntryDetailView(entry: entry, viewModel: viewModel, labels: hopLabels)
      }
      .task(id: appState.servicesVersion) {
        guard isWorkspaceActive else { return }
        viewModel.configure(
          rxLogService: { [appState] in appState.services?.rxLogService },
          dataStore: { [appState] in appState.services?.dataStore },
          radioID: { [appState] in appState.currentRadioID }
        )
        await viewModel.subscribe()
        await viewModel.loadNodeNames()
      }
      .onChange(of: appState.contactsVersion) {
        Task { await viewModel.loadNodeNames() }
      }
      .onChange(of: isWorkspaceActive) { _, isActive in
        if isActive {
          Task { await viewModel.subscribe() }
        } else {
          viewModel.unsubscribe()
        }
      }
  }

  @ViewBuilder
  private func screen(visible: [RxLogEntryDTO]) -> some View {
    if appState.services?.rxLogService == nil {
      disconnectedState
    } else if viewModel.entries.isEmpty {
      emptyState
    } else if visible.isEmpty {
      filteredEmptyState
    } else {
      entryList(visible)
    }
  }

  private var emptyState: some View {
    ContentUnavailableView {
      Label(L10n.Tools.Tools.RxLog.listening, systemImage: "antenna.radiowaves.left.and.right")
    } description: {
      Text(L10n.Tools.Tools.RxLog.listeningDescription)
    }
  }

  private var disconnectedState: some View {
    ContentUnavailableView {
      Label(L10n.Tools.Tools.RxLog.notConnected, systemImage: "antenna.radiowaves.left.and.right.slash")
    } description: {
      Text(L10n.Tools.Tools.RxLog.notConnectedDescription)
    }
  }

  private var filteredEmptyState: some View {
    Group {
      if viewModel.searchText.trimmingCharacters(in: .whitespaces).isEmpty {
        ContentUnavailableView {
          Label(L10n.Tools.Tools.RxLog.noMatchingPackets, systemImage: "line.3.horizontal.decrease.circle")
        } description: {
          Text(L10n.Tools.Tools.RxLog.noMatchingPacketsDescription)
        } actions: {
          Button(L10n.Tools.Tools.RxLog.clearFilters) {
            viewModel.clearFilters()
          }
        }
      } else {
        ContentUnavailableView.search(text: viewModel.searchText)
      }
    }
  }

  private func entryList(_ visible: [RxLogEntryDTO]) -> some View {
    ScrollViewReader { proxy in
      List {
        Section {
          ForEach(visible, id: \.id) { entry in
            RxLogRowView(
              entry: entry,
              groupCount: viewModel.filter.groupsArrivals
                ? viewModel.groupCounts[entry.packetHash, default: 1]
                : 1,
              labels: hopLabels,
              onSelect: { viewModel.selectedEntry = entry },
              onHideType: { viewModel.filter.setHidden(true, for: entry.payloadType) }
            )
            .id(entry.id)
          }
        }
        .themedRowBackground(theme)
      }
      .listStyle(.insetGrouped)
      .themedCanvas(theme)
      .onScrollGeometryChange(for: Bool.self) { geometry in
        geometry.contentOffset.y + geometry.contentInsets.top > Self.scrolledAwayThreshold
      } action: { _, scrolledAway in
        viewModel.setScrolledAway(scrolledAway)
      }
      .safeAreaInset(edge: .top, spacing: 8) {
        RxLogStatusBar(
          viewModel: viewModel,
          shownCount: visible.count,
          isConnected: isConnected,
          onJumpToTop: {
            guard let id = viewModel.visibleEntries.first?.id else { return }
            proxy.scrollTo(id, anchor: .top)
          }
        )
      }
    }
  }

  @ToolbarContentBuilder
  private var toolbarContent: some ToolbarContent {
    ToolbarItem(placement: .topBarTrailing) {
      filterMenu
    }
    ToolbarItem(placement: .topBarTrailing) {
      overflowMenu
    }
  }

  private var filterMenu: some View {
    Menu {
      Picker(L10n.Tools.Tools.RxLog.routeType, selection: routeSelection) {
        ForEach(RxLogFilter.RouteFilter.allCases, id: \.self) { route in
          Text(route.displayName).tag(route)
        }
      }
      .pickerStyle(.inline)

      Picker(L10n.Tools.Tools.RxLog.decryptStatus, selection: decryptSelection) {
        ForEach(RxLogFilter.DecryptFilter.allCases, id: \.self) { decrypt in
          Text(decrypt.displayName).tag(decrypt)
        }
      }
      .pickerStyle(.inline)

      Section(L10n.Tools.Tools.RxLog.packetType) {
        ForEach(PayloadType.allCases, id: \.self) { type in
          Toggle(type.displayName, isOn: shownBinding(for: type))
        }
      }

      Toggle(L10n.Tools.Tools.RxLog.groupDuplicates, isOn: groupsArrivals)
    } label: {
      Label(
        L10n.Tools.Tools.RxLog.filter,
        systemImage: viewModel.filter.hidesPackets
          ? "line.3.horizontal.decrease.circle.fill"
          : "line.3.horizontal.decrease.circle"
      )
    }
    .liquidGlassSecondaryButtonStyle()
  }

  private var overflowMenu: some View {
    Menu {
      Button(role: .destructive) {
        showClearConfirmation = true
      } label: {
        Label(L10n.Tools.Tools.RxLog.deleteLogs, systemImage: "trash")
      }
    } label: {
      Label(L10n.Tools.Tools.RxLog.more, systemImage: "ellipsis.circle")
    }
    .liquidGlassSecondaryButtonStyle()
    .confirmationDialog(
      L10n.Tools.Tools.RxLog.deleteConfirmation,
      isPresented: $showClearConfirmation,
      titleVisibility: .visible
    ) {
      Button(L10n.Tools.Tools.RxLog.delete, role: .destructive) {
        Task { await viewModel.clearLog() }
      }
    }
  }

  private var searchText: Binding<String> {
    Binding(
      get: { viewModel.searchText },
      set: { viewModel.searchText = $0 }
    )
  }

  private var routeSelection: Binding<RxLogFilter.RouteFilter> {
    Binding(
      get: { viewModel.filter.route },
      set: { viewModel.filter.route = $0 }
    )
  }

  private var decryptSelection: Binding<RxLogFilter.DecryptFilter> {
    Binding(
      get: { viewModel.filter.decrypt },
      set: { viewModel.filter.decrypt = $0 }
    )
  }

  private var groupsArrivals: Binding<Bool> {
    Binding(
      get: { viewModel.filter.groupsArrivals },
      set: { viewModel.filter.groupsArrivals = $0 }
    )
  }

  private func shownBinding(for type: PayloadType) -> Binding<Bool> {
    Binding(
      get: { !viewModel.filter.isHidden(type) },
      set: { isShown in viewModel.filter.setHidden(!isShown, for: type) }
    )
  }
}

#Preview {
  NavigationStack {
    RxLogView()
  }
  .environment(\.appState, AppState())
}
