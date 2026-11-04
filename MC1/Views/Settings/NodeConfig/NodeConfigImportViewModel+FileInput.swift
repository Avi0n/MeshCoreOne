import Foundation
import MC1Services
import OSLog

extension NodeConfigImportViewModel {
  /// File reads can wait on iCloud Drive, so decoding stays off the main actor.
  func parseFile(at url: URL) {
    guard !isApplying else { return }
    parseTask?.cancel()
    cancelConfirmation()
    isParsing = true
    errorMessage = nil
    let taskID = UUID()
    currentParseID = taskID
    let selectedRecovery = recovery
    let source = connectionManager?.currentConnection.map { ImportSource($0) }
    let didAccessSecurityScope = url.startAccessingSecurityScopedResource()
    parseTask = Task.detached(priority: .userInitiated) { [weak self, url, didAccessSecurityScope] in
      defer {
        if didAccessSecurityScope { url.stopAccessingSecurityScopedResource() }
      }
      do {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        try Task.checkCancellation()
        let config = try JSONDecoder().decode(MeshCoreNodeConfig.self, from: data)
        try Task.checkCancellation()
        await self?.applyParseSuccess(config, for: taskID, recovery: selectedRecovery, source: source)
      } catch is CancellationError {
        return
      } catch {
        await self?.applyParseFailure(error, for: taskID)
      }
    }
  }

  private func applyParseSuccess(
    _ config: MeshCoreNodeConfig, for taskID: UUID, recovery: IdentityImportRecovery?, source: ImportSource?
  ) {
    guard currentParseID == taskID, self.recovery?.receiptID == recovery?.receiptID else { return }
    isParsing = false
    importedConfig = config
    inputReceiptID = recovery?.receiptID
    inputSource = source
    errorMessage = nil
    sections.nodeIdentity = config.name != nil || config.publicKey != nil || config.privateKey != nil
    sections.radioSettings = config.radioSettings != nil
    sections.positionSettings = config.positionSettings != nil && !(config.positionSettings?.isZero ?? true)
    sections.otherSettings = config.otherSettings != nil
    sections.channels = config.channels != nil
    sections.contacts = config.contacts != nil
  }

  private func applyParseFailure(_ error: Error, for taskID: UUID) {
    guard currentParseID == taskID else { return }
    isParsing = false
    errorMessage = error.userFacingMessage
    logger.error("Failed to parse config: \(error.localizedDescription)")
  }

  func loadCurrentDeviceState() async {
    guard recovery == nil, let settingsService,
          let source = connectionManager?.currentConnection else { return }
    do {
      let info = try await settingsService.getSelfInfo()
      guard connectionManager?.isCurrent(source) == true else { return }
      currentName = info.name
      currentRadio = NodeConfigService.buildRadioSettings(from: info)
      currentPosition = MeshCoreNodeConfig.PositionSettings(latitude: String(info.latitude), longitude: String(info.longitude))
    } catch {
      logger.error("Failed to load device state: \(error.localizedDescription)")
    }
  }

  func handleDismissal() {
    parseTask?.cancel()
    currentParseID = nil
    previewTask?.cancel()
    currentPreviewID = nil
    isParsing = false
    isPreparingConfirmation = false
    showFilePicker = false
    cancelConfirmation()
    guard !isApplying else { return }
    if let inputReceiptID,
       connectionManager?.identityImportRecoveries.contains(where: { $0.receiptID == inputReceiptID }) == true {
      return
    }
    resetToFileSelection()
  }

  func resetToFileSelection() {
    importedConfig = nil
    errorMessage = nil
    isParsing = false
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
    recovery = nil
    identityRecoveryComplete = false
  }
}
