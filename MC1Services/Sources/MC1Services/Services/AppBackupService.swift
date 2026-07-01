import Foundation

/// Result of a successful backup export.
public struct ExportResult: Sendable {
  public let data: Data
  public let manifest: BackupManifest

  public init(data: Data, manifest: BackupManifest) {
    self.data = data
    self.manifest = manifest
  }
}

/// Exports all app data to a compressed backup file.
public actor AppBackupService {
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "AppBackupService")

  public init() {}

  // MARK: - Export

  /// Export all app data to a compressed backup.
  /// - Parameter persistenceStore: The store to fetch records from.
  /// - Returns: An `ExportResult` containing the compressed data and manifest.
  /// - Throws: `AppBackupError.exportFailed` on failure.
  public func export(
    persistenceStore: PersistenceStore
  ) async throws -> ExportResult {
    do {
      let snapshot = try await persistenceStore.fetchBackupExportSnapshot()

      let userDefaultsSnapshot = BackupUserDefaults.snapshot(from: .standard)

      let appVersion = Bundle.main.appVersion
      let appBuild = Bundle.main.appBuild

      var envelope = AppBackupEnvelope(
        exportDate: .now,
        appVersion: appVersion,
        appBuild: appBuild,
        devices: snapshot.devices,
        contacts: snapshot.contacts,
        channels: snapshot.channels,
        messages: snapshot.messages,
        messageRepeats: snapshot.messageRepeats,
        reactions: snapshot.reactions,
        roomMessages: snapshot.roomMessages,
        remoteNodeSessions: snapshot.remoteNodeSessions,
        savedTracePaths: snapshot.savedTracePaths,
        blockedChannelSenders: snapshot.blockedChannelSenders,
        nodeStatusSnapshots: snapshot.nodeStatusSnapshots,
        userDefaults: userDefaultsSnapshot
      )
      envelope.manifest = BackupManifest(from: envelope)

      let encoder = makeBackupJSONEncoder()
      let jsonData = try encoder.encode(envelope)

      let compressed = try jsonData.zlibCompressed()
      logger.info("Backup exported: \(jsonData.count) bytes JSON → \(compressed.count) bytes compressed")
      return ExportResult(data: compressed, manifest: envelope.manifest)

    } catch let error as AppBackupError {
      throw error
    } catch {
      throw AppBackupError.exportFailed(underlying: error)
    }
  }

  // MARK: - Import

  /// Import backup data into the local store with radioID remapping.
  ///
  /// Devices are matched by `publicKey`. When a backup device matches a local device,
  /// all child records are remapped to use the local device's `radioID`. Unmatched
  /// devices are inserted as-is. Records are inserted in parent-before-child order
  /// with a single `modelContext.save()` at the end.
  ///
  /// - Parameters:
  ///   - envelope: The decoded backup envelope.
  ///   - persistenceStore: The store to insert records into.
  /// - Returns: An `ImportResult` with per-model inserted/skipped counts.
  /// - Throws: `AppBackupError.importFailed` on failure.
  @discardableResult
  public func importBackup(
    envelope: AppBackupEnvelope,
    into persistenceStore: PersistenceStore
  ) async throws -> ImportResult {
    do {
      var result = try await persistenceStore.importBackupDatabase(envelope)

      // Cancellation past this point is ignored. `importBackupDatabase` has
      // already committed, and `BackupUserDefaults.restore(to:)` is a
      // write-if-missing no-op on re-run, so finishing the side effect is
      // safer than reporting cancellation while the DB is persisted.
      if let userDefaultsSnapshot = envelope.userDefaults {
        let addedKeys = userDefaultsSnapshot.restore(to: .standard)
        result.userDefaultsRestored = !addedKeys.isEmpty
      }

      logger.info(
        "Import complete: \(result.totalInserted) inserted, \(result.totalMerged) merged, \(result.totalSkipped) skipped"
      )
      return result

    } catch is CancellationError {
      throw CancellationError()
    } catch let error as AppBackupError {
      throw error
    } catch {
      throw AppBackupError.importFailed(underlying: error)
    }
  }
}
