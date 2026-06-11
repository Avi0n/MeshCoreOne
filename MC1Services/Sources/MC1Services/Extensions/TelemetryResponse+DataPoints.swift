import MeshCore

extension TelemetryResponse {
    /// Decoded LPP data points from the raw telemetry data.
    /// Uses MeshCore's LPPDecoder to parse the raw bytes into structured sensor values.
    public var dataPoints: [LPPDataPoint] {
        LPPDecoder.decode(rawData)
    }
}
