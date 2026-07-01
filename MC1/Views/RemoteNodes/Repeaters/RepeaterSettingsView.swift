import CoreLocation
import MC1Services
import SwiftUI

struct RepeaterSettingsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @FocusState private var focusedField: NodeSettingsField?

  let session: RemoteNodeSessionDTO
  @State private var viewModel = RepeaterSettingsViewModel()
  @State private var statusViewModel = RepeaterStatusViewModel()
  @State private var managementTab: NodeManagementTab = .settings
  @State private var cliViewModel = NodeCLIViewModel()
  @State private var showRebootConfirmation = false
  @State private var showingLocationPicker = false
  @State private var telemetryConfigured = false
  @State private var contacts: [ContactDTO] = []
  @State private var discoveredNodes: [DiscoveredNodeDTO] = []
  /// The node's contact, kept live so the route section reflects the path the firmware learns after
  /// a flood login (delivered asynchronously as a contact update).
  @State private var routeContact: ContactDTO?

  var body: some View {
    // ZStack, not Group: a stable container keeps the navigation title hosted on one
    // view across segment switches. Group would re-host it on each branch, animating
    // a nav-bar item transition.
    ZStack {
      switch managementTab {
      case .settings: settingsForm
      case .cli: NodeCLIView(viewModel: cliViewModel)
      case .telemetry:
        RepeaterStatusContent(
          viewModel: statusViewModel,
          session: session,
          connectionState: appState.connectionState,
          contacts: contacts,
          discoveredNodes: discoveredNodes,
          userLocation: appState.bestAvailableLocation,
          connectedDeviceID: appState.connectedDevice?.radioID,
          routePathContact: routeContact
        )
      }
    }
    .animation(nil, value: managementTab)
    .navigationTitle(L10n.RemoteNodes.RemoteNodes.Settings.title)
    .navigationBarTitleDisplayMode(.inline)
    .safeAreaInset(edge: .top, spacing: 0) {
      if session.isAdmin {
        NodeManagementTabPicker(selection: $managementTab)
          .frame(maxWidth: .infinity)
          .pinnedFilterHeaderBackground(theme)
      }
    }
    .task {
      await viewModel.configure(
        repeaterAdminService: { appState.services?.repeaterAdminService },
        session: session
      )
      if let send = viewModel.makeNodeCLISendClosure(session: session) {
        cliViewModel.configure(sessionName: session.name, sendRawCommand: send)
      }
      // Loaded up front (not just on Telemetry reveal) so the Settings-tab route section can
      // resolve hop hashes to repeater names.
      if let radioID = appState.connectedDevice?.radioID,
         let dataStore = appState.services?.dataStore {
        contacts = await (try? dataStore.fetchContacts(radioID: radioID)) ?? []
        discoveredNodes = await (try? dataStore.fetchDiscoveredNodes(radioID: radioID)) ?? []
      }
      await refreshRouteContact()
    }
    .onChange(of: appState.contactsVersion) {
      Task { await refreshRouteContact() }
    }
    .onChange(of: managementTab) { _, newTab in
      guard newTab == .telemetry, !telemetryConfigured else { return }
      telemetryConfigured = true
      // Configure the status VM on first Telemetry reveal rather than on open:
      // its handlers populate only the status/telemetry/neighbours slots, leaving the
      // settings VM's CLI handler intact for the Settings/CLI surface. Guarded by
      // telemetryConfigured because a segment switch recreates only the content subtree,
      // so this must not re-run or duplicate handler registration.
      statusViewModel.configure(
        repeaterAdminService: { appState.services?.repeaterAdminService },
        contactService: { appState.services?.contactService },
        nodeSnapshotService: { appState.services?.nodeSnapshotService }
      )
      Task {
        await statusViewModel.registerHandlers()
        if let radioID = appState.connectedDevice?.radioID {
          await statusViewModel.helper.loadOCVSettings(publicKey: session.publicKey, radioID: radioID)
        }
      }
    }
    .onDisappear {
      statusViewModel.stopDiscovery()
      Task {
        await statusViewModel.clearStatusHandlers()
        await viewModel.cleanup()
      }
    }
    .alert(L10n.RemoteNodes.RemoteNodes.Settings.success, isPresented: $viewModel.helper.showSuccessAlert) {
      Button(L10n.RemoteNodes.RemoteNodes.Settings.ok, role: .cancel) {}
    } message: {
      Text(viewModel.helper.successMessage ?? L10n.RemoteNodes.RemoteNodes.Settings.settingsApplied)
    }
    .sheet(isPresented: $showingLocationPicker) {
      LocationPickerView(
        initialCoordinate: CLLocationCoordinate2D(
          latitude: viewModel.helper.latitude ?? 0,
          longitude: viewModel.helper.longitude ?? 0
        )
      ) { coordinate in
        viewModel.helper.setLocationFromPicker(
          latitude: coordinate.latitude,
          longitude: coordinate.longitude
        )
      }
    }
  }

  private var settingsForm: some View {
    Form {
      NodeSettingsHeaderSection(publicKey: session.publicKey, name: session.name, role: session.role)
      makeRadioSettingsSection()
      makeBehaviorSection()
      makeRegionsSection()
      makeIdentitySection()
      makeContactInfoSection()
      makeSecuritySection()
      makeDeviceInfoSection()
      makeActionsSection()
      if let routeContact {
        NodeRoutePathSection(
          contact: routeContact,
          contacts: contacts,
          discoveredNodes: discoveredNodes,
          userLocation: appState.bestAvailableLocation
        )
      }
    }
    .themedCanvas(theme)
    .nodeManagementHeaderTopMargin()
    .toolbar {
      ToolbarItemGroup(placement: .keyboard) {
        Spacer()
        Button(L10n.RemoteNodes.RemoteNodes.Settings.done) {
          focusedField = nil
        }
      }
    }
  }

  // MARK: - Subviews

  private func makeDeviceInfoSection() -> some View {
    NodeDeviceInfoSection(settings: viewModel.helper)
  }

  private func makeRadioSettingsSection() -> some View {
    NodeRadioSettingsSection(
      settings: viewModel.helper,
      focusedField: $focusedField
    )
  }

  private func makeIdentitySection() -> some View {
    RemoteNodeIdentitySection(
      settings: viewModel.helper,
      focusedField: $focusedField,
      onPickLocation: { showingLocationPicker = true }
    )
  }

  private func makeContactInfoSection() -> some View {
    NodeContactInfoSection(settings: viewModel.helper, focusedField: $focusedField)
  }

  private func makeBehaviorSection() -> some View {
    BehaviorSection(viewModel: viewModel, focusedField: $focusedField)
  }

  private func makeRegionsSection() -> some View {
    RegionsSection(viewModel: viewModel)
  }

  private func makeSecuritySection() -> some View {
    NodeSecuritySection(settings: viewModel.helper)
  }

  private func makeActionsSection() -> some View {
    NodeActionsSection(
      settings: viewModel.helper,
      showRebootConfirmation: $showRebootConfirmation
    )
  }

  private func refreshRouteContact() async {
    guard let dataStore = appState.services?.dataStore else { return }
    if let updated = await (try? dataStore.fetchContact(
      radioID: session.radioID,
      publicKey: session.publicKey
    )).flatMap(\.self) {
      routeContact = updated
    }
  }
}

