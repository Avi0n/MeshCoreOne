import Foundation

extension Parsers {
  // MARK: - StatusResponse

  /// Parser for remote node status reports.
  enum StatusResponse {
    /// Parses remote node status (58 bytes).
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Reserved (skipped)
    /// - Offset 1 (6 bytes): Public Key Prefix
    /// - Offset 7 (2 bytes): Battery level in mV (UInt16 LE)
    /// - Offset 9 (2 bytes): Tx queue length (UInt16 LE)
    /// - Offset 11 (2 bytes): Noise floor (Int16 LE)
    /// - Offset 13 (2 bytes): Last RSSI (Int16 LE)
    /// - Offset 15 (8 bytes): Total packets recv/sent (UInt32 LE)
    /// - Offset 23 (8 bytes): Airtime/Uptime in seconds (UInt32 LE)
    /// - Offset 31 (16 bytes): Stats for flood/direct comms (UInt32 LE)
    /// - Offset 47 (2 bytes): Full events counter
    /// - Offset 49 (2 bytes): Last SNR scaled by 4 (Int16 LE)
    /// - Offset 51 (4 bytes): Duplicate counters
    /// - Offset 55 (4 bytes): Repeater: Rx airtime (UInt32 LE); Room server: posted count (UInt16 LE) + post-push count (UInt16 LE)
    /// - Offset 59 (4 bytes): Repeater only: Receive errors (UInt32 LE, optional)
    static func parse(_ data: Data, layout: MeshCore.StatusResponse.Layout = .repeater) -> MeshEvent {
      guard data.count >= PacketSize.statusResponseMinimum else {
        return .parseFailure(
          data: data,
          reason: "StatusResponse too short: \(data.count) < \(PacketSize.statusResponseMinimum)"
        )
      }

      var offset = 0
      offset += 1 // Skip reserved byte (per firmware and Python parsing.py)
      let pubkeyPrefix = Data(data[offset..<offset + 6]); offset += 6
      let battery = Int(data.readUInt16LE(at: offset)); offset += 2
      let txQueueLen = Int(data.readUInt16LE(at: offset)); offset += 2
      let noiseFloor = Int(data.readInt16LE(at: offset)); offset += 2
      let lastRSSI = Int(data.readInt16LE(at: offset)); offset += 2
      let packetsRecv = data.readUInt32LE(at: offset); offset += 4
      let packetsSent = data.readUInt32LE(at: offset); offset += 4
      let airtime = data.readUInt32LE(at: offset); offset += 4
      let uptime = data.readUInt32LE(at: offset); offset += 4
      let sentFlood = data.readUInt32LE(at: offset); offset += 4
      let sentDirect = data.readUInt32LE(at: offset); offset += 4
      let recvFlood = data.readUInt32LE(at: offset); offset += 4
      let recvDirect = data.readUInt32LE(at: offset); offset += 4
      let fullEvents = Int(data.readUInt16LE(at: offset)); offset += 2
      let lastSNR = Double(data.readInt16LE(at: offset)) / 4.0; offset += 2
      let directDups = Int(data.readUInt16LE(at: offset)); offset += 2
      let floodDups = Int(data.readUInt16LE(at: offset)); offset += 2
      switch layout {
      case .repeater:
        let rxAirtime = data.readUInt32LE(at: offset); offset += 4
        let receiveErrors: UInt32 = data.count >= offset + 4 ? data.readUInt32LE(at: offset) : 0

        return .statusResponse(MeshCore.StatusResponse(
          layout: .repeater,
          publicKeyPrefix: pubkeyPrefix,
          battery: battery,
          txQueueLength: txQueueLen,
          noiseFloor: noiseFloor,
          lastRSSI: lastRSSI,
          packetsReceived: packetsRecv,
          packetsSent: packetsSent,
          airtime: airtime,
          uptime: uptime,
          sentFlood: sentFlood,
          sentDirect: sentDirect,
          receivedFlood: recvFlood,
          receivedDirect: recvDirect,
          fullEvents: fullEvents,
          lastSNR: lastSNR,
          directDuplicates: directDups,
          floodDuplicates: floodDups,
          rxAirtime: rxAirtime,
          receiveErrors: receiveErrors
        ))

      case .roomServer:
        let postedCount: UInt16? = data.count >= offset + 4
          ? data.readUInt16LE(at: offset) : nil
        let postPushCount: UInt16? = data.count >= offset + 4
          ? data.readUInt16LE(at: offset + 2) : nil

        return .statusResponse(MeshCore.StatusResponse(
          layout: .roomServer,
          publicKeyPrefix: pubkeyPrefix,
          battery: battery,
          txQueueLength: txQueueLen,
          noiseFloor: noiseFloor,
          lastRSSI: lastRSSI,
          packetsReceived: packetsRecv,
          packetsSent: packetsSent,
          airtime: airtime,
          uptime: uptime,
          sentFlood: sentFlood,
          sentDirect: sentDirect,
          receivedFlood: recvFlood,
          receivedDirect: recvDirect,
          fullEvents: fullEvents,
          lastSNR: lastSNR,
          directDuplicates: directDups,
          floodDuplicates: floodDups,
          rxAirtime: 0,
          receiveErrors: 0,
          roomServerPostedCount: postedCount,
          roomServerPostPushCount: postPushCount
        ))
      }
    }

