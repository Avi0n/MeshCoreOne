import Foundation
@testable import MeshCore
import Testing

@Suite("NewCommands")
struct NewCommandsTests {
  @Test
  func `sendRawData format`() {
    let path = Data([0x11, 0x22])
    let payload = Data([0xAA, 0xBB, 0xCC])

    let packet = PacketBuilder.sendRawData(path: path, payload: payload)

    #expect(packet[0] == 0x19, "Command code")
    #expect(packet[1] == 0x02, "Path length")
    #expect(Data(packet[2..<4]) == path, "Path data")
    #expect(Data(packet[4...]) == payload, "Payload")
  }

  @Test
  func `sendRawData empty path`() {
    let packet = PacketBuilder.sendRawData(path: Data(), payload: Data([0xAA]))
    #expect(packet[1] == 0x00, "Empty path length")
    #expect(packet.count == 3, "command + pathLen + payload")
  }

  @Test
  func `sendRawData clamps to firmware limits`() {
    let path = Data(repeating: 0x11, count: 80)
    let payload = Data(repeating: 0x22, count: 220)

    let packet = PacketBuilder.sendRawData(path: path, payload: payload)

    #expect(packet[1] == 64, "Path length should clamp to firmware 64-byte limit")
    #expect(Data(packet[2..<66]) == Data(repeating: 0x11, count: 64), "Path bytes should be truncated to 64 bytes")
    #expect(Data(packet[66...]) == Data(repeating: 0x22, count: 184), "Payload should be truncated to firmware 184-byte limit")
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
