import SwiftUI
import MC1Services
import OSLog

@Observable
@MainActor
final class NodeConfigImportViewModel {
    // Parse state
    var importedConfig: MeshCoreNodeConfig?
    var errorMessage: String?
    var showFilePicker = false

    // Section selection
    var sections = ConfigSections()

    // Current device state for diff
    var currentName: String?
    var currentRadio: MeshCoreNodeConfig.RadioSettings?
    var currentPosition: MeshCoreNodeConfig.PositionSettings?

    // Apply state
    var isApplying = false
    var applyProgress: Double = 0
    var applyStepDescription = ""
    var importComplete = false
    var showConfirmation = false
    var isPreparingConfirmation = false

    /// Set from a non-destructive ``NodeConfigService/previewImport(_:sections:)`` pass before the
    /// confirmation alert. True when at least one selected channel would replace an already-configured
    /// slot, so the channels section is not purely additive and the confirmation copy must say so.
    private var channelsWouldOverwrite = false

    /// True once the import has reported progress, i.e. at least one destructive write reached the
    /// device. Distinguishes "cancelled before anything changed" from "cancelled mid-write."
    private var didApplyAnyWrite = false

    private var importTask: Task<Void, Never>?

    /// Handle for the in-flight preview, which does a real BLE round-trip via `previewImport`.
    /// Stored so it can be cancelled if the user leaves the screen or supersedes it.
    private var previewTask: Task<Void, Never>?

    private let logger = Logger(subsystem: "com.mc1", category: "NodeConfigImportVM")

    // MARK: - Dynamic confirmation text

    private var hasOverwriteSections: Bool {
        sections.radioSettings || sections.nodeIdentity || sections.positionSettings
            || sections.otherSettings || (sections.channels && channelsWouldOverwrite)
    }

    private var hasAdditiveSections: Bool {
        sections.channels || sections.contacts
    }

    var confirmTitle: String {
        switch (hasOverwriteSections, hasAdditiveSections) {
        case (false, true): L10n.Settings.ConfigImport.confirmTitleAdd
        case (true, false): L10n.Settings.ConfigImport.confirmTitleOverwrite
        default: L10n.Settings.ConfigImport.confirmTitle
        }
    }

    var applyButtonLabel: String {
        switch (hasOverwriteSections, hasAdditiveSections) {
        case (false, true): L10n.Settings.ConfigImport.applyButtonAdd
        case (true, false): L10n.Settings.ConfigImport.applyButtonOverwrite
        default: L10n.Settings.ConfigImport.applyButton
        }
    }

    func confirmMessage(deviceName: String) -> String {
        switch (hasOverwriteSections, hasAdditiveSections) {
        case (false, true): L10n.Settings.ConfigImport.confirmMessageAdd(deviceName)
        case (true, false): L10n.Settings.ConfigImport.confirmMessageOverwrite(deviceName)
        case (true, true): L10n.Settings.ConfigImport.confirmMessageMixed(deviceName)
        default: L10n.Settings.ConfigImport.confirmMessage(deviceName)
        }
    }

    /// Parse a JSON file from a security-scoped URL.
    func parseFile(at url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            errorMessage = L10n.Settings.ConfigImport.cannotAccess
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }

