import MC1Services
import SwiftUI

/// Sheet for importing an existing Ed25519 private key onto the device
struct ImportKeySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState

    @State private var hexInput = ""
    @State private var isImporting = false
    @State private var showingReplaceAlert = false
    @State private var errorMessage: String?
    @State private var successTrigger = 0
    @State private var validatedKeyData: Data?

    var body: some View {
        NavigationStack {
            Form {
                explanationSection
                keyInputSection
                importSection
            }
            .navigationTitle(L10n.Settings.ImportKey.Sheet.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Localizable.Common.cancel) {
                        dismiss()
                    }
                    .disabled(isImporting)
                }
            }
            .interactiveDismissDisabled(isImporting)
            .alert(L10n.Settings.RegenerateIdentity.Alert.Replace.title, isPresented: $showingReplaceAlert) {
                Button(L10n.Localizable.Common.cancel, role: .cancel) { }
                Button(L10n.Settings.RegenerateIdentity.Alert.Replace.confirm, role: .destructive) {
                    importKey()
                }
            } message: {
                Text(L10n.Settings.RegenerateIdentity.Alert.Replace.message)
            }
            .errorAlert($errorMessage)
            .sensoryFeedback(.success, trigger: successTrigger)
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
            TextField(L10n.Settings.ImportKey.KeyInput.placeholder, text: $hexInput, axis: .vertical)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .font(.system(.body, design: .monospaced))
                .lineLimit(3...6)
                .onChange(of: hexInput) { _, newValue in
                    let filtered = String(newValue.uppercased().filter { $0.isASCII && $0.isHexDigit })
                    if filtered != newValue {
                        hexInput = filtered
                    }
                }
        } header: {
            Text(L10n.Settings.ImportKey.KeyInput.label)
        }
    }

    private var importSection: some View {
        Section {
            Button {
                validateAndConfirm()
            } label: {
                HStack {
                    Spacer()
                    if isImporting {
                        ProgressView()
                            .controlSize(.small)
                        Text(L10n.Settings.ImportKey.importing)
                    } else {
                        Text(L10n.Settings.ImportKey.import)
                    }
                    Spacer()
                }
            }
            .disabled(isImporting || hexInput.isEmpty)
        }
    }

    // MARK: - Actions

    private func validateAndConfirm() {
        // Parse hex and validate length
        guard let keyData = Data(hexString: hexInput),
              keyData.count == ProtocolLimits.privateKeySize else {
            errorMessage = L10n.Settings.ImportKey.Error.invalidHex
            return
        }

        // Validate Ed25519 clamping
        do {
            try KeyGenerationService.validateExpandedKey(keyData)
        } catch {
            errorMessage = L10n.Settings.ImportKey.Error.invalidKey
            return
        }

        validatedKeyData = keyData
        showingReplaceAlert = true
    }

    private func importKey() {
        guard let keyData = validatedKeyData,
              let settingsService = appState.services?.settingsService else { return }

        isImporting = true
        Task {
            defer { isImporting = false }
            do {
                try await settingsService.importPrivateKey(keyData)
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
