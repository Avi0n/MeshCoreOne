import SwiftUI
import MC1Services
import OSLog

@Observable
@MainActor
final class NodeConfigImportViewModel {
    // Parse state
    var importedConfig: MeshCoreNodeConfig?
    var parseError: String?
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
    var applyError: String?
    var importComplete = false
    var showConfirmation = false

    private var importTask: Task<Void, Never>?

    private let logger = Logger(subsystem: "com.mc1", category: "NodeConfigImportVM")

    // MARK: - Dynamic confirmation text

    private var hasOverwriteSections: Bool {
        sections.radioSettings || sections.nodeIdentity || sections.positionSettings || sections.otherSettings
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
            parseError = L10n.Settings.ConfigImport.cannotAccess
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }

        do {
            let data = try Data(contentsOf: url)
            let config = try JSONDecoder().decode(MeshCoreNodeConfig.self, from: data)
            importedConfig = config
            parseError = nil

            // Auto-select only sections present in the file
            sections.nodeIdentity = config.name != nil || config.publicKey != nil || config.privateKey != nil
            sections.radioSettings = config.radioSettings != nil
            sections.positionSettings = config.positionSettings != nil && !(config.positionSettings?.isZero ?? true)
            sections.otherSettings = config.otherSettings != nil
            sections.channels = config.channels != nil
            sections.contacts = config.contacts != nil
        } catch {
            parseError = error.localizedDescription
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

    /// Apply the imported config to the device.
    func applyConfig(appState: AppState) {
        guard !isApplying else { return }
        guard let config = importedConfig,
              let service = appState.services?.nodeConfigService,
              let radioID = appState.connectedDevice?.radioID else { return }

        isApplying = true
        applyProgress = 0
        applyError = nil
        importComplete = false

        importTask = Task {
            do {
                try await service.importConfig(
                    config,
                    sections: sections,
                    radioID: radioID
                ) { progress in
                    Task { @MainActor in
                        self.applyProgress = Double(progress.current) / Double(max(1, progress.total))
                        self.applyStepDescription = progress.step
                    }
                }
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
                isApplying = false
                applyError = L10n.Settings.ConfigImport.cancelled
            } catch {
                isApplying = false
                applyError = error.localizedDescription
                logger.error("Import failed: \(error.localizedDescription)")
            }
        }
    }

    func cancelImport() {
        importTask?.cancel()
    }

    /// Reset to the initial file-selection state so the user can import another file.
    private func resetToFileSelection() {
        importedConfig = nil
        parseError = nil
        sections = ConfigSections()
        currentName = nil
        currentRadio = nil
        currentPosition = nil
        applyProgress = 0
        applyStepDescription = ""
        applyError = nil
        importComplete = false
        isApplying = false
    }
}
