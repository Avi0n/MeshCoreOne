import SwiftUI
import MC1Services

@Observable
@MainActor
final class RegenerateIdentityViewModel {
    var hexPrefix = ""
    var isGenerating = false
    var isImporting = false
    var generatedKey: GeneratedKey?
    var showingReplaceAlert = false
    var errorMessage: String?
    var prefixError: String?
    var successTrigger = 0

    private var generateTask: Task<Void, Never>?

    var isBusy: Bool { isGenerating || isImporting }

    /// Maximum vanity-prefix length in hex digits (two key bytes).
    private static let maxPrefixLength = 4
    /// Key prefixes the firmware reserves for special addressing.
    private static let reservedPrefixes = ["00", "FF"]

    struct GeneratedKey {
        let expandedKey: Data
        let publicKeyHex: String
        let privateKeyHex: String
        let accessibilityLabel: String
    }

    func cancelGeneration() {
        generateTask?.cancel()
    }

    /// Restricts the vanity prefix to hex digits and clears stale validation feedback.
    func sanitizePrefix(_ newValue: String) {
        let filtered = String(
            newValue.uppercased()
                .filter { $0.isASCII && $0.isHexDigit }
                .prefix(Self.maxPrefixLength)
        )
        if filtered != newValue {
            hexPrefix = filtered
        }
        prefixError = nil
    }

    func generateKey() {
        prefixError = nil

        // Validate prefix
        let upper = hexPrefix.uppercased()
        if upper.count >= 2, Self.reservedPrefixes.contains(where: upper.hasPrefix) {
            prefixError = L10n.Settings.RegenerateIdentity.Prefix.Error.reserved
            return
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
                        publicKeyHex: result.publicKey.uppercaseHexString(separator: " "),
                        privateKeyHex: result.expandedPrivateKey.uppercaseHexString(separator: " "),
                        accessibilityLabel: result.publicKey
                            .map { String(format: "%02X", $0) }
                            .joined(separator: ", ")
                    )
                }
            } catch is CancellationError {
                // Sheet dismissed during generation
            } catch {
                errorMessage = error.userFacingMessage
            }
        }
    }

    /// Returns true when the new identity was imported and the sheet should dismiss.
    /// A nil service mirrors a disconnected state and is a no-op.
    func replaceIdentity(settingsService: SettingsService?) async -> Bool {
        guard let expandedKey = generatedKey?.expandedKey,
              let settingsService else { return false }

        isImporting = true
        defer { isImporting = false }
        do {
            try await settingsService.importPrivateKey(expandedKey)
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
                errorMessage = error.userFacingMessage
            }
            return false
        } catch {
            errorMessage = error.userFacingMessage
            return false
        }
    }
}
