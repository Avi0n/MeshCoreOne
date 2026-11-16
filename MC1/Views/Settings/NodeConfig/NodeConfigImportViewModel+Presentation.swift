import MC1Services
import SwiftUI

extension NodeConfigImportViewModel {
  var importedConfig: MeshCoreNodeConfig? {
    state.input?.config
  }

  var inputID: UUID? {
    state.input?.id
  }

  var inputSource: ImportSource? {
    state.input?.source
  }

  var inputReceiptID: UUID? {
    state.input == nil ? nil : recovery?.receiptID
  }

  var recovery: IdentityImportRecovery? {
    state.recovery
  }

  var isParsing: Bool {
    currentParseID != nil
  }

  var isPreparingConfirmation: Bool {
    if case .preparing = state { return true }
    return false
  }

  var isApplying: Bool {
    if case .applying = state { return true }
    return false
  }

  var importComplete: Bool {
    if case .completed = state { return recovery == nil }
    return false
  }

  var identityRecoveryComplete: Bool {
    if case .completed = state { return recovery != nil }
    return false
  }

  var confirmation: NodeConfigImportState.Confirmation? {
    guard case let .confirming(confirmation) = state else { return nil }
    return confirmation
  }

  var didApplyAnyWrite: Bool {
    state.application?.progress != nil
  }

  var applyProgress: Double {
    guard let progress = state.application?.progress else { return 0 }
    return Double(progress.current) / Double(max(1, progress.total))
  }

  var applyStepDescription: String {
    state.application?.progress.map { Self.description(for: $0.step) } ?? ""
  }

  private var hasOverwriteSections: Bool {
    let selectedSections: ConfigSections = if case let .config(preview) = confirmation {
      preview.sections
    } else {
      sections
    }
    return selectedSections.radioSettings || selectedSections.nodeIdentity || selectedSections.positionSettings
      || selectedSections.otherSettings || (selectedSections.channels && state.input?.channelsWouldOverwrite == true)
  }

  private var hasAdditiveSections: Bool {
    if case let .config(preview) = confirmation {
      return preview.sections.channels || preview.sections.contacts
    }
    return sections.channels || sections.contacts
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
    if case let .recovery(summary, _, _) = confirmation {
      return L10n.Settings.ConfigImport.confirmIdentityRecovery("\(summary.sourceName) (\(summary.targetDescription))")
    }
    guard case let .config(preview) = confirmation else { return "" }
    switch (hasOverwriteSections, hasAdditiveSections) {
    case (false, true): return L10n.Settings.ConfigImport.confirmMessageAdd(preview.source.name)
    case (true, false): return L10n.Settings.ConfigImport.confirmMessageOverwrite(preview.source.name)
    case (true, true): return L10n.Settings.ConfigImport.confirmMessageMixed(preview.source.name)
    default: return L10n.Settings.ConfigImport.confirmMessage(preview.source.name)
    }
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
