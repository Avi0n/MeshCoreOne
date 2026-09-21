import Foundation
import MC1Services

/// Errors thrown by MeshCore One's App Intents, mirroring the
/// `.sessionError(MeshCoreError)` wrapping convention of `MessageServiceError`.
enum IntentError: LocalizedError, CustomLocalizedStringResourceConvertible {
  case notConnected
  case invalidRecipient
  case messageTooLong
  case sendFailed
  case advertFailed
  case sessionError(MeshCoreError)

  /// App Intents speaks `localizedStringResource` on Siri and Shortcuts;
  /// `errorDescription` stays for in-process callers and tests.
  var errorDescription: String? {
    message
  }

  var localizedStringResource: LocalizedStringResource {
    LocalizedStringResource(stringLiteral: message)
  }

  private var message: String {
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
    case let .sessionError(error):
      error.userFacingMessage
    }
  }
}
