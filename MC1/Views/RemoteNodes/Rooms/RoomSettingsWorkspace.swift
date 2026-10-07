import CoreLocation
import MC1Services
import SwiftUI

struct RoomSettingsWorkspace: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @FocusState private var focusedField: NodeSettingsField?

  let session: RemoteNodeSessionDTO
  @Bindable var viewModel: RoomSettingsViewModel
  @Bindable var statusViewModel: RoomStatusViewModel
  @Bindable var cliViewModel: NodeCLIViewModel
  @State private var managementTab: NodeManagementTab = .settings
  @State private var showRebootConfirmation = false
  @State private var showingLocationPicker = false
  @State private var telemetryConfigured = false

  var body: some View {
    // ZStack keeps the navigation title on one view across segment switches.
    // Group would re-host the title on each branch and animate a nav-bar transition.
    ZStack {
      switch managementTab {
      case .settings: settingsForm
      case .cli: NodeCLIView(viewModel: cliViewModel)
      case .telemetry:
        RoomStatusContent(
          viewModel: statusViewModel,
          session: session,
          connectionState: appState.connectionState,
          connectedDeviceID: appState.connectedDevice?.radioID
        )
      }
    }
    .animation(nil, value: managementTab)
    .navigationTitle(L10n.RemoteNodes.RemoteNodes.RoomSettings.title)
    .navigationBarTitleDisplayMode(.inline)
    .safeAreaInset(edge: .top, spacing: 0) {
      if session.isAdmin {
        NodeManagementTabPicker(selection: $managementTab)
          .frame(maxWidth: .infinity)
          .pinnedFilterHeaderBackground(theme)
      }
    }
    .onAppear {
      viewModel.helper.noteSettingsAppeared()
      statusViewModel.noteTelemetryVisitAppeared()
    }
    .onDisappear {
      viewModel.helper.noteSettingsDisappeared()
      statusViewModel.noteTelemetryVisitDisappeared()
    }
    .settingsExitGuard(
      hasUncommittedSettingsEdits: viewModel.hasUncommittedSettingsEdits,
      isApplying: viewModel.helper.isApplying.inFlight
        || viewModel.isApplyingRoomAccess.inFlight
        || viewModel.isApplyingBehavior.inFlight,
      errorMessage: viewModel.helper.errorMessage,
      revertUncommittedSettingsEdits: { viewModel.revertUncommittedSettingsEdits() }
    )
    .task {
      await viewModel.configure(
        roomAdminService: { appState.services?.roomAdminService },
        session: session
      )
      if let send = viewModel.makeNodeCLISendClosure(session: session) {
        cliViewModel.configure(sessionName: session.name, sendRawCommand: send)
      }
    }
    .task(id: RemoteAdminWorkspaces.RebindID(
      servicesVersion: appState.servicesVersion,
      generation: appState.remoteAdminWorkspaces.generation
    )) {
      await viewModel.rebind(
        roomAdminService: { appState.services?.roomAdminService },
        session: session
      )
      if let send = viewModel.makeNodeCLISendClosure(session: session) {
        cliViewModel.configure(sessionName: session.name, sendRawCommand: send)
      }
      guard telemetryConfigured else { return }
      await statusViewModel.rebind(
        roomAdminService: { appState.services?.roomAdminService },
        contactService: { appState.services?.contactService },
        nodeSnapshotService: { appState.services?.nodeSnapshotService }
      )
    }
    .onChange(of: managementTab) { _, newTab in
      guard newTab == .telemetry, !telemetryConfigured else { return }
      telemetryConfigured = true
      // First Telemetry reveal registers status handlers without replacing the settings CLI handler.
      // `telemetryConfigured` blocks a second registration when a segment switch recreates this subtree.
      statusViewModel.configure(
        roomAdminService: { appState.services?.roomAdminService },
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
      RoomAccessSection(viewModel: viewModel, focusedField: $focusedField)
      NodeRadioSettingsSection(
        settings: viewModel.helper,
        focusedField: $focusedField,
        radioRestartWarning: L10n.RemoteNodes.RemoteNodes.RoomSettings.radioRestartWarning
      )
      RoomBehaviorSection(viewModel: viewModel, focusedField: $focusedField)
      RemoteNodeIdentitySection(
        settings: viewModel.helper,
        focusedField: $focusedField,
        onPickLocation: { showingLocationPicker = true }
      )
      NodeContactInfoSection(settings: viewModel.helper, focusedField: $focusedField)
      NodeSecuritySection(settings: viewModel.helper)
      NodeDeviceInfoSection(settings: viewModel.helper)
      NodeActionsSection(
        settings: viewModel.helper,
        showRebootConfirmation: $showRebootConfirmation,
        rebootConfirmTitle: L10n.RemoteNodes.RemoteNodes.RoomSettings.rebootConfirmTitle,
        rebootMessage: L10n.RemoteNodes.RemoteNodes.RoomSettings.rebootMessage
      )
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
}

// MARK: - Room Access Section

extension RoomSettingsWorkspace {
  struct RoomAccessSection: View {
    @Bindable var viewModel: RoomSettingsViewModel
    var focusedField: FocusState<NodeSettingsField?>.Binding

    var body: some View {
      ExpandableSettingsSection(
        title: L10n.RemoteNodes.RemoteNodes.RoomSettings.roomSettingsSection,
        icon: "person.badge.key",
        isExpanded: $viewModel.isRoomAccessExpanded,
        isLoaded: { viewModel.roomAccessLoaded },
        isLoading: $viewModel.isLoadingRoomAccess,
        hasError: $viewModel.roomAccessError,
        onLoad: { await viewModel.fetchRoomAccess() },
        footer: L10n.RemoteNodes.RemoteNodes.RoomSettings.roomSettingsFooter
      ) {
        SecureField(L10n.RemoteNodes.RemoteNodes.RoomSettings.guestPassword, text: Binding(
          get: { viewModel.guestPassword ?? "" },
          set: { viewModel.guestPassword = $0 }
        ))
        .focused(focusedField, equals: .guestPassword)
        .disabled(viewModel.guestPassword == nil)
        .overlay(alignment: .trailing) {
          if viewModel.guestPassword == nil {
            SettingsLoadPlaceholder(isLoading: viewModel.isLoadingRoomAccess, hasError: viewModel.roomAccessError)
              .padding(.trailing, 8)
          }
        }

        Toggle(L10n.RemoteNodes.RemoteNodes.RoomSettings.allowReadOnly, isOn: Binding(
          get: { viewModel.allowReadOnly ?? false },
          set: { viewModel.allowReadOnly = $0 }
        ))
        .disabled(viewModel.allowReadOnly == nil)
        .accessibilityValue(
          viewModel.allowReadOnly == nil
            ? (viewModel.isLoadingRoomAccess ? L10n.RemoteNodes.RemoteNodes.Settings.loading : L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad)
            : (viewModel.allowReadOnly == true ? L10n.Localizable.Accessibility.on : L10n.Localizable.Accessibility.off)
        )
        .overlay(alignment: .trailing) {
          if viewModel.allowReadOnly == nil {
            SettingsLoadPlaceholder(isLoading: viewModel.isLoadingRoomAccess, hasError: viewModel.roomAccessError)
              .padding(.trailing, 60)
              .accessibilityHidden(true)
          }
        }

        Text(L10n.RemoteNodes.RemoteNodes.RoomSettings.allowReadOnlyFooter)
          .font(.caption)
          .foregroundStyle(.secondary)

        Button {
          Task { await viewModel.applyRoomAccess() }
        } label: {
          AsyncActionLabel(isLoading: viewModel.isApplyingRoomAccess.inFlight, showSuccess: viewModel.roomAccessApplySuccess) {
            Text(L10n.RemoteNodes.RemoteNodes.RoomSettings.applyRoomSettings)
              .foregroundStyle(viewModel.roomAccessModified ? Color.accentColor : .secondary)
              .transition(.opacity)
          }
        }
        .disabled(viewModel.isApplyingRoomAccess.inFlight || viewModel.roomAccessApplySuccess || !viewModel.roomAccessModified)
      }
    }
  }

  // MARK: - Room Behavior Section

  struct RoomBehaviorSection: View {
    @Bindable var viewModel: RoomSettingsViewModel
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
        footer: L10n.RemoteNodes.RemoteNodes.RoomSettings.behaviorFooter
      ) {
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
          AsyncActionLabel(isLoading: viewModel.isApplyingBehavior.inFlight, showSuccess: viewModel.behaviorApplySuccess) {
            Text(L10n.RemoteNodes.RemoteNodes.Settings.applyBehaviorSettings)
              .foregroundStyle(viewModel.behaviorModified ? Color.accentColor : .secondary)
              .transition(.opacity)
          }
        }
        .disabled(viewModel.isApplyingBehavior.inFlight || viewModel.behaviorApplySuccess || !viewModel.behaviorModified)
      }
    }
  }
}
