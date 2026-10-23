import Foundation
@testable import MeshCore
import Testing

@Suite("v1.18 parsing")
struct V118ParsingTests {
  @Test
  func `cliReply routes via PacketParser`() {
    var wire = Data([0x1D])
    wire.append(contentsOf: "OK".utf8)
    let event = PacketParser.parse(wire)
    guard case let .cliReply(text) = event else {
      Issue.record("Expected .cliReply, got \(event)")
      return
    }
    #expect(text == "OK")
  }

  @Test
  func `empty cliReply payload parses as empty string`() {
    let event = PacketParser.parse(Data([0x1D]))
    guard case let .cliReply(text) = event else {
      Issue.record("Expected empty cliReply, got \(event)")
      return
    }
    #expect(text.isEmpty)
  }
}
