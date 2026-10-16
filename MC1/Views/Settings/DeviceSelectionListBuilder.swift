import Foundation
import MC1Services

/// Connectable saved radios plus the caller-supplied needs-setup list.
/// Does not re-diff ASK ids minus saved.
enum DeviceSelectionListBuilder {
  struct Result: Equatable {
    var connectable: [DeviceDTO]
    var needsSetup: [SystemPairedAccessory]
  }

  /// VoiceOver and trailing-control copy for a needs-setup Previously Paired row.
  struct NeedsSetupRowPresentation: Equatable {
    let trailingTitle: String
    let accessibilityLabel: String
    let accessibilityHint: String
  }

  static func make(
    saved: [DeviceDTO],
    accessories: [(id: UUID, name: String)],
    needsSetup: [SystemPairedAccessory],
    hasSystemPairingRegistry: Bool
  ) -> Result {
    let pairedIDs = Set(accessories.map(\.id))
    let connectable = saved.filter {
      DeviceSelectionFilter.isConnectable(
        $0,
        pairedAccessoryIDs: pairedIDs,
        hasSystemPairingRegistry: hasSystemPairingRegistry
      )
    }
    return Result(
      connectable: connectable,
      needsSetup: hasSystemPairingRegistry ? needsSetup : []
    )
  }

  static func needsSetupPresentation(name: String) -> NeedsSetupRowPresentation {
    NeedsSetupRowPresentation(
      trailingTitle: L10n.Settings.DeviceSelection.setup,
      accessibilityLabel: L10n.Settings.DeviceSelection.Accessibility.setupLabel(name),
      accessibilityHint: L10n.Settings.DeviceSelection.Accessibility.setupHint
    )
  }
}
