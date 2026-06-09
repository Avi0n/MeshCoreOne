import SwiftUI

/// The set of diagnostic tools. Shared by the compact `ToolsView` (which pushes each as a
/// `NavigationLink`) and the iPad split columns (`ToolsContentColumn` list selection +
/// `ToolsDetailColumn` detail), and persisted as the active selection on `NavigationCoordinator`.
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

    /// Tools that collapse the iPad section's sidebar when open, reclaiming its width. Line of Sight
    /// swaps the content column for its analysis panel beside the detail map; Trace Path keeps the tool
    /// list in the content column and gives its own list/map view the freed width in the detail column.
    /// Other tools keep the sidebar's normal width-driven behavior.
    var prefersCollapsedSidebar: Bool {
        self == .lineOfSight || self == .tracePath
    }
}
