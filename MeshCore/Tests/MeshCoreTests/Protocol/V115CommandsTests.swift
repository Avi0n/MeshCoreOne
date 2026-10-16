import Foundation
@testable import MeshCore
import Testing

@Suite("v1.15.0 commands")
struct V115CommandsTests {
  // MARK: - sendChannelData

  @Test
  func `sendChannelData flood (default pathLength) format`() {
    let payload = Data([0xAA, 0xBB, 0xCC])
    let packet = PacketBuilder.sendChannelData(
      channelIndex: 2,
      dataType: 0xFFFF,
      payload: payload
    )

    #expect(packet[0] == 0x3E, "Command code")
    #expect(packet[1] == 0x02, "Channel index")
    #expect(packet[2] == 0xFF, "pathLength defaults to 0xFF (flood)")
    #expect(packet[3] == 0xFF, "data_type LE low byte")
    #expect(packet[4] == 0xFF, "data_type LE high byte")
    #expect(Data(packet[5...]) == payload, "Payload follows data_type")
    #expect(packet.count == 5 + payload.count)
  }

  @Test
  func `sendChannelData flood ignores pathBytes when pathLength == 0xFF`() {
    // If a caller supplies pathBytes but leaves pathLength at flood sentinel,
    // the path bytes must NOT be written on the wire — firmware expects
    // no path when path_len == 0xFF.
    let packet = PacketBuilder.sendChannelData(
      channelIndex: 0,
      dataType: 0xFFFF,
      payload: Data([0xAA]),
      pathLength: 0xFF,
      pathBytes: Data([0xDE, 0xAD, 0xBE, 0xEF])
    )
    #expect(packet.count == 5 + 1, "5-byte header (flood) + 1-byte payload; pathBytes dropped")
    #expect(packet[2] == 0xFF)
  }

  @Test
  func `sendChannelData direct-path format (1-byte hashes)`() {
    // pathLength 0x03: upper 2 bits = 0b00 -> hash_size = 1; lower 6 bits = 3 -> hash_count = 3.
    // Firmware's writePath consumes hash_count * hash_size = 3 bytes.
    let pathBytes = Data([0x11, 0x22, 0x33])
    let payload = Data([0x01, 0x02])
    let packet = PacketBuilder.sendChannelData(
      channelIndex: 0,
      dataType: 0x1234,
      payload: payload,
      pathLength: 0x03,
      pathBytes: pathBytes
    )

    #expect(packet[0] == 0x3E)
    #expect(packet[1] == 0x00, "Channel 0")
    #expect(packet[2] == 0x03, "Encoded pathLength byte is passed through verbatim")
    #expect(Data(packet[3..<6]) == pathBytes, "Path bytes follow pathLength")
    #expect(packet[6] == 0x34, "data_type LE low")
    #expect(packet[7] == 0x12, "data_type LE high")
    #expect(Data(packet[8...]) == payload, "Payload tail")
    #expect(packet.count == 8 + payload.count)
  }

  @Test
  func `sendChannelData direct-path format (2-byte hashes)`() {
    // pathLength 0x42: upper 2 bits = 0b01 -> hash_size = 2; lower 6 bits = 2 -> hash_count = 2.
    // Firmware's writePath consumes 2 * 2 = 4 bytes.
    let pathBytes = Data([0x11, 0x22, 0x33, 0x44])
    let packet = PacketBuilder.sendChannelData(
      channelIndex: 1,
      dataType: 0xFFFF,
      payload: Data([0xAA]),
      pathLength: 0x42,
      pathBytes: pathBytes
    )
    #expect(packet[2] == 0x42, "Encoded pathLength (hash_size=2, hash_count=2)")
    #expect(Data(packet[3..<7]) == pathBytes, "4 path bytes follow")
    #expect(packet[7] == 0xFF)
    #expect(packet[8] == 0xFF)
    #expect(Data(packet[9...]) == Data([0xAA]))
  }

  @Test
  func `sendChannelData passes caller-supplied pathBytes verbatim`() {
    // Builder is a dumb packer. If the caller lies about pathLength, firmware rejects;
    // we don't second-guess on our side. This test locks in that contract.
    let packet = PacketBuilder.sendChannelData(
      channelIndex: 0,
      dataType: 0xFFFF,
      payload: Data([0x00]),
      pathLength: 0x05,
      pathBytes: Data([0x01, 0x02]) // caller says 5, passes only 2
    )
    #expect(packet[2] == 0x05)
    #expect(Data(packet[3..<5]) == Data([0x01, 0x02]))
  }

