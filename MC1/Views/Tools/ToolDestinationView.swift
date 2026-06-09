import SwiftUI

/// Maps a `ToolSelection` to its tool view. Single source of truth for the five radio tools, shared
/// by the compact `ToolsView` (stack) and the iPad `ToolsDetailColumn` (split). Line of Sight differs
/// between the two — the compact stack shows the combined map-with-sheet layout from a fresh view
/// model, while the split shows only the map driven by the shared one — so its view is injected.
struct ToolDestinationView<LineOfSight: View>: View {
    let tool: ToolSelection
    @ViewBuilder let lineOfSight: () -> LineOfSight

    var body: some View {
        switch tool {
        case .tracePath: TracePathView()
        case .lineOfSight: lineOfSight()
        case .rxLog: RxLogView()
        case .noiseFloor: NoiseFloorView()
        case .nodeDiscovery: NodeDiscoveryView()
        case .cli: CLIToolView()
        }
    }
}
