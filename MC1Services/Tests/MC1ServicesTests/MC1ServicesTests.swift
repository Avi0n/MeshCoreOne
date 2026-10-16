@testable import MC1Services
import Testing

@Suite("MC1Services Basic Tests")
struct MC1ServicesTests {
  @Test
  func `Version is accessible`() {
    #expect(MC1ServicesVersion.version == "0.1.0")
  }

  @Test
  func `MeshCore types are re-exported`() {
    // Verify MeshCore types are accessible without explicit import
    let _: MeshEvent.Type = MeshEvent.self
    let _: PacketBuilder.Type = PacketBuilder.self
    let _: PacketParser.Type = PacketParser.self
  }
}
