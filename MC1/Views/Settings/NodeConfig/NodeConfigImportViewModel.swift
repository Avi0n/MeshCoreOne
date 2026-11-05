import MC1Services
import OSLog
import SwiftUI

@Observable
@MainActor
final class NodeConfigImportViewModel {
  struct ImportSource: Equatable, Sendable {
    let generation: UUID
    let requestID: UUID
    let deviceID: UUID
    let radioID: UUID
    let name: String

    @MainActor
    init(_ snapshot: ConnectionSnapshot) {
      generation = snapshot.generation
      requestID = snapshot.requestID
      deviceID = snapshot.device.id
      radioID = snapshot.device.radioID
      name = snapshot.device.nodeName
    }
  }

  enum Confirmation {
    case config(ImportSource, inputID: UUID, sections: ConfigSections)
    case recovery(IdentityImportRecovery, inputID: UUID)
  }

  var importedConfig: MeshCoreNodeConfig? {
    didSet {
      inputID = UUID()
      inputSource = nil
      inputReceiptID = nil
      cancelConfirmation()
    }
  }

  var errorMessage: String?
  var showFilePicker = false
  var isParsing = false
  var sections = ConfigSections()
  var currentName: String?
  var currentRadio: MeshCoreNodeConfig.RadioSettings?
  var currentPosition: MeshCoreNodeConfig.PositionSettings?
  var isApplying = false
  var applyProgress: Double = 0
  var applyStepDescription = ""
  var importComplete = false
  var showConfirmation = false
  var isPreparingConfirmation = false
  var recovery: IdentityImportRecovery?
  var identityRecoveryComplete = false

  var channelsWouldOverwrite = false
  var didApplyAnyWrite = false
  var importTask: Task<Void, Never>?
  var parseTask: Task<Void, Never>?
  var currentParseID: UUID?
  var previewTask: Task<Void, Never>?
  var currentPreviewID: UUID?
  var inputID = UUID()
  var inputSource: ImportSource?
  var inputReceiptID: UUID?
  var confirmation: Confirmation?
  var connectionManager: ConnectionManager?
  let logger = Logger(subsystem: "com.mc1", category: "NodeConfigImportVM")

  private var nodeConfigServiceProvider: @MainActor () -> NodeConfigService? = { nil }
  private var settingsServiceProvider: @MainActor () -> SettingsService? = { nil }
  private var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  var nodeConfigService: NodeConfigService? {
    nodeConfigServiceProvider()
  }

  var settingsService: SettingsService? {
    settingsServiceProvider()
  }

  var connectedDevice: DeviceDTO? {
    connectedDeviceProvider()
  }

  func configure(
    nodeConfigService: @escaping @MainActor () -> NodeConfigService?,
    settingsService: @escaping @MainActor () -> SettingsService?,
    connectedDevice: @escaping @MainActor () -> DeviceDTO?,
    connectionManager: ConnectionManager
  ) {
    nodeConfigServiceProvider = nodeConfigService
    settingsServiceProvider = settingsService
    connectedDeviceProvider = connectedDevice
    self.connectionManager = connectionManager
  }

  private var hasOverwriteSections: Bool {
    sections.radioSettings || sections.nodeIdentity || sections.positionSettings
      || sections.otherSettings || (sections.channels && channelsWouldOverwrite)
  }

  private var hasAdditiveSections: Bool {
    sections.channels || sections.contacts
  }

  var confirmTitle: String {
    if recovery != nil { return L10n.Settings.ConfigImport.confirmTitleIdentityRecovery }
    switch (hasOverwriteSections, hasAdditiveSections) {
    case (false, true): return L10n.Settings.ConfigImport.confirmTitleAdd
    case (true, false): return L10n.Settings.ConfigImport.confirmTitleOverwrite
    default: return L10n.Settings.ConfigImport.confirmTitle
    }
  }

  var confirmButtonRole: ButtonRole? {
    recovery == nil ? nil : .destructive
  }

  /// The recovery sheet stays up while the identity write is in flight, so its result stays visible.
  var recoverySheetDismissalDisabled: Bool {
    recovery != nil && isApplying
  }

  var applyButtonLabel: String {
    if recovery != nil { return L10n.Settings.ConfigImport.retryIdentityImport }
    switch (hasOverwriteSections, hasAdditiveSections) {
    case (false, true): return L10n.Settings.ConfigImport.applyButtonAdd
    case (true, false): return L10n.Settings.ConfigImport.applyButtonOverwrite
    default: return L10n.Settings.ConfigImport.applyButton
    }
  }

  var confirmMessage: String {
    if case let .recovery(summary, _) = confirmation {
      return L10n.Settings.ConfigImport.confirmIdentityRecovery("\(summary.sourceName) (\(summary.targetDescription))")
    }
    guard case let .config(source, _, _) = confirmation else { return "" }
    switch (hasOverwriteSections, hasAdditiveSections) {
    case (false, true): return L10n.Settings.ConfigImport.confirmMessageAdd(source.name)
    case (true, false): return L10n.Settings.ConfigImport.confirmMessageOverwrite(source.name)
    case (true, true): return L10n.Settings.ConfigImport.confirmMessageMixed(source.name)
    default: return L10n.Settings.ConfigImport.confirmMessage(source.name)
    }
  }

