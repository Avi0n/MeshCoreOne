import SwiftUI
import MC1Services

@Observable
@MainActor
final class ImportKeyViewModel {
    var hexInput = ""
    var isImporting = false
    var showingReplaceAlert = false
    var errorMessage: String?
    var successTrigger = 0

    private var validatedKeyData: Data?

    /// Restricts the pasted key text to hex digits.
    func sanitizeInput(_ newValue: String) {
        let filtered = String(newValue.uppercased().filter { $0.isASCII && $0.isHexDigit })
        if filtered != newValue {
            hexInput = filtered
        }
    }

    func validateAndConfirm() {
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

    /// Returns true when the key was imported and the sheet should dismiss.
    func importKey(appState: AppState) async -> Bool {
        guard let keyData = validatedKeyData,
              let settingsService = appState.services?.settingsService else { return false }

        isImporting = true
        defer { isImporting = false }
        do {
            try await settingsService.importPrivateKey(keyData)
            try await settingsService.refreshDeviceInfo()
            successTrigger += 1
            return true
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
            return false
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }
}
