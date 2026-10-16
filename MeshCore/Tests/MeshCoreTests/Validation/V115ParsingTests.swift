import Foundation
@testable import MeshCore
import Testing

@Suite("v1.15.0 parsing")
struct V115ParsingTests {
  // MARK: - ChannelDatagram parser

  @Test
  func `channelDatagram parses valid payload`() {
    // Frame after PacketParser strips the 0x1B response byte:
    // [snr:1][rsv:1][rsv:1][channel:1][path_len:1][data_type LE:2][data_len:1][data...]
    let snrByte = Int8(20) // 20 / 4 = 5.0 dB
    let data: [UInt8] = [
      UInt8(bitPattern: snrByte), 0x00, 0x00, // snr + reserved
      0x03, // channel index
      0xFF, // path_len: 0xFF means direct route
      0xFF, 0xFF, // data_type LE 0xFFFF (DEV)
      0x04, // data_len
      0xDE, 0xAD, 0xBE, 0xEF, // payload
    ]

    let event = Parsers.ChannelDatagram.parse(Data(data))
    guard case let .channelDataReceived(dg) = event else {
      Issue.record("Expected .channelDataReceived, got \(event)")
      return
    }
    #expect(dg.channelIndex == 3)
    #expect(dg.pathLength == 0xFF)
    #expect(dg.dataType == 0xFFFF)
    #expect(dg.data == Data([0xDE, 0xAD, 0xBE, 0xEF]))
    #expect(dg.snr == 5.0)
  }

  @Test
  func `channelDatagram routes via PacketParser`() {
    let data: [UInt8] = [
      0x1B, // response code
      0x00, 0x00, 0x00, // snr + reserved
      0x00, // channel 0
      0x03, // path_len: flood-accumulated, 3 hops × 1-byte hashes
      0x12, 0x34, // data_type LE 0x3412
      0x02, // data_len
      0xAA, 0xBB,
    ]

    let event = PacketParser.parse(Data(data))
    guard case let .channelDataReceived(dg) = event else {
      Issue.record("Expected routed .channelDataReceived, got \(event)")
      return
    }
    #expect(dg.pathLength == 0x03)
    #expect(dg.dataType == 0x3412)
    #expect(dg.data == Data([0xAA, 0xBB]))
  }

  @Test
  func `channelDatagram rejects truncated payload`() {
    let data = Data([0x00, 0x00, 0x00, 0x01, 0xFF]) // missing data_type+data_len+data
    let event = Parsers.ChannelDatagram.parse(data)
    if case .parseFailure = event {
      // ok
    } else {
      Issue.record("Expected .parseFailure for truncated payload, got \(event)")
    }
  }

  @Test
  func `channelDatagram truncates when declared data_len exceeds remaining bytes`() {
    // Firmware emits data_len == actual payload; if declared length is larger than
    // what's on the wire, we clamp to what's actually there rather than parseFailure,
    // since firmware guarantees the frame framing but a buggy peer could lie.
    let data: [UInt8] = [
      0x00, 0x00, 0x00,
      0x01,
      0xFF,
      0xFF, 0xFF,
      0x10, // claims 16 bytes
      0xAA, 0xBB, // only 2 actually follow
    ]
    let event = Parsers.ChannelDatagram.parse(Data(data))
    guard case let .channelDataReceived(dg) = event else {
      Issue.record("Expected datagram, got \(event)")
      return
    }
    #expect(dg.data == Data([0xAA, 0xBB]))
  }

  // MARK: - DefaultFloodScope parser

  @Test
  func `defaultFloodScope parses empty payload as null`() {
    let event = Parsers.DefaultFloodScope.parse(Data())
    guard case let .defaultFloodScope(scope) = event else {
      Issue.record("Expected .defaultFloodScope, got \(event)")
      return
    }
    #expect(scope == nil, "Empty payload means no default scope configured")
  }

  @Test
  func `defaultFloodScope parses populated payload`() {
    var payload = Data()
    var nameBytes = Array("Europe".utf8)
    while nameBytes.count < 31 {
      nameBytes.append(0)
    }
    payload.append(contentsOf: nameBytes)
    payload.append(Data(repeating: 0x7E, count: 16))

    let event = Parsers.DefaultFloodScope.parse(payload)
    guard case let .defaultFloodScope(scope) = event, let scope else {
      Issue.record("Expected populated .defaultFloodScope, got \(event)")
      return
    }
    #expect(scope.name == "Europe")
    #expect(scope.scopeKey == Data(repeating: 0x7E, count: 16))
  }

  @Test
  func `defaultFloodScope routes via PacketParser`() {
    var wire = Data([0x1C])
    var nameBytes = Array("ch".utf8)
    while nameBytes.count < 31 {
      nameBytes.append(0)
    }
    wire.append(contentsOf: nameBytes)
    wire.append(Data(repeating: 0x01, count: 16))

    let event = PacketParser.parse(wire)
    guard case let .defaultFloodScope(scope) = event else {
      Issue.record("Expected routed .defaultFloodScope, got \(event)")
      return
    }
    #expect(scope?.name == "ch")
  }

  @Test
  func `defaultFloodScope rejects partial payload`() {
    let event = Parsers.DefaultFloodScope.parse(Data(repeating: 0x00, count: 20))
    if case .parseFailure = event {
      // ok
    } else {
      Issue.record("Expected parseFailure for short payload, got \(event)")
    }
  }
}