        do {
            let data = try Data(contentsOf: url)
            let config = try JSONDecoder().decode(MeshCoreNodeConfig.self, from: data)
            importedConfig = config
            errorMessage = nil

            // Auto-select only sections present in the file
            sections.nodeIdentity = config.name != nil || config.publicKey != nil || config.privateKey != nil
            sections.radioSettings = config.radioSettings != nil
            sections.positionSettings = config.positionSettings != nil && !(config.positionSettings?.isZero ?? true)
            sections.otherSettings = config.otherSettings != nil
            sections.channels = config.channels != nil
            sections.contacts = config.contacts != nil
        } catch {
            errorMessage = Self.localizedDescription(for: error)
            logger.error("Failed to parse config: \(error.localizedDescription)")
        }
    }

    /// Load current device values for diff display.
    func loadCurrentDeviceState(appState: AppState) async {
        guard let settingsService = appState.services?.settingsService else { return }
        do {
            let selfInfo = try await settingsService.getSelfInfo()
            currentName = selfInfo.name
            currentRadio = NodeConfigService.buildRadioSettings(from: selfInfo)
            currentPosition = MeshCoreNodeConfig.PositionSettings(
                latitude: String(selfInfo.latitude),
                longitude: String(selfInfo.longitude)
            )
        } catch {
            logger.error("Failed to load device state: \(error.localizedDescription)")
        }
    }

    /// Runs the non-destructive planner to classify the import (overwrite vs additive) and reject a
    /// malformed config up front, then presents the confirmation alert. Surfacing a planner error here
    /// means a poison file is caught before the user even confirms, and before any write.
    func prepareConfirmation(appState: AppState) {
        guard !isPreparingConfirmation, !isApplying else { return }
        guard let config = importedConfig,
              let service = appState.services?.nodeConfigService else { return }

        errorMessage = nil
        isPreparingConfirmation = true
        previewTask = Task {
            defer { isPreparingConfirmation = false }
            do {
                let preview = try await service.previewImport(config, sections: sections)
                // If the user left the screen while the round-trip was in flight, the dismissal has
                // already reset the UI; presenting the confirmation now would pop an alert over a
                // stale, off-screen state, so honour the cancellation even when the BLE call won the
                // race and returned before observing it.
                guard !Task.isCancelled else { return }
                channelsWouldOverwrite = preview.channelsOverwriteExisting
                errorMessage = nil
                showConfirmation = true
            } catch is CancellationError {
                // The user left the screen mid-preview — leave the UI untouched.
            } catch {
                errorMessage = Self.localizedDescription(for: error)
                logger.error("Import preview failed: \(error.localizedDescription)")
            }
        }
    }

    /// Apply the imported config to the device.
    func applyConfig(appState: AppState) {
        guard !isApplying else { return }
        guard let config = importedConfig,
              let service = appState.services?.nodeConfigService,
              let radioID = appState.connectedDevice?.radioID else { return }

        isApplying = true
        applyProgress = 0
        errorMessage = nil
        importComplete = false
        didApplyAnyWrite = false

        importTask = Task {
            // Deliver progress through one stream consumed in order on the main actor, so updates
            // can't race or arrive out of sequence the way a per-update `Task { @MainActor }` would.
            let (progressStream, progressContinuation) = AsyncStream.makeStream(of: ImportProgress.self)
            let consumer = Task { @MainActor in
                for await progress in progressStream {
                    didApplyAnyWrite = true
                    applyProgress = Double(progress.current) / Double(max(1, progress.total))
                    applyStepDescription = Self.description(for: progress.step)
                }
            }

            do {
                try await service.importConfig(
                    config,
                    sections: sections,
                    radioID: radioID
                ) { progress in
                    progressContinuation.yield(progress)
                }
                progressContinuation.finish()
                await consumer.value
                // Refresh cached device state so Settings UI reflects imported values
                if let settingsService = appState.services?.settingsService {
                    try? await settingsService.refreshDeviceInfo()
                }
                isApplying = false
                importComplete = true
                try? await Task.sleep(for: .seconds(1.5))
                guard !Task.isCancelled else { return }
                resetToFileSelection()
            } catch is CancellationError {
                progressContinuation.finish()
                await consumer.value
                isApplying = false
                // "No progress reported" means no destructive write landed, so the device is untouched.
                errorMessage = Self.cancellationMessage(didApplyAnyWrite: didApplyAnyWrite)
            } catch {
                progressContinuation.finish()
                await consumer.value
                isApplying = false
                errorMessage = Self.failureMessage(for: error, didApplyAnyWrite: didApplyAnyWrite)
                logger.error("Import failed: \(error.localizedDescription)")
            }
        }
    }

    func cancelImport() {
        importTask?.cancel()
    }

    /// Called when the screen goes away. Cancels any pending preview round-trip and, unless a
    /// destructive import is in flight, resets back to the file picker so re-entering the screen
    /// starts fresh instead of re-showing a stale preview or error. An in-flight import is left
    /// running with its progress state intact: it is a destructive, safety-critical operation with
    /// its own explicit cancel control, so a transient view disappearance must neither abort it
    /// mid-write nor hide its result from a user who returns.
    func handleDismissal() {
        previewTask?.cancel()
        guard !isApplying else { return }
        resetToFileSelection()
    }

    /// Maps a service-layer ``ImportStep`` to a localized progress description, keeping `L10n`
    /// in the app layer so the service stays localization-free.
    private static func description(for step: ImportStep) -> String {
        switch step {
        case .position: L10n.Settings.ConfigImport.stepPosition
        case .otherParameters: L10n.Settings.ConfigImport.stepOtherParameters
        case .privateKey: L10n.Settings.ConfigImport.stepPrivateKey
        case .nodeName: L10n.Settings.ConfigImport.stepNodeName
        case .radioParameters: L10n.Settings.ConfigImport.stepRadioParameters
        case .txPower: L10n.Settings.ConfigImport.stepTxPower
        case .channel(let name): L10n.Settings.ConfigImport.stepChannel(name)
        case .contact(let name): L10n.Settings.ConfigImport.stepContact(name)
        }
    }

    /// Maps a service-layer ``NodeConfigServiceError`` to a localized message, keeping `L10n` in the
    /// app layer so the service stays localization-free. Non-config errors fall through to their own
    /// ``Error/localizedDescription``.
    static func localizedDescription(for error: Error) -> String {
        guard let configError = error as? NodeConfigServiceError else { return error.localizedDescription }
        switch configError {
        case .invalidRadioSettings(let field):
            return L10n.Settings.ConfigImport.Error.radioOutOfRange(radioFieldLabel(field))
        case .invalidCoordinate(let field):
            switch field {
            case .positionLatitude, .positionLongitude:
                return L10n.Settings.ConfigImport.Error.positionInvalid(coordinateLabel(field))
            case .contactLatitude(let name), .contactLongitude(let name):
                return L10n.Settings.ConfigImport.Error.contactCoordinateInvalid(name, coordinateLabel(field))
            }
        case .invalidOutPath(let name):
            return L10n.Settings.ConfigImport.Error.invalidOutPath(name)
        case .contactCapacityExceeded(let needed, let available):
            return L10n.Settings.ConfigImport.Error.contactCapacityExceeded(needed, available)
        default:
            return error.localizedDescription
        }
    }

    /// Localized message for a cancelled import; distinguishes a clean cancel from one where a
    /// destructive write already landed on the device.
    static func cancellationMessage(didApplyAnyWrite: Bool) -> String {
        didApplyAnyWrite
            ? L10n.Settings.ConfigImport.cancelledPartial
            : L10n.Settings.ConfigImport.cancelled
    }

    /// Localized message for a failed import; distinguishes a clean failure from one where a
    /// destructive write already landed.
    static func failureMessage(for error: Error, didApplyAnyWrite: Bool) -> String {
        didApplyAnyWrite
            ? L10n.Settings.ConfigImport.failedPartial(localizedDescription(for: error))
            : localizedDescription(for: error)
    }

    private static func radioFieldLabel(_ field: RadioField) -> String {
        switch field {
        case .frequency: L10n.Settings.ConfigImport.Field.frequency
        case .bandwidth: L10n.Settings.ConfigImport.Field.bandwidth
        case .spreadingFactor: L10n.Settings.ConfigImport.Field.spreadingFactor
        case .codingRate: L10n.Settings.ConfigImport.Field.codingRate
        case .txPower: L10n.Settings.ConfigImport.Field.txPower
        }
    }

    private static func coordinateLabel(_ field: CoordinateField) -> String {
        switch field {
        case .positionLatitude, .contactLatitude: L10n.Settings.ConfigImport.Field.latitude
        case .positionLongitude, .contactLongitude: L10n.Settings.ConfigImport.Field.longitude
        }
    }

    /// Reset to the initial file-selection state so the user can import another file.
    private func resetToFileSelection() {
        importedConfig = nil
        errorMessage = nil
        sections = ConfigSections()
        currentName = nil
        currentRadio = nil
        currentPosition = nil
        applyProgress = 0
        applyStepDescription = ""
        importComplete = false
        isApplying = false
        isPreparingConfirmation = false
        showConfirmation = false
        channelsWouldOverwrite = false
        didApplyAnyWrite = false
    }
}
