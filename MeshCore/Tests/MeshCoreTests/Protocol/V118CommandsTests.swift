import Foundation
@testable import MeshCore
import Testing

@Suite("v1.18 commands")
struct V118CommandsTests {
  @Test
  func `runCliCommand format`() {
    let packet = PacketBuilder.runCliCommand("get cad")
    #expect(packet[0] == 0x42)
    #expect(packet.dropFirst() == Data("get cad".utf8))
  }

  @Test
  func `runCliCommand max UTF-8 length fills the companion frame`() {
    let command = String(repeating: "a", count: PacketBuilder.runCliCommandMaxUTF8Bytes)
    let packet = PacketBuilder.runCliCommand(command)
    #expect(packet.count == PacketBuilder.maxFrameSize)
  }
}
