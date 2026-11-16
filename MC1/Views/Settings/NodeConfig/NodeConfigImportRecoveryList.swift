import MC1Services
import SwiftUI

struct NodeConfigImportRecoveryList: View {
  @Environment(\.appTheme) private var theme
  @Bindable var viewModel: NodeConfigImportViewModel
  let recovery: IdentityImportRecovery

  var body: some View {
    List {
      Section {
        Text(recovery.sourceName)
        Text(recovery.targetDescription)
          .font(.footnote)
        if !viewModel.identityRecoveryComplete {
          Label(L10n.Settings.ConfigImport.privateKeyWarning, systemImage: "exclamationmark.shield")
            .foregroundStyle(.orange)
        }
      }
      .themedRowBackground(theme)

      if viewModel.identityRecoveryComplete {
        Section {
          Label(L10n.Settings.ConfigImport.identityRecoveryComplete, systemImage: "checkmark.circle.fill")
        }
        .themedRowBackground(theme)
      } else if viewModel.isApplying {
        Section {
          ProgressView()
          Button(L10n.Localizable.Common.cancel, role: .cancel) { viewModel.cancelImport() }
        }
        .themedRowBackground(theme)
      } else {
        Section {
          Button(L10n.Settings.ConfigImport.selectFile) { viewModel.showFilePicker = true }
            .disabled(viewModel.isParsing)
          if viewModel.isParsing { ProgressView() }
          Button(L10n.Settings.ConfigImport.retryIdentityImport) { viewModel.prepareConfirmation() }
            .disabled(viewModel.isParsing)
        }
        .themedRowBackground(theme)
      }

      if let errorMessage = viewModel.errorMessage {
        Section {
          Label(errorMessage, systemImage: "exclamationmark.triangle")
            .foregroundStyle(.red)
        }
        .themedRowBackground(theme)
      }
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.ConfigImport.retryIdentityImport)
  }
}
