import MeshCore

extension StatusResponse {
    /// Uptime in seconds (compatibility alias)
    public var uptimeSeconds: UInt32 { uptime }

    /// Battery level in millivolts (compatibility conversion)
    public var batteryMillivolts: UInt16 { UInt16(clamping: battery) }

    /// Last RSSI value (compatibility alias)
    public var lastRssi: Int16 { Int16(clamping: lastRSSI) }

    /// Last SNR value (compatibility conversion)
    public var lastSnr: Float { Float(lastSNR) }

    /// Repeater RX airtime in seconds (compatibility alias)
    public var repeaterRxAirtimeSeconds: UInt32 { rxAirtime }
}
