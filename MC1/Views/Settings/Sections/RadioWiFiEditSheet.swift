import SwiftUI

struct RadioWiFiEditSheet: View {
  @Environment(\.appTheme) private var theme
  @Bindable var viewModel: RadioWiFiViewModel
  @State private var passwordVisible = false

  var body: some View {
    NavigationStack {
      Form {
        Section {
          TextField(L10n.Settings.RadioWiFi.networkName, text: $viewModel.ssidField)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .disabled(viewModel.isBusy)
          passwordField
        } footer: {
          Text(viewModel.editorFooter)
        }
      }
      .themedCanvas(theme)
      .navigationTitle(L10n.Settings.RadioWiFi.network)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) {
            viewModel.editorOpen = false
          }
          .disabled(viewModel.isWriting)
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.save) {
            Task { await viewModel.requestSave() }
          }
          .disabled(!viewModel.canSave)
        }
      }
      .task {
        passwordVisible = false
        await viewModel.loadEditor()
      }
      .alert(viewModel.linkWarningTitle, isPresented: $viewModel.showingWiFiWarning) {
        Button(L10n.Localizable.Common.cancel, role: .cancel) {
          viewModel.cancelWiFiWarning()
        }
        Button(viewModel.linkWarningConfirmTitle) {
          Task { await viewModel.continuePastWiFiWarning() }
        }
      } message: {
        Text(L10n.Settings.RadioWiFi.Alert.WifiTransport.message)
      }
      .errorAlert($viewModel.errorMessage)
      .interactiveDismissDisabled(viewModel.isWriting)
    }
  }

  private var passwordField: some View {
    HStack {
      Group {
        if passwordVisible {
          TextField(L10n.Settings.RadioWiFi.password, text: $viewModel.passwordField)
        } else {
          SecureField(L10n.Settings.RadioWiFi.password, text: $viewModel.passwordField)
        }
      }
      .textInputAutocapitalization(.never)
      .autocorrectionDisabled()
      .disabled(viewModel.isBusy)

      Button {
        passwordVisible.toggle()
      } label: {
        Image(systemName: passwordVisible ? "eye.slash" : "eye")
          .foregroundStyle(.secondary)
      }
      .buttonStyle(.borderless)
      .accessibilityLabel(
        passwordVisible
          ? L10n.Settings.RadioWiFi.hidePassword
          : L10n.Settings.RadioWiFi.showPassword
      )
    }
  }
}
