import Foundation
@testable import MeshCore
import Testing

@Suite("ACK Parsing")
struct AckParsingTests {
  @Test
  func `4-byte ACK payload produces tripTime nil`() {
    let ackCode = Data([0xDE, 0xAD, 0xBE, 0xEF])
    // Build raw frame: [responseCode][ackCode]
    var frame = Data([ResponseCode.ack.rawValue])
    frame.append(ackCode)

    let event = PacketParser.parse(frame)
    guard case let .acknowledgement(code, tripTime) = event else {
      Issue.record("Expected .acknowledgement, got \(event)")
      return
    }
    #expect(code == ackCode)
    #expect(tripTime == nil)
  }

  @Test
  func `8-byte ACK payload parses trip time as UInt32 LE`() {
    let ackCode = Data([0xDE, 0xAD, 0xBE, 0xEF])
    // trip_time = 500ms = 0x000001F4 LE = [0xF4, 0x01, 0x00, 0x00]
    let tripTimeBytes = Data([0xF4, 0x01, 0x00, 0x00])

    var frame = Data([ResponseCode.ack.rawValue])
    frame.append(ackCode)
    frame.append(tripTimeBytes)

    let event = PacketParser.parse(frame)
    guard case let .acknowledgement(code, tripTime) = event else {
      Issue.record("Expected .acknowledgement, got \(event)")
      return
    }
    #expect(code == ackCode)
    #expect(tripTime == 500)
  }

  @Test
  func `3-byte ACK payload produces parseFailure`() {
    var frame = Data([ResponseCode.ack.rawValue])
    frame.append(Data([0x01, 0x02, 0x03])) // only 3 bytes, need 4

    let event = PacketParser.parse(frame)
    guard case .parseFailure = event else {
      Issue.record("Expected .parseFailure, got \(event)")
      return
    }
  }

  @Test
  func `5-7 byte ACK payload produces tripTime nil (no partial read)`() {
    for extraBytes in 1...3 {
      let ackCode = Data([0xDE, 0xAD, 0xBE, 0xEF])
      var frame = Data([ResponseCode.ack.rawValue])
      frame.append(ackCode)
      frame.append(Data(repeating: 0xFF, count: extraBytes)) // 5, 6, or 7 byte payload

      let event = PacketParser.parse(frame)
      guard case let .acknowledgement(code, tripTime) = event else {
        Issue.record("Expected .acknowledgement for \(4 + extraBytes)-byte payload, got \(event)")
        return
      }
      #expect(code == ackCode)
      #expect(tripTime == nil, "Payload of \(4 + extraBytes) bytes should not attempt partial trip time read")
    }
  }
}
