import Foundation

public extension Parsers {
  // MARK: - AdvertPathResponse

  /// Parser for advertisement path responses.
  ///
  /// ### Binary Format
  /// - Offset 0-3 (4 bytes): Receive timestamp (UInt32 LE)
  /// - Offset 4 (1 byte): Path length
  /// - Offset 5+ (N bytes): Path data (length = pathLength)
  enum AdvertPathResponse {
    /// Parses an advertisement path response.
    ///
    /// - Parameter data: Raw response data.
    /// - Returns: An `.advertPathResponse` event or `.parseFailure`.
    static func parse(_ data: Data) -> MeshEvent {
      guard data.count >= 5 else {
        return .parseFailure(
          data: data,
          reason: "AdvertPathResponse too short: \(data.count) bytes, need 5"
        )
      }

      let timestamp = data.readUInt32LE(at: 0)
      let pathLen = data[4]
      guard let decoded = decodePathLen(pathLen) else {
        return .parseFailure(
          data: data,
          reason: "AdvertPathResponse uses reserved path length encoding: 0x\(String(format: "%02X", pathLen))"
        )
      }
      let byteLen = decoded.byteLength
      guard data.count >= 5 + byteLen else {
        return .parseFailure(
          data: data,
          reason: "AdvertPathResponse path truncated: \(data.count) < \(5 + byteLen)"
        )
      }
      let path = Data(data.dropFirst(5).prefix(byteLen))

      return .advertPathResponse(MeshCore.AdvertPathResponse(
        recvTimestamp: timestamp,
        pathLength: pathLen,
        path: path
      ))
    }
  }

  // MARK: - TuningParamsResponse

  /// Parser for tuning parameters responses.
  ///
  /// ### Binary Format
  /// - Offset 0-3 (4 bytes): RX delay base * 1000 (UInt32 LE)
  /// - Offset 4-7 (4 bytes): Airtime factor * 1000 (UInt32 LE)
  enum TuningParamsResponse {
    /// Parses a tuning parameters response.
    ///
    /// - Parameter data: Raw response data.
    /// - Returns: A `.tuningParamsResponse` event or `.parseFailure`.
    static func parse(_ data: Data) -> MeshEvent {
      guard data.count >= 8 else {
        return .parseFailure(
          data: data,
          reason: "TuningParamsResponse too short: \(data.count) bytes, need 8"
        )
      }

      let rxDelayRaw = data.readUInt32LE(at: 0)
      let airtimeRaw = data.readUInt32LE(at: 4)

      return .tuningParamsResponse(MeshCore.TuningParamsResponse(
        rxDelayBase: Double(rxDelayRaw) / 1000.0,
        airtimeFactor: Double(airtimeRaw) / 1000.0
      ))
    }
  }

  // MARK: - AllowedRepeatFreq

  /// Parser for allowed repeat frequency ranges (v9+).
  ///
  /// ### Binary Format
  /// Sequence of 8-byte pairs:
  /// - Offset N (4 bytes): Lower frequency in kHz (UInt32 LE)
  /// - Offset N+4 (4 bytes): Upper frequency in kHz (UInt32 LE)
  enum AllowedRepeatFreq {
    /// Parses allowed frequency ranges.
    static func parse(_ data: Data) -> MeshEvent {
      var ranges: [FrequencyRange] = []
      var offset = 0
      while offset + 8 <= data.count {
        let lower = data.readUInt32LE(at: offset)
        let upper = data.readUInt32LE(at: offset + 4)
        ranges.append(FrequencyRange(lowerKHz: lower, upperKHz: upper))
        offset += 8
      }
      return .allowedRepeatFreq(ranges)
    }
  }
}
