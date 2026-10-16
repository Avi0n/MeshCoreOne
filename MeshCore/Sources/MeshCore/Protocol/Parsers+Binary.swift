import Foundation

extension Parsers {
  // MARK: - BinaryResponse

  /// Parser for generic binary protocol responses.
  enum BinaryResponse {
    /// Parses a generic binary response.
    ///
    /// - Parameter data: Raw response data.
    /// - Returns: A `.binaryResponse` event.
    ///
    /// Note: This returns a generic `.binaryResponse` event. The caller should
    /// use specialized parsers (like ``ACLParser`` or ``MMAParser``) to decode
    /// the `responseData` based on the request context.
    static func parse(_ data: Data) -> MeshEvent {
      // Binary response format:
      // - Byte 0: Request type (unused, skip)
      // - Bytes 1-4: Tag (matches expectedAck from messageSent)
      // - Bytes 5+: Response data
      guard data.count >= 5 else {
        return .parseFailure(data: data, reason: "BinaryResponse too short: \(data.count) < 5")
      }
      let tag = Data(data[1..<5])
      let responseData = Data(data.dropFirst(5))
      return .binaryResponse(tag: tag, data: responseData)
    }
  }

  // MARK: - PathDiscoveryResponse

  /// Parser for path discovery results.
  enum PathDiscoveryResponse {
    /// Parses a path discovery response.
    ///
    /// ### Binary Format
    /// (Per firmware MyMesh.cpp push_path_discovery_response)
    /// - Offset 0 (1 byte): Reserved
    /// - Offset 1 (6 bytes): Public key prefix
    /// - Offset 7 (1 byte): Outbound path length
    /// - Offset 8 (N bytes): Outbound path data
    /// - Offset 8+N (1 byte): Inbound path length
    /// - Offset 9+N (M bytes): Inbound path data
    static func parse(_ data: Data) -> MeshEvent {
      // Minimum: reserved(1) + pubkey(6) + out_path_len(1) + in_path_len(1) = 9 bytes
      guard data.count >= PacketSize.pathDiscoveryMinimum else {
        return .parseFailure(
          data: data,
          reason: "PathDiscoveryResponse too short: \(data.count) bytes, need \(PacketSize.pathDiscoveryMinimum)"
        )
      }

      // Skip reserved byte at offset 0
      let pubkeyPrefix = Data(data[1..<7])
      var offset = 7

      var outPathLength: UInt8 = 0
      var outPath = Data()
      var inPathLength: UInt8 = 0
      var inPath = Data()

      // Parse outbound path (multibyte encoded). Preserve the raw length
      // byte so the UI can display an accurate hop count without needing
      // the device's cached `hashSize`. A truncated payload where the
      // declared byte length runs past the end of `data` is surfaced as
      // `.parseFailure` so `PathInfo`'s size invariant holds.
      if data.count > offset {
        outPathLength = data[offset]
        offset += 1
        if let decoded = decodePathLen(outPathLength), decoded.byteLength > 0 {
          guard data.count >= offset + decoded.byteLength else {
            return .parseFailure(
              data: data,
              reason: "PathDiscoveryResponse truncated outbound path: need \(decoded.byteLength) bytes, have \(data.count - offset)"
            )
          }
          outPath = Data(data[offset..<offset + decoded.byteLength])
          offset += decoded.byteLength
        }
      }

      // Parse inbound path (multibyte encoded)
      if data.count > offset {
        inPathLength = data[offset]
        offset += 1
        if let decoded = decodePathLen(inPathLength), decoded.byteLength > 0 {
          guard data.count >= offset + decoded.byteLength else {
            return .parseFailure(
              data: data,
              reason: "PathDiscoveryResponse truncated inbound path: need \(decoded.byteLength) bytes, have \(data.count - offset)"
            )
          }
          inPath = Data(data[offset..<offset + decoded.byteLength])
        }
      }

      return .pathResponse(PathInfo(
        publicKeyPrefix: pubkeyPrefix,
        outPathLength: outPathLength,
        outPath: outPath,
        inPathLength: inPathLength,
        inPath: inPath
      ))
    }
  }

  // MARK: - ControlData

  /// Parser for low-level protocol control data.
  enum ControlData {
    /// Parses SNR, RSSI, and payload from a control packet.
    ///
    /// This parser automatically detects DISCOVER_RESP payloads (upper nibble 0x9)
    /// and returns a structured `.discoverResponse` event instead of raw `.controlData`.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
    /// - Offset 1 (1 byte): RSSI (Int8)
    /// - Offset 2 (1 byte): Path length
    /// - Offset 3 (1 byte): Payload type (upper nibble 0x9 = DISCOVER_RESP)
    /// - Offset 4+ (N bytes): Payload data
    ///
    /// ### DISCOVER_RESP Inner Payload Format
    /// - Offset 0 (1 byte): SNR in scaled by 4 (Int8)
    /// - Offset 1-4 (4 bytes): Tag (UInt32 LE)
    /// - Offset 5+ (8 or 32 bytes): Public key (prefix or full)
    static func parse(_ data: Data) -> MeshEvent {
      guard data.count >= PacketSize.controlDataMinimum else {
        return .parseFailure(
          data: data,
          reason: "ControlData too short: \(data.count) < \(PacketSize.controlDataMinimum)"
        )
      }
      let snr = Double(Int8(bitPattern: data[0])) / 4.0
      let rssi = Int(Int8(bitPattern: data[1]))
      let pathLen = data[2]
      let payloadType = data[3]
      let payload = Data(data.dropFirst(4))

      // Check for DISCOVER_RESP (upper nibble 0x9)
      // Minimum inner payload: snr_in(1) + tag(4) = 5 bytes
      if payloadType & 0xF0 == 0x90, payload.count >= 5 {
        let nodeType = payloadType & 0x0F
        let snrIn = Double(Int8(bitPattern: payload[0])) / 4.0
        let tag = Data(payload[1..<5])

        // Pubkey: 32 bytes if available, otherwise 8-byte prefix
        let pubkey = if payload.count >= 37 {
          Data(payload[5..<37])
        } else if payload.count >= 13 {
          Data(payload[5..<13])
        } else {
          Data(payload.dropFirst(5))
        }

        return .discoverResponse(DiscoverResponse(
          nodeType: nodeType,
          snrIn: snrIn,
          snr: snr,
          rssi: rssi,
          pathLength: pathLen,
          tag: tag,
          publicKey: pubkey
        ))
      }

      return .controlData(ControlDataInfo(
        snr: snr,
        rssi: rssi,
        pathLength: pathLen,
        payloadType: payloadType,
        payload: payload
      ))
    }
  }

  // MARK: - Signature

  /// Parser for cryptographic signature responses.
  enum Signature {
    /// Wraps the signature data in a `.signature` event.
    static func parse(_ data: Data) -> MeshEvent {
      .signature(data)
    }
  }
}
