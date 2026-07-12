import Foundation
import os

extension Parsers {
  // MARK: - DefaultFloodScope

  /// Parser for the persisted default flood scope. Firmware v11+ (MeshCore v1.15.0+).
  enum DefaultFloodScope {
    /// Parses the default flood scope response.
    ///
    /// ### Binary Format (offsets exclude the `0x1C` opcode byte, stripped by ``PacketParser``)
    /// - Empty payload (0 bytes): No default scope configured, emits `.defaultFloodScope(nil)`.
    /// - Populated (exactly 47 bytes): `[name:31 zero-padded UTF-8][key:16]`.
    ///
    /// Firmware emits exactly 0 or 47 bytes (`MyMesh.cpp:1915-1917`); other lengths
    /// indicate protocol drift and fall through to `parseFailure`.
    static func parse(_ data: Data) -> MeshEvent {
      if data.isEmpty {
        return .defaultFloodScope(nil)
      }
      guard data.count == PacketSize.defaultFloodScopeSet else {
        return .parseFailure(
          data: data,
          reason: "DefaultFloodScope response wrong size: \(data.count), expected 0 or \(PacketSize.defaultFloodScopeSet)"
        )
      }

      // `PacketParser.parse` zero-aligns the payload; use `data` directly,
      // matching the sibling `Parsers.ChannelMessage.parse` convention.
      var offset = 0
      let nameField = Data(data[offset..<(offset + PacketSize.defaultFloodScopeNameField)])
      offset += PacketSize.defaultFloodScopeNameField
      let nullIdx = nameField.firstIndex(of: 0) ?? nameField.endIndex
      let nameBytes = Data(nameField[..<nullIdx])

      // Mirror `ContactMessage` / `ChannelMessage` lossy-UTF-8 handling so a corrupt
      // name doesn't silently misclassify a populated scope as null — the scope itself
      // is set, only the display name is garbled.
      let name: String
      if let decoded = String(data: nameBytes, encoding: .utf8) {
        name = decoded
      } else {
        parserLogger.warning("DefaultFloodScope: Invalid UTF-8 in name field, using lossy conversion")
        name = String(decoding: nameBytes, as: UTF8.self)
      }

      let key = Data(data[offset..<(offset + PacketSize.defaultFloodScopeKeyBytes)])

      return .defaultFloodScope(MeshCore.DefaultFloodScope(name: name, scopeKey: key))
    }
  }

  // MARK: - ChannelInfo

  /// Parser for channel configuration data.
  enum ChannelInfo {
    /// Parses channel index, name, and PSK secret.
    ///
    /// The channel name is a null-terminated C string in a 32-byte buffer.
    /// Bytes after the null terminator may be uninitialized garbage from the firmware,
    /// so we must find the null and decode only the bytes before it.
    static func parse(_ data: Data) -> MeshEvent {
      guard data.count >= PacketSize.channelInfoMinimum else {
        return .parseFailure(
          data: data,
          reason: "ChannelInfo too short: \(data.count) < \(PacketSize.channelInfoMinimum)"
        )
      }
      let index = data[0]
      let nameData = data[1..<33]

      // Find first null byte - firmware uses strcpy which leaves garbage after the null
      let nullIndex = nameData.firstIndex(of: 0) ?? nameData.endIndex
      let validNameData = nameData[nameData.startIndex..<nullIndex]
      let name = String(decoding: validNameData, as: UTF8.self)

      let secret = Data(data[33..<49])

      return .channelInfo(MeshCore.ChannelInfo(
        index: index,
        name: name,
        secret: secret
      ))
    }
  }
}
