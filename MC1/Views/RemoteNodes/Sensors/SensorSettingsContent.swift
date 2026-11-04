import MC1Services
import SwiftUI

struct SensorSettingsContent: View {
  @Environment(\.appTheme) private var theme
  @Bindable var viewModel: SensorSettingsViewModel
  let cliViewModel: NodeCLIViewModel
  @State private var tab: NodeManagementTab = .settings
  @State private var showRebootConfirmation = false
  @State private var reloadTask: Task<Void, Never>?

  var body: some View {
    ZStack {
      if tab == .cli {
        NodeCLIView(viewModel: cliViewModel)
          .disabled(!viewModel.isAuthorized || viewModel.isLoading || (viewModel.isMutating && !cliViewModel.isWaitingForResponse))
      } else {
        settingsForm
      }
    }
    .navigationTitle(L10n.RemoteNodes.RemoteNodes.Sensor.title)
    .navigationBarTitleDisplayMode(.inline)
    .safeAreaInset(edge: .top, spacing: 0) {
      NodeManagementTabPicker(selection: $tab, tabs: [.settings, .cli])
        .pinnedFilterHeaderBackground(theme)
    }
    .task(id: viewModel.isAuthorized) {
      if viewModel.isAuthorized { await viewModel.loadIfNeeded() }
    }
    .onDisappear { reloadTask?.cancel(); reloadTask = nil }
    .errorAlert($viewModel.errorMessage)
    .confirmationDialog(
      L10n.RemoteNodes.RemoteNodes.Sensor.rebootConfirmation,
      isPresented: $showRebootConfirmation,
      titleVisibility: .visible
    ) {
      Button(L10n.RemoteNodes.RemoteNodes.Settings.reboot, role: .destructive) {
        showRebootConfirmation = false
        viewModel.reboot()
      }
      Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
    }
  }

  private var settingsForm: some View {
    Form {
      if viewModel.isMutating {
        Section { ProgressView().frame(maxWidth: .infinity) }
      }
      if viewModel.requiresReload {
        Section { Text(L10n.Localizable.Error.RemoteNode.sensorOutcomeUnknown).foregroundStyle(.secondary) }
      }
      if let status = viewModel.statusMessage {
        Section { Text(status).foregroundStyle(.secondary) }
      }
      if !viewModel.isAuthorized {
        Section { Text(L10n.RemoteNodes.RemoteNodes.Sensor.adminRequired).foregroundStyle(.secondary) }
      }
      Section {
        if viewModel.isLoading {
          ProgressView()
            .frame(maxWidth: .infinity)
        } else if viewModel.hasLoaded, viewModel.settings.isEmpty {
          Text(L10n.RemoteNodes.RemoteNodes.Sensor.empty)
            .foregroundStyle(.secondary)
        }
        ForEach(viewModel.settings) { setting in
          VStack(alignment: .leading) {
            Text(setting.key)
              .font(.headline)
            TextField(L10n.RemoteNodes.RemoteNodes.Sensor.value, text: $viewModel[draft: setting.key])
              .textInputAutocapitalization(.never)
              .autocorrectionDisabled()
              .accessibilityLabel(setting.key)
            Button(L10n.RemoteNodes.RemoteNodes.Sensor.save) { viewModel.save(setting.key) }
              .disabled(!viewModel.canSave(setting.key))
          }
        }
        Button(L10n.RemoteNodes.RemoteNodes.Sensor.reload) {
          reloadTask = Task { await viewModel.reload() }
        }
        .disabled(!viewModel.canSend)
      } footer: {
        Text(L10n.RemoteNodes.RemoteNodes.Sensor.runtimeOnly)
      }
      Section {
        Button(L10n.RemoteNodes.RemoteNodes.Settings.reboot, role: .destructive) {
          showRebootConfirmation = true
        }
        .disabled(!viewModel.canSend)
      }
    }
    .themedCanvas(theme)
  }
}