// MARK: - Behavior Section

private struct BehaviorSection: View {
  @Bindable var viewModel: RepeaterSettingsViewModel
  var focusedField: FocusState<NodeSettingsField?>.Binding

  var body: some View {
    ExpandableSettingsSection(
      title: L10n.RemoteNodes.RemoteNodes.Settings.behavior,
      icon: "slider.horizontal.3",
      isExpanded: $viewModel.isBehaviorExpanded,
      isLoaded: { viewModel.behaviorLoaded },
      isLoading: $viewModel.isLoadingBehavior,
      hasError: $viewModel.behaviorError,
      onLoad: { await viewModel.fetchBehaviorSettings() },
      footer: L10n.RemoteNodes.RemoteNodes.Settings.behaviorFooter
    ) {
      Toggle(L10n.RemoteNodes.RemoteNodes.Settings.repeaterMode, isOn: Binding(
        get: { viewModel.repeaterEnabled ?? false },
        set: { viewModel.repeaterEnabled = $0 }
      ))
      .disabled(viewModel.repeaterEnabled == nil)
      .accessibilityValue(
        viewModel.repeaterEnabled == nil
          ? (viewModel.isLoadingBehavior ? L10n.RemoteNodes.RemoteNodes.Settings.loading : L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad)
          : (viewModel.repeaterEnabled == true ? L10n.Localizable.Accessibility.on : L10n.Localizable.Accessibility.off)
      )
      .overlay(alignment: .trailing) {
        if viewModel.repeaterEnabled == nil {
          SettingsLoadPlaceholder(isLoading: viewModel.isLoadingBehavior, hasError: viewModel.behaviorError)
            .padding(.trailing, 60)
            .accessibilityHidden(true)
        }
      }

      HStack {
        Text(L10n.RemoteNodes.RemoteNodes.Settings.advertInterval0Hop)
        Spacer()
        if let interval = viewModel.advertIntervalMinutes {
          TextField(L10n.RemoteNodes.RemoteNodes.Settings.min, value: Binding(
            get: { interval },
            set: { viewModel.advertIntervalMinutes = $0 }
          ), format: .number)
            .keyboardType(.numberPad)
            .multilineTextAlignment(.trailing)
            .frame(width: 60)
            .focused(focusedField, equals: .advertInterval)
          Text(L10n.RemoteNodes.RemoteNodes.Settings.min)
            .foregroundStyle(.secondary)
        } else {
          SettingsLoadPlaceholder(isLoading: viewModel.isLoadingBehavior, hasError: viewModel.behaviorError)
        }
      }

      if let error = viewModel.advertIntervalError {
        Text(error)
          .font(.caption)
          .foregroundStyle(.red)
      }

      HStack {
        Text(L10n.RemoteNodes.RemoteNodes.Settings.advertIntervalFlood)
        Spacer()
        if let interval = viewModel.floodAdvertIntervalHours {
          TextField(L10n.RemoteNodes.RemoteNodes.Settings.hrs, value: Binding(
            get: { interval },
            set: { viewModel.floodAdvertIntervalHours = $0 }
          ), format: .number)
            .keyboardType(.numberPad)
            .multilineTextAlignment(.trailing)
            .frame(width: 60)
            .focused(focusedField, equals: .floodAdvertInterval)
          Text(L10n.RemoteNodes.RemoteNodes.Settings.hrs)
            .foregroundStyle(.secondary)
        } else {
          SettingsLoadPlaceholder(isLoading: viewModel.isLoadingBehavior, hasError: viewModel.behaviorError)
        }
      }

      if let error = viewModel.floodAdvertIntervalError {
        Text(error)
          .font(.caption)
          .foregroundStyle(.red)
      }

      HStack {
        Text(L10n.RemoteNodes.RemoteNodes.Settings.maxFloodHops)
        Spacer()
        if let hops = viewModel.floodMaxHops {
          TextField(L10n.RemoteNodes.RemoteNodes.Settings.hops, value: Binding(
            get: { hops },
            set: { viewModel.floodMaxHops = $0 }
          ), format: .number)
            .keyboardType(.numberPad)
            .multilineTextAlignment(.trailing)
            .frame(width: 60)
            .focused(focusedField, equals: .floodMaxHops)
          Text(L10n.RemoteNodes.RemoteNodes.Settings.hops)
            .foregroundStyle(.secondary)
        } else {
          SettingsLoadPlaceholder(isLoading: viewModel.isLoadingBehavior, hasError: viewModel.behaviorError)
        }
      }

      if let error = viewModel.floodMaxHopsError {
        Text(error)
          .font(.caption)
          .foregroundStyle(.red)
      }

      Button {
        Task { await viewModel.applyBehaviorSettings() }
      } label: {
        AsyncActionLabel(isLoading: viewModel.helper.isApplying, showSuccess: viewModel.behaviorApplySuccess) {
          Text(L10n.RemoteNodes.RemoteNodes.Settings.applyBehaviorSettings)
            .foregroundStyle(viewModel.behaviorSettingsModified ? Color.accentColor : .secondary)
            .transition(.opacity)
        }
      }
      .disabled(viewModel.helper.isApplying || viewModel.behaviorApplySuccess || !viewModel.behaviorSettingsModified)
    }
  }
}