    /// Parses status data from a BINARY_RESPONSE (0x8C) payload.
    ///
    /// ### Binary Format (Format 2 - no pubkey header)
    /// Fields start at offset 0:
    /// - Offset 0 (2 bytes): Battery level in mV (UInt16 LE)
    /// - Offset 2 (2 bytes): Tx queue length (UInt16 LE)
    /// - Offset 4 (2 bytes): Noise floor (Int16 LE)
    /// - Offset 6 (2 bytes): Last RSSI (Int16 LE)
    /// - Offset 8 (4 bytes): Packets received (UInt32 LE)
    /// - Offset 12 (4 bytes): Packets sent (UInt32 LE)
    /// - Offset 16 (4 bytes): Airtime in seconds (UInt32 LE)
    /// - Offset 20 (4 bytes): Uptime in seconds (UInt32 LE)
    /// - Offset 24 (4 bytes): Sent flood (UInt32 LE)
    /// - Offset 28 (4 bytes): Sent direct (UInt32 LE)
    /// - Offset 32 (4 bytes): Received flood (UInt32 LE)
    /// - Offset 36 (4 bytes): Received direct (UInt32 LE)
    /// - Offset 40 (2 bytes): Full events counter (UInt16 LE)
    /// - Offset 42 (2 bytes): Last SNR scaled by 4 (Int16 LE)
    /// - Offset 44 (2 bytes): Direct duplicates (UInt16 LE)
    /// - Offset 46 (2 bytes): Flood duplicates (UInt16 LE)
    /// - Offset 48 (4 bytes): Rx airtime (UInt32 LE, optional)
    /// - Offset 52 (4 bytes): Receive errors (UInt32 LE, optional, v1.12+)
    ///
    /// - Parameters:
    ///   - data: Raw binary response payload (without the 4-byte tag).
    ///   - publicKeyPrefix: The 6-byte public key prefix from the pending request context.
    /// - Returns: A `StatusResponse` if parsing succeeds, `nil` otherwise.
    static func parseFromBinaryResponse(
      _ data: Data,
      publicKeyPrefix: Data,
      layout: MeshCore.StatusResponse.Layout = .repeater
    ) -> MeshCore.StatusResponse? {
      // Accept exactly 48 (no rxAirtime), 52 (with rxAirtime), or 56+ (with receiveErrors).
      // Reject malformed payloads with incomplete fields (49-51, 53-55).
      guard data.count == PacketSize.binaryResponseStatusBase ||
        data.count == PacketSize.binaryResponseStatusWithRxAirtime ||
        data.count >= PacketSize.binaryResponseStatusWithReceiveErrors else { return nil }

      var offset = 0
      let battery = Int(data.readUInt16LE(at: offset)); offset += 2
      let txQueueLen = Int(data.readUInt16LE(at: offset)); offset += 2
      let noiseFloor = Int(data.readInt16LE(at: offset)); offset += 2
      let lastRSSI = Int(data.readInt16LE(at: offset)); offset += 2
      let packetsRecv = data.readUInt32LE(at: offset); offset += 4
      let packetsSent = data.readUInt32LE(at: offset); offset += 4
      let airtime = data.readUInt32LE(at: offset); offset += 4
      let uptime = data.readUInt32LE(at: offset); offset += 4
      let sentFlood = data.readUInt32LE(at: offset); offset += 4
      let sentDirect = data.readUInt32LE(at: offset); offset += 4
      let recvFlood = data.readUInt32LE(at: offset); offset += 4
      let recvDirect = data.readUInt32LE(at: offset); offset += 4
      let fullEvents = Int(data.readUInt16LE(at: offset)); offset += 2
      let lastSNR = Double(data.readInt16LE(at: offset)) / 4.0; offset += 2
      let directDups = Int(data.readUInt16LE(at: offset)); offset += 2
      let floodDups = Int(data.readUInt16LE(at: offset)); offset += 2
      switch layout {
      case .repeater:
        let rxAirtime: UInt32 = data.count >= PacketSize.binaryResponseStatusWithRxAirtime
          ? data.readUInt32LE(at: offset) : 0
        offset += 4
        let receiveErrors: UInt32 = data.count >= PacketSize.binaryResponseStatusWithReceiveErrors
          ? data.readUInt32LE(at: offset) : 0

        return MeshCore.StatusResponse(
          layout: .repeater,
          publicKeyPrefix: publicKeyPrefix,
          battery: battery,
          txQueueLength: txQueueLen,
          noiseFloor: noiseFloor,
          lastRSSI: lastRSSI,
          packetsReceived: packetsRecv,
          packetsSent: packetsSent,
          airtime: airtime,
          uptime: uptime,
          sentFlood: sentFlood,
          sentDirect: sentDirect,
          receivedFlood: recvFlood,
          receivedDirect: recvDirect,
          fullEvents: fullEvents,
          lastSNR: lastSNR,
          directDuplicates: directDups,
          floodDuplicates: floodDups,
          rxAirtime: rxAirtime,
          receiveErrors: receiveErrors
        )

      case .roomServer:
        let postedCount: UInt16? = data.count >= PacketSize.binaryResponseStatusWithRxAirtime
          ? data.readUInt16LE(at: offset) : nil
        let postPushCount: UInt16? = data.count >= PacketSize.binaryResponseStatusWithRxAirtime
          ? data.readUInt16LE(at: offset + 2) : nil

        return MeshCore.StatusResponse(
          layout: .roomServer,
          publicKeyPrefix: publicKeyPrefix,
          battery: battery,
          txQueueLength: txQueueLen,
          noiseFloor: noiseFloor,
          lastRSSI: lastRSSI,
          packetsReceived: packetsRecv,
          packetsSent: packetsSent,
          airtime: airtime,
          uptime: uptime,
          sentFlood: sentFlood,
          sentDirect: sentDirect,
          receivedFlood: recvFlood,
          receivedDirect: recvDirect,
          fullEvents: fullEvents,
          lastSNR: lastSNR,
          directDuplicates: directDups,
          floodDuplicates: floodDups,
          rxAirtime: 0,
          receiveErrors: 0,
          roomServerPostedCount: postedCount,
          roomServerPostPushCount: postPushCount
        )
      }
    }
  }