  @Test
  func `sendChannelData clamps payload to 163 bytes`() {
    let payload = Data(repeating: 0x55, count: 200)
    let packet = PacketBuilder.sendChannelData(
      channelIndex: 1,
      dataType: 0x00FF,
      payload: payload
    )

    #expect(packet.count == 5 + PacketBuilder.channelDataMaxPayloadBytes,
            "Header (5 bytes, flood) + clamped payload")
    #expect(Data(packet[5...]) == Data(repeating: 0x55,
                                       count: PacketBuilder.channelDataMaxPayloadBytes))
  }

  // MARK: - setDefaultFloodScope

  @Test
  func `setDefaultFloodScope set format`() {
    let key = Data(repeating: 0xAB, count: 16)
    let packet = PacketBuilder.setDefaultFloodScope(name: "test", scopeKey: key)

    #expect(packet.count == 1 + 31 + 16, "Cmd + 31-byte padded name + 16-byte key")
    #expect(packet[0] == 0x3F, "Command code")
    #expect(Data(packet[1..<5]) == Data("test".utf8), "Name prefix")
    #expect(packet[5] == 0, "Name is null-padded after the UTF-8 bytes")
    #expect(Data(packet[32..<48]) == key, "Key tail")
  }

  @Test
  func `setDefaultFloodScope clear format`() {
    let packet = PacketBuilder.setDefaultFloodScope(name: "", scopeKey: Data())

    #expect(packet.count == 1, "Empty name + empty key encodes as single-byte clear")
    #expect(packet[0] == 0x3F)
  }

  @Test
  func `setDefaultFloodScope truncates long name to 30 bytes`() {
    let longName = String(repeating: "A", count: 50)
    let key = Data(repeating: 0xCD, count: 16)
    let packet = PacketBuilder.setDefaultFloodScope(name: longName, scopeKey: key)

    #expect(packet.count == 1 + 31 + 16)
    #expect(Data(packet[1..<31]) == Data(repeating: UInt8(ascii: "A"), count: 30),
            "First 30 bytes are 'A's")
    #expect(packet[31] == 0, "Byte 31 is the null terminator")
  }

  @Test
  func `setDefaultFloodScope pads short key to 16 bytes`() {
    let shortKey = Data([0x01, 0x02, 0x03])
    let packet = PacketBuilder.setDefaultFloodScope(name: "x", scopeKey: shortKey)

    #expect(packet.count == 1 + 31 + 16)
    #expect(Data(packet[32..<35]) == shortKey)
    #expect(packet[35] == 0, "Remaining key bytes are zero-padded")
  }

  @Test
  func `setDefaultFloodScope treats empty name as clear regardless of key`() {
    // Firmware rejects n == 0 at MyMesh.cpp:1896. An empty name with a non-empty key
    // would always fail; we normalise to the clear form instead of building a doomed packet.
    let packet = PacketBuilder.setDefaultFloodScope(
      name: "",
      scopeKey: Data(repeating: 0xAA, count: 16)
    )
    #expect(packet == Data([0x3F]), "Empty name always clears, even with a non-empty key")
  }

  @Test
  func `setDefaultFloodScope preserves codepoint boundary when truncating`() {
    // "🚀" is 4 UTF-8 bytes. 27 'A's + rocket = 31 bytes encoded; budget is 30 bytes
    // (reserving byte 30 as the null terminator). Builder must drop the rocket entirely
    // rather than split it into invalid UTF-8.
    let name = String(repeating: "A", count: 27) + "🚀"
    let key = Data(repeating: 0x01, count: 16)
    let packet = PacketBuilder.setDefaultFloodScope(name: name, scopeKey: key)

    let nameField = Data(packet[1..<32])
    let expected = Data(repeating: UInt8(ascii: "A"), count: 27) + Data(repeating: 0, count: 4)
    #expect(nameField == expected,
            "27 A's + 4 null-pad bytes; the 🚀 must be dropped, not split")
  }

  @Test
  func `setDefaultFloodScope with .disabled scope clears`() {
    // Exercises the FloodScope overload's `.disabled` branch — the raw-key tests
    // only cover the scopeKey-based overload, leaving this branch uncovered.
    let packet = PacketBuilder.setDefaultFloodScope(name: "ignored", scope: .disabled)
    #expect(packet == Data([0x3F]), "`.disabled` scope must short-circuit to single-byte clear")
  }

  // MARK: - getDefaultFloodScope

  @Test
  func `getDefaultFloodScope format`() {
    let packet = PacketBuilder.getDefaultFloodScope()

    #expect(packet.count == 1)
    #expect(packet[0] == 0x40, "Command code")
  }
}
