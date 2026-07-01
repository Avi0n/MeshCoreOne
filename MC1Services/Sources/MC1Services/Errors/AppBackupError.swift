import Foundation

/// Errors that can occur during app backup export or import.
public enum AppBackupError: Error, LocalizedError, Sendable {
  case invalidFile
  case fileTooLarge(actualBytes: Int, maxBytes: Int)
  case decompressedTooLarge(maxBytes: Int)
  case unsupportedVersion(found: Int, maxSupported: Int)
  case corruptedManifest
  case exportFailed(underlying: any Error)
  case importFailed(underlying: any Error)

  public var errorDescription: String? {
    switch self {
    case .invalidFile:
      "The backup file is invalid or could not be read."
    case let .fileTooLarge(actualBytes, maxBytes):
      "The backup file is too large to import (\(actualBytes / 1_048_576) MB; limit is \(maxBytes / 1_048_576) MB)."
    case let .decompressedTooLarge(maxBytes):
      "The backup file expands past the safe size limit (\(maxBytes / 1_048_576) MB uncompressed)."
    case let .unsupportedVersion(found, maxSupported):
      "This backup was created with a newer format (version \(found)). This app supports up to version \(maxSupported). Please update the app and try again."
    case .corruptedManifest:
      "The backup file appears to be corrupted. The declared item counts do not match the actual data."
    case let .exportFailed(underlying):
      "Failed to create backup: \(underlying.localizedDescription)"
    case let .importFailed(underlying):
      "Failed to import backup: \(underlying.localizedDescription)"
    }
  }
}
