import Foundation
import MC1Services
import os
import TipKit

/// Import flow state. Collapsing the five overlapping booleans the view
/// model used to carry into a single enum makes invalid combinations
/// (e.g. `isImporting && importResult != nil`) unrepresentable, and
/// separates the sheet-scoped error surface from the top-level alert.
enum ImportState {
    case idle
    case parsing
    case preview(AppBackupEnvelope)
    case importing
    case success(ImportResult)
    case failed(String)
    case cancelled
}

@MainActor
@Observable
final class AppBackupViewModel {
    // MARK: - Export state

    var isExporting = false
    var exportedData: Data?  // when set, triggers fileExporter
    var errorMessage: String?

    // MARK: - Import state

    var importState: ImportState = .idle
    /// Latched to `true` the instant the user taps Cancel during an active import,
    /// so the UI can acknowledge the tap before the import task reaches a
    /// cancellation point and actually tears down.
    var isCancellingImport = false
    private var parseTask: Task<Void, Never>?
    private var currentParseID: UUID?
    private var importTask: Task<Void, Never>?

    /// Computed view of the import flow. `.parsing` is treated as pre-sheet
    /// so the spinner in the import row is the only visible cue until a
    /// preview is ready; the sheet appears once state advances past parsing.
    var isImportSheetActive: Bool {
        switch importState {
        case .idle, .parsing: false
        case .preview, .importing, .success, .failed, .cancelled: true
        }
    }

    var isParsing: Bool {
        if case .parsing = importState { true } else { false }
    }

    var isImporting: Bool {
        if case .importing = importState { true } else { false }
    }

    var isCancelled: Bool {
        if case .cancelled = importState { true } else { false }
    }

    var previewEnvelope: AppBackupEnvelope? {
        if case .preview(let env) = importState { env } else { nil }
    }

    var importResult: ImportResult? {
        if case .success(let result) = importState { result } else { nil }
    }

    /// Error surfaced inside the import sheet (import-phase failure). Kept
    /// separate from `errorMessage`, which is reserved for top-level alerts
    /// shown when no sheet is active.
    var sheetErrorMessage: String? {
        if case .failed(let msg) = importState { msg } else { nil }
    }

    // MARK: - Dependencies

    private let connectionManager: ConnectionManager
    private let onImportRestoredData: (@MainActor () -> Void)?
    private let backupService = AppBackupService()
    private let logger = Logger(subsystem: "com.mc1", category: "AppBackupViewModel")

    init(
        connectionManager: ConnectionManager,
        onImportRestoredData: (@MainActor () -> Void)? = nil
    ) {
        self.connectionManager = connectionManager
        self.onImportRestoredData = onImportRestoredData
    }

    // MARK: - Export

    func performExport() {
        guard !isExporting else { return }
        isExporting = true
        errorMessage = nil

        Task {
            defer { isExporting = false }
            do {
                exportedData = try await backupService.export(
                    persistenceStore: preferredPersistenceStore()
                )
            } catch {
                errorMessage = error.backupUserFacingMessage
                logger.error("Export failed: \(error.localizedDescription)")
            }
        }
    }

    func handleExportResult(_ result: Result<URL, Error>) {
        defer { exportedData = nil }

        if case .failure(let error) = result {
            guard !isUserCancelled(error) else { return }
            errorMessage = error.backupUserFacingMessage
        }
    }

    // MARK: - Import

