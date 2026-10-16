import MeshCore

public extension TelemetryResponse {
  /// Decoded LPP data points from the raw telemetry data.
  /// Uses MeshCore's LPPDecoder to parse the raw bytes into structured sensor values.
  var dataPoints: [LPPDataPoint] {
    LPPDecoder.decode(rawData)
  }
}
