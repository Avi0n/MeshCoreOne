import Foundation
import MC1Services

/// Errors thrown by MeshCore One's App Intents, mirroring the
/// `.sessionError(MeshCoreError)` wrapping convention of `MessageServiceError`.
enum IntentError: LocalizedError {
    case notConnected
    case invalidRecipient
    case messageTooLong
    case sendFailed
    case advertFailed
    case sessionError(MeshCoreError)

    /// Siri and Shortcuts read `errorDescription`, so this is the L10n seam.
    var errorDescription: String? {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.Intent.notConnected
        case .invalidRecipient:
            L10n.Localizable.Error.Intent.invalidRecipient
        case .messageTooLong:
            L10n.Localizable.Error.Intent.messageTooLong
        case .sendFailed:
            L10n.Localizable.Error.Intent.sendFailed
        case .advertFailed:
            L10n.Localizable.Error.Advertisement.sendFailed
        case .sessionError(let error):
            error.userFacingMessage
        }
    }
}
