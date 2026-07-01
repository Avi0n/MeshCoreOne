import MeshCore

public extension StatusResponse {
  /// Uptime in seconds (compatibility alias)
  var uptimeSeconds: UInt32 {
    uptime
  }

  /// Battery level in millivolts (compatibility conversion)
  var batteryMillivolts: UInt16 {
    UInt16(clamping: battery)
  }

  /// Last RSSI value (compatibility alias)
  var lastRssi: Int16 {
    Int16(clamping: lastRSSI)
  }

  /// Last SNR value (compatibility conversion)
  var lastSnr: Float {
    Float(lastSNR)
  }

  /// Repeater RX airtime in seconds (compatibility alias)
  var repeaterRxAirtimeSeconds: UInt32 {
    rxAirtime
  }
}
