import SwiftUI

/// Signal-strength glyph shared by the device pickers (`DeviceScannerSheet`, `DeviceSelectionSheet`)
/// so both render the `cellularbars` symbol with identical `RSSITuning` fill and tint.
struct SignalBars: View {
  let tier: RSSITuning.SignalTier
  /// When set, the glyph announces this label to VoiceOver; when `nil`, the glyph is hidden from
  /// VoiceOver so the enclosing row can announce the tier itself.
  var accessibilityLabel: String?

  var body: some View {
    Image(systemName: "cellularbars", variableValue: RSSITuning.fillLevel(forTier: tier))
      .foregroundStyle(RSSITuning.color(forTier: tier))
      .font(.body)
      .accessibilityHidden(accessibilityLabel == nil)
      .accessibilityLabel(accessibilityLabel ?? "")
  }

  /// VoiceOver descriptor for a signal tier, shared by both device pickers so the
  /// tier-to-text mapping and its localized strings live in one place.
  static func accessibilityDescription(forTier tier: RSSITuning.SignalTier) -> String {
    switch tier {
    case .strong: L10n.Localizable.Accessibility.SignalStrength.strong
    case .medium: L10n.Localizable.Accessibility.SignalStrength.medium
    case .weak: L10n.Localizable.Accessibility.SignalStrength.weak
    }
  }
}
