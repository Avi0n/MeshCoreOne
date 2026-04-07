import MC1Services
import SwiftUI

/// Sheet for generating a new Ed25519 identity and importing it to the device
struct RegenerateIdentitySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState

    @State private var hexPrefix = ""
    @State private var isGenerating = false
    @State private var isImporting = false
    @State private var generatedKey: GeneratedKey?
    @State private var showingReplaceAlert = false
    @State private var errorMessage: String?
    @State private var prefixError: String?
    @State private var generateTask: Task<Void, Never>?
    @State private var successTrigger = 0

    private var isBusy: Bool { isGenerating || isImporting }

    var body: some View {
        NavigationStack {
            Form {
                explanationSection
                prefixSection
                generateSection
                if let generatedKey {
                    keyPreviewSection(generatedKey)
                    replaceSection
                }
            }
            .navigationTitle(L10n.Settings.RegenerateIdentity.Sheet.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Localizable.Common.cancel) {
                        dismiss()
                    }
                    .disabled(isBusy)
                }
            }
            .interactiveDismissDisabled(isBusy)
            .alert(L10n.Settings.RegenerateIdentity.Alert.Replace.title, isPresented: $showingReplaceAlert) {
                Button(L10n.Localizable.Common.cancel, role: .cancel) { }
                Button(L10n.Settings.RegenerateIdentity.Alert.Replace.confirm, role: .destructive) {
                    replaceIdentity()
                }
            } message: {
                Text(L10n.Settings.RegenerateIdentity.Alert.Replace.message)
            }
            .errorAlert($errorMessage)
            .sensoryFeedback(.success, trigger: successTrigger)
        }
        .onDisappear {
            generateTask?.cancel()
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
                TextField(L10n.Settings.RegenerateIdentity.Prefix.placeholder, text: $hexPrefix)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
                    .onChange(of: hexPrefix) { _, newValue in
                        let filtered = String(newValue.uppercased().filter { $0.isASCII && $0.isHexDigit }.prefix(4))
                        if filtered != newValue {
                            hexPrefix = filtered
                        }
                        prefixError = nil
                    }

                if let prefixError {
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
                generateKey()
            } label: {
                HStack {
                    Spacer()
                    if isGenerating {
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
            .disabled(isBusy)
        }
    }

    private func keyPreviewSection(_ key: GeneratedKey) -> some View {
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
                showingReplaceAlert = true
            } label: {
                HStack {
                    Spacer()
                    if isImporting {
                        ProgressView()
                            .controlSize(.small)
                        Text(L10n.Settings.RegenerateIdentity.importing)
                    } else {
                        Text(L10n.Settings.RegenerateIdentity.replace)
                    }
                    Spacer()
                }
            }
            .disabled(isBusy)
        }
    }

    // MARK: - Actions

    private func generateKey() {
        prefixError = nil

        // Validate prefix
        let upper = hexPrefix.uppercased()
        if !upper.isEmpty {
            if upper.count >= 2, upper.hasPrefix("00") || upper.hasPrefix("FF") {
                prefixError = L10n.Settings.RegenerateIdentity.Prefix.Error.reserved
                return
            }
        }

        isGenerating = true
        generateTask = Task {
            defer { isGenerating = false }
            do {
                let result = try await KeyGenerationService.generateIdentity(
                    hexPrefix: upper.isEmpty ? nil : upper
                )
                withAnimation {
                    generatedKey = GeneratedKey(
                        expandedKey: result.expandedPrivateKey,
                        publicKeyHex: result.publicKey.hexString(separator: " "),
                        privateKeyHex: result.expandedPrivateKey.hexString(separator: " "),
                        accessibilityLabel: result.publicKey
                            .map { String(format: "%02X", $0) }
                            .joined(separator: ", ")
                    )
                }
            } catch is CancellationError {
                // Sheet dismissed during generation
            } catch let error as KeyGenerationError {
                errorMessage = error.localizedDescription
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func replaceIdentity() {
        guard let expandedKey = generatedKey?.expandedKey,
              let settingsService = appState.services?.settingsService else { return }

        isImporting = true
        Task {
            defer { isImporting = false }
            do {
                try await settingsService.importPrivateKey(expandedKey)
                try await settingsService.refreshDeviceInfo()
                successTrigger += 1
                dismiss()
            } catch let error as SettingsServiceError {
                if case .sessionError(let meshError) = error,
                   case .featureDisabled = meshError {
                    errorMessage = L10n.Settings.RegenerateIdentity.Error.featureDisabled
                } else if case .sessionError(let meshError) = error,
                          case .deviceError = meshError {
                    errorMessage = L10n.Settings.RegenerateIdentity.Error.deviceRejected
                } else {
                    errorMessage = error.localizedDescription
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

// MARK: - Supporting Types

private struct GeneratedKey {
    let expandedKey: Data
    let publicKeyHex: String
    let privateKeyHex: String
    let accessibilityLabel: String
}
