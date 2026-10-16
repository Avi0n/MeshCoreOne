import Foundation
import Testing
@testable import MC1Services

/// Regenerates the on-disk vectors under `fixtures/backup/`. This is the deliberate,
/// reviewable path for a format change: it only writes when `REGENERATE_BACKUP_FIXTURES=1`
/// is set, so a normal `swift test` run leaves the files untouched and `BackupFixtureTests`
/// stays the guard. After a legitimate DTO wire-format change, run:
///
///     REGENERATE_BACKUP_FIXTURES=1 swift test --package-path MC1Services \
///       --filter BackupFixtureGenerator
///
/// then review the fixture diff and update the Kotlin decoder in lockstep (the freeze rule).
@Suite("BackupFixtureGenerator")
struct BackupFixtureGenerator {
  private static let isRequested =
    ProcessInfo.processInfo.environment["REGENERATE_BACKUP_FIXTURES"] == "1"

  @Test(.enabled(if: BackupFixtureGenerator.isRequested))
  func `Regenerate backup fixtures when explicitly requested`() throws {
    let fileManager = FileManager.default
    try fileManager.createDirectory(
      at: BackupFixtures.directory,
      withIntermediateDirectories: true
    )

    try writeEncoded(BackupFixtures.buildFullEnvelope(), to: "full-envelope.json", compressedTo: "full-envelope.mc1backup")
    try writeEncoded(BackupFixtures.buildUnicodeEnvelope(), to: "unicode-edge.json")
    try writeLegacyMinimal(to: "legacy-minimal.json")
  }

  // MARK: - Writers

  /// Encodes the envelope to JSON and, when a `compressedTo` name is given, also writes the
  /// `.mc1backup` transport golden: Apple's `NSData.compressed(using: .zlib)` (raw DEFLATE) of
  /// those exact JSON bytes, the deterministic input the Kotlin zlib golden decodes against.
  private func writeEncoded(_ envelope: AppBackupEnvelope, to name: String, compressedTo: String? = nil) throws {
    let data = try BackupFixtures.encoder().encode(envelope)
    try write(data, to: name)
    if let compressedTo {
      try write(data.zlibCompressed(), to: compressedTo)
    }
  }

  /// Encodes the modern legacy-source envelope, then removes the late-added keys from each
  /// channel and message object so the vector matches an envelope written before those keys
  /// existed. Re-serialized with `.sortedKeys` so regeneration stays byte-stable.
  private func writeLegacyMinimal(to name: String) throws {
    let encoded = try BackupFixtures.encoder().encode(BackupFixtures.buildLegacySourceEnvelope())
    guard var root = try JSONSerialization.jsonObject(with: encoded) as? [String: Any] else {
      throw FixtureError.notAnObject
    }

    root["channels"] = strip(BackupFixtures.legacyStrippedChannelKeys, fromArrayAt: "channels", in: root)
    root["messages"] = strip(BackupFixtures.legacyStrippedMessageKeys, fromArrayAt: "messages", in: root)
    root.removeValue(forKey: BackupFixtures.legacyStrippedEnvelopeKey)
    if var manifest = root["manifest"] as? [String: Any] {
      manifest.removeValue(forKey: BackupFixtures.legacyStrippedManifestKey)
      root["manifest"] = manifest
    }

    let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    try write(data, to: name)
  }

  private func strip(_ keys: [String], fromArrayAt arrayKey: String, in root: [String: Any]) -> [[String: Any]] {
    let array = (root[arrayKey] as? [[String: Any]]) ?? []
    return array.map { element in
      var copy = element
      for key in keys { copy.removeValue(forKey: key) }
      return copy
    }
  }

  private func write(_ data: Data, to name: String) throws {
    try data.write(to: BackupFixtures.directory.appendingPathComponent(name))
  }

  private enum FixtureError: Error {
    case notAnObject
  }
}
