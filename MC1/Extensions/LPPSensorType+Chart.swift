import MeshCore
import SwiftUI

extension LPPSensorType {
  /// Chart accent color for telemetry history views.
  var chartColor: Color {
    switch self {
    case .voltage: .orange
    case .temperature: .red
    case .humidity: .teal
    case .barometer: .purple
    case .illuminance: .yellow
    case .current: .mint
    case .power: .pink
    case .frequency: .blue
    case .altitude, .distance: .green
    case .energy: .orange
    case .direction: .indigo
    case .percentage: .cyan
    default: .cyan
    }
  }

  /// Sort priority for telemetry charts (lower = earlier).
  var chartSortPriority: Int {
    switch self {
    case .voltage: 0
    default: 1
    }
  }
}
