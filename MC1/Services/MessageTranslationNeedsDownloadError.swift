import Foundation
@preconcurrency import Translation

/// Installed-only session could not translate. Leave the pending request in
/// place so `.translationTask` can present the system download UI.
struct MessageTranslationNeedsDownloadError: Error {
  static func wrapping(_ error: Error) -> Error {
    if #available(iOS 26.0, *), TranslationError.notInstalled ~= error {
      return MessageTranslationNeedsDownloadError()
    }
    return error
  }
}
