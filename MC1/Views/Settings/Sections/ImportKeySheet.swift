import MC1Services
import SwiftUI

/// Sheet for importing an existing Ed25519 private key onto the device
struct ImportKeySheet: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  @State private var viewModel = ImportKeyViewModel()

  var body: some View {
    NavigationStack {
      Form {
        explanationSection
          .themedRowBackground(theme)
        keyInputSection
          .themedRowBackground(theme)
        importSection
          .themedRowBackground(theme)
      }
      .themedCanvas(theme)
      .navigationTitle(L10n.Settings.ImportKey.Sheet.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) {
            dismiss()
          }
          .disabled(viewModel.isImporting)
        }
      }
      .interactiveDismissDisabled(viewModel.isImporting)
      .alert(
        L10n.Settings.RegenerateIdentity.Alert.Replace.title,
        isPresented: $viewModel.showingReplaceAlert
      ) {
        Button(L10n.Localizable.Common.cancel, role: .cancel) {}
        Button(L10n.Settings.RegenerateIdentity.Alert.Replace.confirm, role: .destructive) {
          Task {
            if await viewModel.importKey() {
              dismiss()
            }
          }
        }
      } message: {
        Text(L10n.Settings.RegenerateIdentity.Alert.Replace.message)
      }
      .errorAlert($viewModel.errorMessage)
      .sensoryFeedback(.success, trigger: viewModel.successTrigger)
      .task {
        viewModel.configure(settingsService: { appState.services?.settingsService })
      }
    }
  }

  // MARK: - Sections

  private var explanationSection: some View {
    Section {
      Text(L10n.Settings.ImportKey.Sheet.explanation)
        .foregroundStyle(.secondary)
    }
  }

  private var keyInputSection: some View {
    Section {
      TextField(L10n.Settings.ImportKey.KeyInput.placeholder, text: $viewModel.hexInput, axis: .vertical)
        .textInputAutocapitalization(.characters)
        .autocorrectionDisabled()
        .font(.system(.body, design: .monospaced))
        .lineLimit(3...6)
        .onChange(of: viewModel.hexInput) { _, newValue in
          viewModel.sanitizeInput(newValue)
        }
    } header: {
      Text(L10n.Settings.ImportKey.KeyInput.label)
    }
  }

  private var importSection: some View {
    Section {
      Button {
        viewModel.validateAndConfirm()
      } label: {
        HStack {
          Spacer()
          if viewModel.isImporting {
            ProgressView()
              .controlSize(.small)
            Text(L10n.Settings.ImportKey.importing)
          } else {
            Text(L10n.Settings.ImportKey.import)
          }
          Spacer()
        }
      }
      .disabled(viewModel.isImporting || viewModel.hexInput.isEmpty)
    }
  }
}
