import Foundation
import MC1Services

extension AppBackupError {
  /// L10n-routed user-facing message for backup errors. The package-level
  /// `errorDescription` provides an English fallback so MC1Services stays
  /// independent of the app target's L10n; views should prefer this property.
  var userFacingMessage: String {
    switch self {
    case .invalidFile:
      return L10n.Settings.Settings.Backup.Error.invalidFile
    case let .fileTooLarge(actualBytes, maxBytes):
      let actualMB = actualBytes / 1_048_576
      let maxMB = maxBytes / 1_048_576
      return L10n.Settings.Settings.Backup.Error.fileTooLarge(actualMB, maxMB)
    case let .decompressedTooLarge(maxBytes):
      let maxMB = maxBytes / 1_048_576
      return L10n.Settings.Settings.Backup.Error.decompressedTooLarge(maxMB)
    case let .unsupportedVersion(found, maxSupported):
      return L10n.Settings.Settings.Backup.Error.unsupportedVersion(found, maxSupported)
    case .corruptedManifest:
      return L10n.Settings.Settings.Backup.Error.corruptedManifest
    case let .exportFailed(underlying):
      return L10n.Settings.Settings.Backup.Error.exportFailed(underlying.userFacingMessage)
    case let .importFailed(underlying):
      return L10n.Settings.Settings.Backup.Error.importFailed(underlying.userFacingMessage)
    }
  }
}
