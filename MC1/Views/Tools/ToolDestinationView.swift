import SwiftUI

struct ToolDestinationView: View {
  let tool: ToolSelection

  var body: some View {
    switch tool {
    case .tracePath: TracePathView()
    case .lineOfSight: LineOfSightView()
    case .rxLog: RxLogView()
    case .noiseFloor: NoiseFloorView()
    case .nodeDiscovery: NodeDiscoveryView()
    case .cli: CLIToolView()
    }
  }
}
