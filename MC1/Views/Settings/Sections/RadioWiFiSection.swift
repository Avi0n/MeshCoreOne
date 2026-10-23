import SwiftUI

/// Radio WiFi rows. Shown once a load finds a WiFi build.
struct RadioWiFiSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Bindable var viewModel: RadioWiFiViewModel

  var body: some View {
    Group {
      Section {
        Toggle(L10n.Settings.RadioWiFi.enabled, isOn: Binding(
          get: { viewModel.wifiEnabled },
          set: { enabled in
            Task { await viewModel.setWiFiEnabled(enabled) }
          }
        ))
        .disabled(!viewModel.canRunCommands)

        if viewModel.rebootPending {
          Button(L10n.Settings.RadioWiFi.rebootToApply) {
            viewModel.requestReboot()
          }
          .disabled(!viewModel.canRunCommands)
        }

        Button {
          viewModel.editorOpen = true
        } label: {
          HStack {
            Text(L10n.Settings.RadioWiFi.network)
            Spacer()
            Text(viewModel.networkName ?? L10n.Settings.RadioWiFi.Network.notSet)
              .foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
              .font(.caption)
              .foregroundStyle(.tertiary)
          }
        }
        .foregroundStyle(.primary)
        .disabled(!viewModel.canEditNetwork)

        LabeledContent(
          L10n.Settings.RadioWiFi.status,
          value: viewModel.statusConnected
            ? L10n.Settings.RadioWiFi.Status.connected
            : L10n.Settings.RadioWiFi.Status.notConnected
        )

        if let ipAddress = viewModel.ipAddress {
          LabeledContent(L10n.Settings.RadioWiFi.ipAddress, value: ipAddress)
        }
      } header: {
        Text(L10n.Settings.Connection.Link.radioWiFi)
      } footer: {
        Text(L10n.Settings.RadioWiFi.footer)
      }
      .themedRowBackground(theme)

      Section {
        Button(L10n.Settings.RadioWiFi.clear, role: .destructive) {
          viewModel.requestClear()
        }
        .disabled(!viewModel.canClear || appState.connectionLinkSwitch?.target != nil)
      }
      .themedRowBackground(theme)
    }
    .alert(viewModel.linkWarningTitle, isPresented: Binding(
      get: { viewModel.showingWiFiWarning && !viewModel.editorOpen },
      set: { if !$0 { viewModel.showingWiFiWarning = false } }
    )) {
      Button(L10n.Localizable.Common.cancel, role: .cancel) {
        viewModel.cancelWiFiWarning()
      }
      Button(viewModel.linkWarningConfirmTitle) {
        Task { await viewModel.continuePastWiFiWarning() }
      }
    } message: {
      Text(L10n.Settings.RadioWiFi.Alert.WifiTransport.message)
    }
    .alert(L10n.Settings.RadioWiFi.Alert.Clear.title, isPresented: $viewModel.showingClearConfirm) {
      Button(L10n.Localizable.Common.cancel, role: .cancel) {}
      Button(L10n.Settings.RadioWiFi.clear, role: .destructive) {
        Task { await viewModel.confirmClear() }
      }
    } message: {
      Text(viewModel.clearMessage)
    }
    .alert(L10n.Settings.RadioWiFi.Alert.Reboot.title, isPresented: $viewModel.showingRebootConfirm) {
      Button(L10n.Localizable.Common.cancel, role: .cancel) {
        viewModel.cancelReboot()
      }
      Button(L10n.Settings.DeviceActions.Alert.Reboot.confirm) {
        Task { await viewModel.confirmReboot() }
      }
    } message: {
      Text(viewModel.rebootMessage)
    }
  }
}