  func prepareConfirmation() {
    guard !isPreparingConfirmation, !isApplying, !isParsing else { return }
    if recovery != nil {
      prepareRecoveryConfirmation()
      return
    }
    guard let config = importedConfig, let service = nodeConfigService,
          let snapshot = connectionManager?.currentConnection,
          connectedDevice?.id == snapshot.device.id else { return }
    let source = ImportSource(snapshot)
    guard inputSource == nil || inputSource == source else { return }
    inputSource = source
    let selectedInputID = inputID
    let selectedSections = sections
    let previewID = UUID()
    currentPreviewID = previewID
    errorMessage = nil
    isPreparingConfirmation = true
    previewTask = Task {
      defer {
        if currentPreviewID == previewID { isPreparingConfirmation = false }
      }
      do {
        let preview = try await service.previewImport(config, sections: selectedSections)
        guard !Task.isCancelled, currentPreviewID == previewID, inputID == selectedInputID,
              connectionManager?.isCurrent(generation: source.generation, requestID: source.requestID) == true else { return }
        channelsWouldOverwrite = preview.channelsOverwriteExisting
        confirmation = .config(source, inputID: selectedInputID, sections: selectedSections)
        showConfirmation = true
      } catch is CancellationError {
        return
      } catch {
        guard currentPreviewID == previewID else { return }
        errorMessage = error.userFacingMessage
        logger.error("Import preview failed: \(error.localizedDescription)")
      }
    }
  }

  func cancelConfirmation() {
    confirmation = nil
    showConfirmation = false
  }

  func applyConfig() {
    guard !isApplying, let confirmation else { return }
    cancelConfirmation()
    switch confirmation {
    case let .recovery(summary, selectedInputID):
      applyIdentityRecovery(summary, selectedInputID: selectedInputID)
    case let .config(source, selectedInputID, selectedSections):
      guard inputID == selectedInputID, sections == selectedSections,
            connectionManager?.isCurrent(generation: source.generation, requestID: source.requestID) == true,
            let config = importedConfig, let service = nodeConfigService else { return }
      applyConfiguration(config, service: service, source: source, selectedSections: selectedSections)
    }
  }

  private func applyConfiguration(
    _ config: MeshCoreNodeConfig, service: NodeConfigService, source: ImportSource, selectedSections: ConfigSections
  ) {
    isApplying = true
    applyProgress = 0
    errorMessage = nil
    importComplete = false
    didApplyAnyWrite = false
    let selectedInputID = inputID
    importTask = Task {
      let (progressStream, progressContinuation) = AsyncStream.makeStream(of: ImportProgress.self)
      let consumer = Task { @MainActor in
        for await progress in progressStream {
          didApplyAnyWrite = true
          applyProgress = Double(progress.current) / Double(max(1, progress.total))
          applyStepDescription = Self.description(for: progress.step)
        }
      }
      do {
        try await service.importConfig(config, sections: selectedSections, radioID: source.radioID) {
          progressContinuation.yield($0)
        }
        progressContinuation.finish()
        await consumer.value
        if connectedDevice?.id == source.deviceID, let settingsService {
          try? await settingsService.refreshDeviceInfo()
        }
        isApplying = false
        importComplete = true
        try? await Task.sleep(for: Timing.resultDisplay)
        guard !Task.isCancelled, inputID == selectedInputID else { return }
        resetToFileSelection()
      } catch {
        progressContinuation.finish()
        await consumer.value
        isApplying = false
        if let interrupted = error as? IdentityImportRecoveryRequired {
          retainInterruptedInput(interrupted.recovery, selectedInputID: selectedInputID)
        } else if error is CancellationError {
          errorMessage = Self.cancellationMessage(didApplyAnyWrite: didApplyAnyWrite)
        } else {
          errorMessage = Self.failureMessage(for: error, didApplyAnyWrite: didApplyAnyWrite)
          logger.error("Import failed: \(error.localizedDescription)")
        }
      }
    }
  }

  func cancelImport() {
    importTask?.cancel()
  }

  private enum Timing {
    static let resultDisplay: Duration = .seconds(1.5)
  }

  static func description(for step: ImportStep) -> String {
    switch step {
    case .position: L10n.Settings.ConfigImport.stepPosition
    case .otherParameters: L10n.Settings.ConfigImport.stepOtherParameters
    case .privateKey: L10n.Settings.ConfigImport.stepPrivateKey
    case .nodeName: L10n.Settings.ConfigImport.stepNodeName
    case .radioParameters: L10n.Settings.ConfigImport.stepRadioParameters
    case .txPower: L10n.Settings.ConfigImport.stepTxPower
    case let .channel(name): L10n.Settings.ConfigImport.stepChannel(name)
    case let .contact(name): L10n.Settings.ConfigImport.stepContact(name)
    }
  }

  static func cancellationMessage(didApplyAnyWrite: Bool) -> String {
    didApplyAnyWrite ? L10n.Settings.ConfigImport.cancelledPartial : L10n.Settings.ConfigImport.cancelled
  }

  static func failureMessage(for error: Error, didApplyAnyWrite: Bool) -> String {
    didApplyAnyWrite ? L10n.Settings.ConfigImport.failedPartial(error.userFacingMessage) : error.userFacingMessage
  }
}
