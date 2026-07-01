import MC1Services
import SwiftUI

/// Displays a radio parameter summary line, e.g. "915.000 MHz • BW125 kHz • SF12 • CR8".
/// Uses POSIX locale to always render a period decimal separator regardless of user locale.
struct RadioParameterText: View {
  let frequencyMHz: Double
  let bandwidthKHz: Double
  let spreadingFactor: UInt8
  let codingRate: UInt8

  var body: some View {
    Text(frequencyMHz.formatted(.number.precision(.fractionLength(3)).locale(.posix)))
      .font(.caption.monospacedDigit()) +
      Text(" MHz \u{2022} BW\(bandwidthKHz.formatted(.number.locale(.posix))) kHz \u{2022} SF\(spreadingFactor) \u{2022} CR\(codingRate)")
      .font(.caption)
  }
}
