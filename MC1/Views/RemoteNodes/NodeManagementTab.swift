import Foundation

/// Top-of-page segments on the repeater/room admin management page.
/// No raw value: the selection is in-memory `@State`, never persisted, so
/// `CaseIterable` (with the synthesized `Hashable`) is all the picker needs.
enum NodeManagementTab: CaseIterable {
  case settings
  case cli
  case telemetry

  var label: String {
    switch self {
    case .settings: L10n.RemoteNodes.RemoteNodes.Settings.Tab.settings
    case .cli: L10n.RemoteNodes.RemoteNodes.Settings.Tab.cli
    case .telemetry: L10n.RemoteNodes.RemoteNodes.Settings.Tab.telemetry
    }
  }
}
