import Foundation
@testable import MeshCore
import Testing

@Suite("NewCommands")
struct NewCommandsTests {
  @Test
  func `sendRawData writes a 2-byte hash path of 3 hops`() throws {
    let path = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
    let payload = Data([0xAA, 0xBB, 0xCC])

    let packet = try PacketBuilder.sendRawData(pathLength: 0x43, path: path, payload: payload)

    #expect(packet[0] == 0x19, "Command code")
    #expect(packet[1] == 0x43, "Encoded path length")
    #expect(Data(packet[2..<8]) == path, "6 path bytes")
    #expect(Data(packet[8...]) == payload, "Payload")
  }

  @Test
  func `sendRawData writes no path bytes for zero hops`() throws {
    let packet = try PacketBuilder.sendRawData(pathLength: 0x00, path: Data(), payload: Data([0xAA]))
    #expect(packet[1] == 0x00, "1-byte hashes, 0 hops")
    #expect(packet.count == 3, "command + pathLen + payload")
    #expect(packet[2] == 0xAA)
  }

  @Test
  func `sendRawData writes 63 path bytes for 3-byte hashes and 21 hops`() throws {
    let path = Data(repeating: 0x11, count: 63)
    let packet = try PacketBuilder.sendRawData(pathLength: 0x95, path: path, payload: Data([0xAA]))
    #expect(packet[1] == 0x95)
    #expect(Data(packet[2..<65]) == path)
    #expect(packet[65] == 0xAA)
  }

  @Test
  func `sendRawData throws when the path is shorter than the encoded length`() {
    let error = #expect(throws: MeshCoreError.self) {
      try PacketBuilder.sendRawData(pathLength: 0x43, path: Data(repeating: 0x11, count: 4), payload: Data([0xAA]))
    }
    guard case .invalidInput? = error else {
      Issue.record("Expected invalidInput, got \(String(describing: error))")
      return
    }
  }

  @Test(arguments: [(UInt8(0), 0, 174), (7, 7, 167), (0x44, 8, 166), (0x83, 9, 165)])
  func `sendRawData enforces the host frame budget`(pathLength: UInt8, pathCount: Int, payloadCount: Int) throws {
    let path = Data(repeating: 0x11, count: pathCount + 3)
    let payload = Data(repeating: 0x22, count: payloadCount)
    let packet = try PacketBuilder.sendRawData(pathLength: pathLength, path: path, payload: payload)
    #expect(packet.count == 176)
    #expect(Data(packet.suffix(payloadCount)) == payload)

    let error = #expect(throws: MeshCoreError.self) {
      try PacketBuilder.sendRawData(pathLength: pathLength, path: path, payload: payload + Data([0x22]))
    }
    guard case .invalidInput? = error else {
      Issue.record("Expected invalidInput")
      return
    }
  }

  @Test
  func `hasConnection format`() {
    let pubkey = Data(repeating: 0xAA, count: 32)

    let packet = PacketBuilder.hasConnection(publicKey: pubkey)

    #expect(packet.count == 33, "1 + 32 bytes")
    #expect(packet[0] == 0x1C, "Command code")
    #expect(Data(packet[1...]) == pubkey, "Public key")
  }

  @Test
  func `hasConnection pads short public key to protocol width`() {
    let pubkey = Data(repeating: 0xAA, count: 31)

    let packet = PacketBuilder.hasConnection(publicKey: pubkey)

    #expect(packet.count == 33, "Packet should still contain a full 32-byte key field")
    #expect(Data(packet[1..<32]) == Data(repeating: 0xAA, count: 31))
    #expect(packet[32] == 0x00, "Short public keys should be zero-padded at the builder layer")
  }

  @Test
  func `getContactByKey format`() {
    let pubkey = Data(repeating: 0xBB, count: 32)

    let packet = PacketBuilder.getContactByKey(publicKey: pubkey)

    #expect(packet.count == 33, "1 + 32 bytes")
    #expect(packet[0] == 0x1E, "Command code")
    #expect(Data(packet[1...]) == pubkey, "Public key")
  }

  @Test
  func `getContactByKey pads short public key to protocol width`() {
    let pubkey = Data(repeating: 0xBB, count: 31)

    let packet = PacketBuilder.getContactByKey(publicKey: pubkey)

    #expect(packet.count == 33, "Packet should still contain a full 32-byte key field")
    #expect(Data(packet[1..<32]) == Data(repeating: 0xBB, count: 31))
    #expect(packet[32] == 0x00)
  }

  @Test
  func `getAdvertPath format`() {
    let pubkey = Data(repeating: 0xCC, count: 32)

    let packet = PacketBuilder.getAdvertPath(publicKey: pubkey)

    #expect(packet.count == 34, "1 + 1 + 32 bytes")
    #expect(packet[0] == 0x2A, "Command code")
    #expect(packet[1] == 0x00, "Reserved byte")
    #expect(Data(packet[2...]) == pubkey, "Public key")
  }

  @Test
  func `getAdvertPath pads short public key to protocol width`() {
    let pubkey = Data(repeating: 0xCC, count: 31)

    let packet = PacketBuilder.getAdvertPath(publicKey: pubkey)

    #expect(packet.count == 34, "Packet should still contain reserved byte plus a full 32-byte key field")
    #expect(Data(packet[2..<33]) == Data(repeating: 0xCC, count: 31))
    #expect(packet[33] == 0x00)
  }

  @Test
  func `getTuningParams format`() {
    let packet = PacketBuilder.getTuningParams()

    #expect(packet.count == 1)
    #expect(packet[0] == 0x2B, "Command code")
  }

  // MARK: - setPathHashMode

  @Test(arguments: [
    (mode: UInt8(0), label: "1-byte hashes"),
    (mode: UInt8(1), label: "2-byte hashes"),
    (mode: UInt8(2), label: "3-byte hashes"),
  ])
  func `setPathHashMode mode 0 (1-byte hashes)`(mode: UInt8, label: String) {
    let packet = PacketBuilder.setPathHashMode(mode)

    #expect(packet.count == 3, "Packet should be exactly 3 bytes: cmd + reserved + mode")
    #expect(packet[0] == 0x3D, "Command code should be setPathHashMode (0x3D)")
    #expect(packet[1] == 0x00, "Reserved byte at offset 1 should be 0x00")
    #expect(packet[2] == mode, "Mode byte at offset 2 should be \(mode) (\(label))")
  }

  @Test
  func `setPathHashMode clamps reserved values to max supported mode`() {
    let packet = PacketBuilder.setPathHashMode(3)

    #expect(packet.count == 3)
    #expect(packet[2] == 2, "Reserved path hash modes should clamp to the max supported mode")
  }
}
