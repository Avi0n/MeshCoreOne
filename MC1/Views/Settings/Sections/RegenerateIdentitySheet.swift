import MC1Services
import SwiftUI

/// Sheet for generating a new Ed25519 identity and importing it to the device
struct RegenerateIdentitySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    @State private var viewModel = RegenerateIdentityViewModel()

    var body: some View {
        NavigationStack {
            Form {
                explanationSection
                    .themedRowBackground(theme)
                prefixSection
                    .themedRowBackground(theme)
                generateSection
                    .themedRowBackground(theme)
                if let generatedKey = viewModel.generatedKey {
                    keyPreviewSection(generatedKey)
                        .themedRowBackground(theme)
                    replaceSection
                        .themedRowBackground(theme)
                }
            }
            .themedCanvas(theme)
            .navigationTitle(L10n.Settings.RegenerateIdentity.Sheet.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Localizable.Common.cancel) {
                        dismiss()
                    }
                    .disabled(viewModel.isBusy)
                }
            }
            .interactiveDismissDisabled(viewModel.isBusy)
            .alert(
                L10n.Settings.RegenerateIdentity.Alert.Replace.title,
                isPresented: $viewModel.showingReplaceAlert
            ) {
                Button(L10n.Localizable.Common.cancel, role: .cancel) { }
                Button(L10n.Settings.RegenerateIdentity.Alert.Replace.confirm, role: .destructive) {
                    Task {
                        if await viewModel.replaceIdentity(settingsService: appState.services?.settingsService) {
                            dismiss()
                        }
                    }
                }
            } message: {
                Text(L10n.Settings.RegenerateIdentity.Alert.Replace.message)
            }
            .errorAlert($viewModel.errorMessage)
            .sensoryFeedback(.success, trigger: viewModel.successTrigger)
        }
        .onDisappear {
            viewModel.cancelGeneration()
        }
    }

    // MARK: - Sections

    private var explanationSection: some View {
        Section {
            Text(L10n.Settings.RegenerateIdentity.Sheet.explanation)
                .foregroundStyle(.secondary)
        }
    }

    private var prefixSection: some View {
        Section {
            DisclosureGroup(L10n.Settings.RegenerateIdentity.Prefix.label) {
                TextField(L10n.Settings.RegenerateIdentity.Prefix.placeholder, text: $viewModel.hexPrefix)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
                    .onChange(of: viewModel.hexPrefix) { _, newValue in
                        viewModel.sanitizePrefix(newValue)
                    }

                if let prefixError = viewModel.prefixError {
                    Label(prefixError, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                        .font(.footnote)
                        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
                }
            }
        } footer: {
            Text(L10n.Settings.RegenerateIdentity.Prefix.footer)
        }
    }

    private var generateSection: some View {
        Section {
            Button {
                viewModel.generateKey()
            } label: {
                HStack {
                    Spacer()
                    if viewModel.isGenerating {
                        ProgressView()
                            .controlSize(.small)
                            .accessibilityLabel(L10n.Settings.RegenerateIdentity.generating)
                        Text(L10n.Settings.RegenerateIdentity.generating)
                    } else {
                        Text(L10n.Settings.RegenerateIdentity.generate)
                    }
                    Spacer()
                }
            }
            .disabled(viewModel.isBusy)
        }
    }

    private func keyPreviewSection(_ key: RegenerateIdentityViewModel.GeneratedKey) -> some View {
        Group {
            Section {
                Text(key.publicKeyHex)
                    .font(.system(.body, design: .monospaced))
                    .textSelection(.enabled)
                    .accessibilityLabel(key.accessibilityLabel)
            } header: {
                Text(L10n.Settings.PublicKey.header)
            }
            Section {
                Text(key.privateKeyHex)
                    .font(.system(.body, design: .monospaced))
                    .textSelection(.enabled)
            } header: {
                Text(L10n.Settings.PrivateKey.header)
            }
        }
    }

    private var replaceSection: some View {
        Section {
            Button {
                viewModel.showingReplaceAlert = true
            } label: {
                HStack {
                    Spacer()
                    if viewModel.isImporting {
                        ProgressView()
                            .controlSize(.small)
                        Text(L10n.Settings.RegenerateIdentity.importing)
                    } else {
                        Text(L10n.Settings.RegenerateIdentity.replace)
                    }
                    Spacer()
                }
            }
            .disabled(viewModel.isBusy)
        }
    }
}
