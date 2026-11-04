import Foundation
import MC1Services

/// A telemetry response accepted for one public key, with channel 0 removed.
struct TelemetryReading {
  private static let publicKeyPrefixSize = 6
  private static let ignoredChannel: UInt8 = 0

  let dataPoints: [LPPDataPoint]
  let entries: [TelemetrySnapshotEntry]
  let location: NodeLocationFix?

  static func accepted(from response: TelemetryResponse, expectedPublicKey: Data) -> TelemetryReading? {
    guard response.publicKeyPrefix == expectedPublicKey.prefix(publicKeyPrefixSize) else { return nil }
    let dataPoints = response.dataPoints.filter { $0.channel != ignoredChannel }
    let entries: [TelemetrySnapshotEntry] = dataPoints.compactMap { point in
      let numericValue: Double? = switch point.value {
      case let .float(value):
        value
      case let .integer(value):
        Double(value)
      default:
        nil
      }
      guard let numericValue else { return nil }
      return TelemetrySnapshotEntry(channel: Int(point.channel), type: point.typeName, value: numericValue)
    }
    return TelemetryReading(
      dataPoints: dataPoints,
      entries: entries,
      location: NodeLocationFix.primaryFix(from: dataPoints)
    )
  }
}
