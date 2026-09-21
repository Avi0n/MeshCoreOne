import SwiftUI

/// Diagnostic tools. `ToolsView` pushes each as a `NavigationLink`; the
/// selected case lives on `NavigationCoordinator` so it survives tab switches.
enum ToolSelection: Hashable, CaseIterable {
  case tracePath
  case lineOfSight
  case rxLog
  case noiseFloor
  case nodeDiscovery
  case cli

  var title: String {
    switch self {
    case .tracePath: L10n.Tools.Tools.tracePath
    case .lineOfSight: L10n.Tools.Tools.lineOfSight
    case .rxLog: L10n.Tools.Tools.rxLog
    case .noiseFloor: L10n.Tools.Tools.noiseFloor
    case .nodeDiscovery: L10n.Tools.Tools.nodeDiscovery
    case .cli: L10n.Tools.Tools.cli
    }
  }

  var systemImage: String {
    switch self {
    case .tracePath: "point.3.connected.trianglepath.dotted"
    case .lineOfSight: "eye"
    case .rxLog: "waveform.badge.magnifyingglass"
    case .noiseFloor: "waveform"
    case .nodeDiscovery: "dot.radiowaves.left.and.right"
    case .cli: "terminal"
    }
  }

  /// Line of Sight runs its analysis offline; every other tool needs a connected radio.
  var requiresRadio: Bool {
    self != .lineOfSight
  }
}