// MARK: - Regions Section

private struct RegionsSection: View {
  @Bindable var viewModel: RepeaterSettingsViewModel

  /// Regions sorted: wildcard first, then alphabetical
  private var sortedRegions: [RepeaterRegionEntry] {
    viewModel.regions.sorted { lhs, rhs in
      if lhs.isWildcard { return true }
      if rhs.isWildcard { return false }
      return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
    }
  }

  /// Display name for a region entry
  private func displayName(for region: RepeaterRegionEntry) -> String {
    region.isWildcard
      ? L10n.RemoteNodes.RemoteNodes.Settings.Regions.allTrafficWildcard
      : region.name
  }

  var body: some View {
    ExpandableSettingsSection(
      title: L10n.RemoteNodes.RemoteNodes.Settings.regions,
      icon: "globe",
      isExpanded: $viewModel.isRegionsExpanded,
      isLoaded: { viewModel.regionsLoaded },
      isLoading: $viewModel.isLoadingRegions,
      hasError: $viewModel.regionsError,
      onLoad: { await viewModel.fetchRegions() },
      footer: L10n.RemoteNodes.RemoteNodes.Settings.regionsFooter
    ) {
      if viewModel.regionsLoaded, viewModel.regions.isEmpty {
        Text(L10n.RemoteNodes.RemoteNodes.Settings.Regions.empty)
          .foregroundStyle(.secondary)
      }

      // Home region picker
      if !viewModel.regions.isEmpty {
        Picker(L10n.RemoteNodes.RemoteNodes.Settings.Regions.homeRegion, selection: Binding(
          get: {
            viewModel.regions.first(where: \.isHome)?.name
              ?? RepeaterSettingsViewModel.wildcardName
          },
          set: { newValue in
            Task { await viewModel.setHomeRegion(name: newValue) }
          }
        )) {
          ForEach(sortedRegions) { region in
            Text(displayName(for: region))
              .tag(region.name)
          }
        }
        .pickerStyle(.menu)
        .tint(.primary)
      }

      // Region list with flood toggles
      ForEach(sortedRegions) { region in
        Toggle(
          displayName(for: region),
          isOn: Binding(
            get: { region.floodAllowed },
            set: { _ in
              Task { await viewModel.toggleRegionFlood(name: region.name) }
            }
          )
        )
        .accessibilityLabel(
          region.isWildcard
            ? L10n.RemoteNodes.RemoteNodes.Settings.Regions.allTraffic
            : region.name
        )
        .accessibilityHint(L10n.RemoteNodes.RemoteNodes.Settings.Regions.floodToggleHint)
        .disabled(viewModel.helper.isApplying)
      }
      .onDelete { offsets in
        let sorted = sortedRegions
        for offset in offsets {
          let region = sorted[offset]
          guard !region.isWildcard else { continue }
          Task { await viewModel.removeRegion(name: region.name) }
        }
      }

      // Add region button
      Button(L10n.RemoteNodes.RemoteNodes.Settings.Regions.addRegion, systemImage: "plus") {
        viewModel.isAddingRegion = true
      }
      .disabled(viewModel.helper.isApplying)

      // Save to device button
      if viewModel.regionsLoaded {
        Button {
          Task { await viewModel.saveRegions() }
        } label: {
          AsyncActionLabel(isLoading: viewModel.helper.isApplying, showSuccess: viewModel.regionsSaveSuccess) {
            Text(L10n.RemoteNodes.RemoteNodes.Settings.Regions.saveToDevice)
              .foregroundStyle(viewModel.hasUnsavedRegionChanges ? Color.accentColor : .secondary)
              .transition(.opacity)
          }
        }
        .disabled(viewModel.helper.isApplying || viewModel.regionsSaveSuccess || !viewModel.hasUnsavedRegionChanges)
      }
    }
    .alert(L10n.RemoteNodes.RemoteNodes.Settings.Regions.addRegionTitle, isPresented: $viewModel.isAddingRegion) {
      TextField(L10n.RemoteNodes.RemoteNodes.Settings.Regions.regionName, text: $viewModel.newRegionName)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
      Button(L10n.RemoteNodes.RemoteNodes.Settings.Regions.addRegion) {
        Task { await viewModel.addRegion(name: viewModel.newRegionName) }
      }
      Button(L10n.RemoteNodes.RemoteNodes.cancel, role: .cancel) {
        viewModel.newRegionName = ""
      }
    }
  }
}

#Preview {
  NavigationStack {
    RepeaterSettingsView(
      session: RemoteNodeSessionDTO(
        id: UUID(),
        radioID: UUID(),
        publicKey: Data(repeating: 0x42, count: 32),
        name: "Mountain Peak Repeater",
        role: .repeater,
        latitude: 37.7749,
        longitude: -122.4194,
        isConnected: true,
        permissionLevel: .admin
      )
    )
    .environment(\.appState, AppState())
  }
}
