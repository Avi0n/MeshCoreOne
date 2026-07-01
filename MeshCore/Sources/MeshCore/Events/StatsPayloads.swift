import Foundation

/// Represents core device statistics.
///
/// Core stats (9 bytes payload, little-endian per Python reader.py):
/// - Bytes 0-1: UInt16 - battery_mv
/// - Bytes 2-5: UInt32 - uptime_secs
/// - Bytes 6-7: UInt16 - errors
/// - Byte 8: UInt8 - queue_len
public struct CoreStats: Sendable, Equatable {
  /// The battery level in millivolts.
  public let batteryMV: UInt16
  /// The device uptime in seconds.
  public let uptimeSeconds: UInt32
  /// Total count of errors encountered.
  public let errors: UInt16
  /// The current length of the transmit queue.
  public let queueLength: UInt8

  /// Initializes a new core statistics object.
  ///
  /// - Parameters:
  ///   - batteryMV: The battery level.
  ///   - uptimeSeconds: The uptime in seconds.
  ///   - errors: The error count.
  ///   - queueLength: The queue length.
  public init(batteryMV: UInt16, uptimeSeconds: UInt32, errors: UInt16, queueLength: UInt8) {
    self.batteryMV = batteryMV
    self.uptimeSeconds = uptimeSeconds
    self.errors = errors
    self.queueLength = queueLength
  }
}

/// Represents radio statistics.
///
/// Radio stats (12 bytes payload, little-endian per Python reader.py):
/// - Bytes 0-1: Int16 - noise_floor (dBm)
/// - Byte 2: Int8 - last_rssi (dBm)
/// - Byte 3: Int8 - last_snr (raw, divide by 4.0 for dB)
/// - Bytes 4-7: UInt32 - tx_air_secs
/// - Bytes 8-11: UInt32 - rx_air_secs
public struct RadioStats: Sendable, Equatable {
  /// The noise floor in dBm.
  public let noiseFloor: Int16
  /// The last received signal strength indicator in dBm.
  public let lastRSSI: Int8
  /// The last recorded signal-to-noise ratio.
  public let lastSNR: Double
  /// Total transmit airtime in seconds.
  public let txAirtimeSeconds: UInt32
  /// Total receive airtime in seconds.
  public let rxAirtimeSeconds: UInt32

  /// Initializes a new radio statistics object.
  ///
  /// - Parameters:
  ///   - noiseFloor: The noise floor.
  ///   - lastRSSI: The last RSSI.
  ///   - lastSNR: The last SNR.
  ///   - txAirtimeSeconds: Transmit airtime.
  ///   - rxAirtimeSeconds: Receive airtime.
  public init(
    noiseFloor: Int16,
    lastRSSI: Int8,
    lastSNR: Double,
    txAirtimeSeconds: UInt32,
    rxAirtimeSeconds: UInt32
  ) {
    self.noiseFloor = noiseFloor
    self.lastRSSI = lastRSSI
    self.lastSNR = lastSNR
    self.txAirtimeSeconds = txAirtimeSeconds
    self.rxAirtimeSeconds = rxAirtimeSeconds
  }
}

/// Represents packet statistics.
///
/// Packet stats (24 bytes payload, little-endian per Python reader.py):
/// - Bytes 0-3: UInt32 - recv
/// - Bytes 4-7: UInt32 - sent
/// - Bytes 8-11: UInt32 - flood_tx
/// - Bytes 12-15: UInt32 - direct_tx
/// - Bytes 16-19: UInt32 - flood_rx
/// - Bytes 20-23: UInt32 - direct_rx
/// - Bytes 24-27: UInt32 - recv_errors (optional, present when frame >= 28 bytes)
public struct PacketStats: Sendable, Equatable {
  /// Total packets received.
  public let received: UInt32
  /// Total packets sent.
  public let sent: UInt32
  /// Total flood packets transmitted.
  public let floodTx: UInt32
  /// Total direct packets transmitted.
  public let directTx: UInt32
  /// Total flood packets received.
  public let floodRx: UInt32
  /// Total direct packets received.
  public let directRx: UInt32
  /// Total RadioLib receive errors (CRC failures, malformed packets).
  public let receiveErrors: UInt32

  /// Initializes a new packet statistics object.
  public init(
    received: UInt32,
    sent: UInt32,
    floodTx: UInt32,
    directTx: UInt32,
    floodRx: UInt32,
    directRx: UInt32,
    receiveErrors: UInt32 = 0
  ) {
    self.received = received
    self.sent = sent
    self.floodTx = floodTx
    self.directTx = directTx
    self.floodRx = floodRx
    self.directRx = directRx
    self.receiveErrors = receiveErrors
  }
}