    func handleFileSelected(result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            loadAndParseBackup(from: url)
        case .failure(let error):
            guard !isUserCancelled(error) else { return }
            errorMessage = error.backupUserFacingMessage
        }
    }

    func loadAndParseBackup(from url: URL) {
        parseTask?.cancel()
        importState = .parsing
        let taskID = UUID()
        currentParseID = taskID
        let didAccessSecurityScope = url.startAccessingSecurityScopedResource()

        parseTask = Task.detached(priority: .userInitiated) { [weak self, url, didAccessSecurityScope] in
            defer {
                if didAccessSecurityScope {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            do {
                // .mappedIfSafe lets the OS page-cache the read rather than
                // copying the whole file into the heap.
                let data = try Data(contentsOf: url, options: .mappedIfSafe)
                try Task.checkCancellation()
                let envelope = try parseBackup(data: data)
                try Task.checkCancellation()
                await MainActor.run {
                    guard self?.currentParseID == taskID else { return }
                    self?.importState = .preview(envelope)
                    self?.errorMessage = nil
                }
            } catch is CancellationError {
                // The new invocation owns importState; don't clobber it
                return
            } catch {
                let message = error.backupUserFacingMessage
                await MainActor.run {
                    guard self?.currentParseID == taskID else { return }
                    // Parse failure happens before the sheet opens, so surface
                    // via the top-level alert, not the in-sheet error view.
                    self?.importState = .idle
                    self?.errorMessage = message
                }
            }
        }
    }

    func performImport() {
        guard case .preview(let envelope) = importState else { return }
        isCancellingImport = false
        importState = .importing

        importTask = Task {
            defer { importTask = nil }
            do {
                let result = try await backupService.importBackup(
                    envelope: envelope,
                    into: preferredPersistenceStore()
                )
                importState = .success(result)
                if result.hasRestoredChanges {
                    // The import wrote directly to the persistence store, bypassing the
                    // sync-path callbacks that normally bump contacts/conversations
                    // versions. Fire the notifier so any mounted chat/contact tabs
                    // reload their data rather than show the pre-restore snapshot.
                    onImportRestoredData?()
                    // SyncCoordinator caches blocked names at connect time; restored
                    // BlockedChannelSender rows and blocked contacts would otherwise be
                    // ignored by the incoming-message filter until reconnect.
                    if let services = connectionManager.services,
                       let radioID = connectionManager.lastConnectedRadioID {
                        await services.syncCoordinator.refreshBlockedContactsCache(
                            radioID: radioID,
                            dataStore: services.dataStore
                        )
                    }
                }
                // Donate to BackupTip so a restored install doesn't re-show the export prompt.
                // TipKit's own donation store is wiped on reinstall, so the donation in the
                // export path doesn't survive; donating again here covers the reinstall+restore path.
                BackupTip.exportCompleted.sendDonation()
            } catch is CancellationError {
                // Rollback is guaranteed by the `defer` in `importBackupDatabase`.
                // Surface a terminal cancelled state so the user sees that nothing
                // was changed instead of the sheet closing silently.
                importState = .cancelled
            } catch {
                importState = .failed(error.backupUserFacingMessage)
                logger.error("Import failed: \(error.localizedDescription)")
            }
        }
    }

    func cancelImport() {
        guard !isCancellingImport else { return }
        isCancellingImport = true
        importTask?.cancel()
    }

    func dismissImportSheet() {
        parseTask?.cancel()
        parseTask = nil
        currentParseID = nil
        importState = .idle
        isCancellingImport = false
        errorMessage = nil
    }

    // MARK: - Export file name

    var defaultExportFilename: String {
        let timestamp = Date.now.formatted(
            .iso8601
                .year()
                .month()
                .day()
                .dateSeparator(.dash)
                .dateTimeSeparator(.space)
                .time(includingFractionalSeconds: false)
                .timeSeparator(.omitted)
        )
        return L10n.Settings.Settings.Backup.Export.defaultFilename(timestamp)
    }

    private func preferredPersistenceStore() -> PersistenceStore {
        // Reuse the live store actor when services are active so backup work serializes
        // with the app's authoritative writer instead of creating a second store actor.
        connectionManager.services?.dataStore ?? connectionManager.createStandalonePersistenceStore()
    }

    private func isUserCancelled(_ error: any Error) -> Bool {
        let nsError = error as NSError
        return nsError.domain == NSCocoaErrorDomain && nsError.code == NSUserCancelledError
    }
}