  // MARK: - TelemetryResponse

  /// Parser for remote sensor telemetry.
  enum TelemetryResponse {
    /// Parses a telemetry push notification.
    ///
    /// ### Binary Format
    /// (Per firmware MyMesh.cpp push_telemetry_response)
    /// - Offset 0 (1 byte): Reserved
    /// - Offset 1 (6 bytes): Public key prefix
    /// - Offset 7 (N bytes): Raw LPP telemetry data
    static func parse(_ data: Data) -> MeshEvent {
      // Minimum: reserved(1) + pubkey(6) = 7 bytes
      guard data.count >= 7 else {
        return .parseFailure(data: data, reason: "TelemetryResponse too short: \(data.count) bytes, need 7")
      }

      // Skip reserved byte at offset 0
      let pubkeyPrefix = Data(data[1..<7])
      // LPP data starts at byte 7, no tag in push frames
      let rawData = Data(data.dropFirst(7))

      return .telemetryResponse(MeshCore.TelemetryResponse(
        publicKeyPrefix: pubkeyPrefix,
        tag: nil,
        rawData: rawData
      ))
    }

    /// Parses telemetry data from a BINARY_RESPONSE (0x8C) payload.
    ///
    /// ### Binary Format (Format 2 - no pubkey header)
    /// Raw LPP data starts at offset 0.
    ///
    /// - Parameters:
    ///   - data: Raw binary response payload (without the 4-byte tag).
    ///   - publicKeyPrefix: The 6-byte public key prefix from the pending request context.
    /// - Returns: A `TelemetryResponse` with the raw data for LPP decoding.
    static func parseFromBinaryResponse(_ data: Data, publicKeyPrefix: Data) -> MeshCore.TelemetryResponse {
      MeshCore.TelemetryResponse(
        publicKeyPrefix: publicKeyPrefix,
        tag: nil,
        rawData: data
      )
    }
  }
}
