import Foundation
import os

extension Parsers {
  // MARK: - ContactMessage

  /// Parser for incoming direct messages.
  enum ContactMessage {
    /// Supported protocol versions for message parsing.
    enum Version { case v1, v3 }

    /// Parses a contact message.
    ///
    /// - Parameters:
    ///   - data: Raw message data.
    ///   - version: Protocol version (v1 or v3).
    /// - Returns: A `.contactMessageReceived` event or `.parseFailure`.
    ///
    /// ### Binary Format (v3)
    /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
    /// - Offset 1 (2 bytes): Reserved
    /// - Offset 3 (6 bytes): Sender Public Key Prefix
    /// - Offset 9 (1 byte): Path Length
    /// - Offset 10 (1 byte): Text Type
    /// - Offset 11 (4 bytes): Sender Timestamp (UInt32 LE)
    /// - Offset 15+ (N bytes): Message payload (UTF-8)
    static func parse(_ data: Data, version: Version) -> MeshEvent {
      var offset = 0
      var snr: Double?

      let minSize = version == .v3 ? PacketSize.contactMessageV3Minimum : PacketSize.contactMessageV1Minimum
      guard data.count >= minSize else {
        return .parseFailure(
          data: data,
          reason: "ContactMessage response too short: \(data.count) < \(minSize)"
        )
      }

      if version == .v3 {
        snr = Double(Int8(bitPattern: data[offset])) / 4.0
        offset += 1
        offset += 2 // reserved
      }

      let pubkeyPrefix = Data(data[offset..<offset + 6]); offset += 6
      let pathLen = data[offset]; offset += 1
      let txtType = data[offset]; offset += 1
      let timestamp = Date(timeIntervalSince1970: TimeInterval(data.readUInt32LE(at: offset))); offset += 4

      var signature: Data?
      if txtType == 2 {
        guard data.count >= offset + 4 else {
          return .parseFailure(
            data: data,
            reason: "ContactMessage signature truncated: \(data.count) < \(offset + 4)"
          )
        }
        signature = Data(data[offset..<offset + 4]); offset += 4
      }

      // Handle UTF-8 decoding with explicit failure logging
      let textData = Data(data[offset...])
      let text: String
      if let decoded = String(data: textData, encoding: .utf8) {
        text = decoded
      } else {
        parserLogger.warning("ContactMessage: Invalid UTF-8 in message payload, using lossy conversion")
        text = String(decoding: textData, as: UTF8.self) // Replaces invalid sequences with replacement char
      }

      return .contactMessageReceived(MeshCore.ContactMessage(
        senderPublicKeyPrefix: pubkeyPrefix,
        pathLength: pathLen,
        textType: txtType,
        senderTimestamp: timestamp,
        signature: signature,
        text: text,
        snr: snr
      ))
    }
  }

  // MARK: - ChannelMessage

  /// Parser for incoming channel (broadcast) messages.
  enum ChannelMessage {
    /// Supported protocol versions for message parsing.
    enum Version { case v1, v3 }

    /// Parses a channel message.
    ///
    /// - Parameters:
    ///   - data: Raw message data.
    ///   - version: Protocol version.
    /// - Returns: A `.channelMessageReceived` event or `.parseFailure`.
    ///
    /// ### Binary Format (v3)
    /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
    /// - Offset 1 (2 bytes): Reserved
    /// - Offset 3 (1 byte): Channel Index
    /// - Offset 4 (1 byte): Path Length
    /// - Offset 5 (1 byte): Text Type
    /// - Offset 6 (4 bytes): Sender Timestamp (UInt32 LE)
    /// - Offset 10+ (N bytes): Message payload (UTF-8)
    static func parse(_ data: Data, version: Version) -> MeshEvent {
      var offset = 0
      var snr: Double?

      let minSize = version == .v3 ? PacketSize.channelMessageV3Minimum : PacketSize.channelMessageV1Minimum
      guard data.count >= minSize else {
        return .parseFailure(
          data: data,
          reason: "ChannelMessage response too short: \(data.count) < \(minSize)"
        )
      }

      if version == .v3 {
        snr = Double(Int8(bitPattern: data[offset])) / 4.0
        offset += 1
        offset += 2 // reserved
      }

      let channelIndex = data[offset]; offset += 1
      let pathLen = data[offset]; offset += 1
      let txtType = data[offset]; offset += 1
      let timestamp = Date(timeIntervalSince1970: TimeInterval(data.readUInt32LE(at: offset))); offset += 4

      // Handle UTF-8 decoding
      let textData = Data(data[offset...])
      let text: String
      if let decoded = String(data: textData, encoding: .utf8) {
        text = decoded
      } else {
        parserLogger.warning("ChannelMessage: Invalid UTF-8 in message payload, using lossy conversion")
        text = String(decoding: textData, as: UTF8.self)
      }

      return .channelMessageReceived(MeshCore.ChannelMessage(
        channelIndex: channelIndex,
        pathLength: pathLen,
        textType: txtType,
        senderTimestamp: timestamp,
        text: text,
        snr: snr
      ))
    }
  }

  // MARK: - ChannelDatagram

  /// Parser for incoming channel binary datagrams. Firmware v11+ (MeshCore v1.15.0+).
  enum ChannelDatagram {
    /// Parses a channel datagram.
    ///
    /// ### Binary Format (offsets exclude the `0x1B` opcode byte, stripped by ``PacketParser``)
    /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
    /// - Offset 1 (2 bytes): Reserved
    /// - Offset 3 (1 byte): Channel Index
    /// - Offset 4 (1 byte): Path Length — `0xFF` = direct route; otherwise flood-accumulated path encoding
    /// - Offset 5 (2 bytes): Data Type (UInt16 LE)
    /// - Offset 7 (1 byte): Data Length
    /// - Offset 8+: Binary payload (length = data_len, clamped to remaining bytes)
    static func parse(_ data: Data) -> MeshEvent {
      guard data.count >= PacketSize.channelDatagramMinimum else {
        return .parseFailure(
          data: data,
          reason: "ChannelDatagram response too short: \(data.count) < \(PacketSize.channelDatagramMinimum)"
        )
      }

      // `PacketParser.parse` already normalises payloads via `Data(data.dropFirst())`,
      // so `data.startIndex == 0` here; offset-based subscripting is slice-safe.
      // Matches the convention used by `Parsers.ChannelMessage.parse`.
      var offset = 0
      let snr = Double(Int8(bitPattern: data[offset])) / 4.0
      offset += 1
      offset += 2 // reserved
      let channelIndex = data[offset]; offset += 1
      let pathLen = data[offset]; offset += 1
      let dataType = data.readUInt16LE(at: offset); offset += 2
      let declared = Int(data[offset]); offset += 1

      let remaining = data.count - offset
      let length = min(declared, remaining)
      let payload = Data(data[offset..<offset + length])

      return .channelDataReceived(MeshCore.ChannelDatagram(
        channelIndex: channelIndex,
        pathLength: pathLen,
        dataType: dataType,
        data: payload,
        snr: snr
      ))
    }
  }
}
