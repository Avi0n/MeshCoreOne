import Foundation
@testable import MeshCore
import Testing

@Suite("NewResponse Parsing")
struct NewResponseParsingTests {
  @Test
  func `advertPathResponse parse`() {
    var payload = Data()
    payload.appendLittleEndian(UInt32(1_704_067_200)) // timestamp
    payload.append(0x03) // path length
    payload.append(contentsOf: [0x11, 0x22, 0x33]) // path

    let event = Parsers.AdvertPathResponse.parse(payload)

    guard case let .advertPathResponse(response) = event else {
      Issue.record("Expected advertPathResponse, got \(event)")
      return
    }

    #expect(response.recvTimestamp == 1_704_067_200)
    #expect(response.pathLength == 3)
    #expect(response.path == Data([0x11, 0x22, 0x33]))
  }

  @Test
  func `advertPathResponse empty path`() {
    var payload = Data()
    payload.appendLittleEndian(UInt32(1000))
    payload.append(0x00) // path length = 0

    let event = Parsers.AdvertPathResponse.parse(payload)

    guard case let .advertPathResponse(response) = event else {
      Issue.record("Expected advertPathResponse")
      return
    }

    #expect(response.pathLength == 0)
    #expect(response.path.count == 0)
  }

  @Test
  func `advertPathResponse too short`() {
    // Less than 5 bytes should fail
    let shortPayload = Data([0x01, 0x02, 0x03, 0x04])

    let event = Parsers.AdvertPathResponse.parse(shortPayload)

    guard case .parseFailure = event else {
      Issue.record("Expected parseFailure for short payload")
      return
    }
  }

  @Test
  func `advertPathResponse rejects reserved path length encoding`() {
    var payload = Data()
    payload.appendLittleEndian(UInt32(1_704_067_200))
    payload.append(0xC1) // mode 3 (reserved), hop count 1
    payload.append(0x11)

    let event = Parsers.AdvertPathResponse.parse(payload)

    guard case let .parseFailure(_, reason) = event else {
      Issue.record("Expected parseFailure for reserved path length, got \(event)")
      return
    }

    #expect(reason.contains("reserved path length encoding"))
  }

  @Test
  func `tuningParamsResponse parse`() {
    var payload = Data()
    // rx_delay_base * 1000 = 1500 (1.5ms)
    payload.appendLittleEndian(UInt32(1500))
    // airtime_factor * 1000 = 2500 (2.5)
    payload.appendLittleEndian(UInt32(2500))

    let event = Parsers.TuningParamsResponse.parse(payload)

    guard case let .tuningParamsResponse(response) = event else {
      Issue.record("Expected tuningParamsResponse, got \(event)")
      return
    }

    #expect(abs(response.rxDelayBase - 1.5) <= 0.001)
    #expect(abs(response.airtimeFactor - 2.5) <= 0.001)
  }

  @Test
  func `tuningParamsResponse too short`() {
    // Less than 8 bytes should fail
    let shortPayload = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07])

    let event = Parsers.TuningParamsResponse.parse(shortPayload)

    guard case .parseFailure = event else {
      Issue.record("Expected parseFailure for short payload")
      return
    }
  }

  @Test
  func `tuningParamsResponse zero values`() {
    var payload = Data()
    payload.appendLittleEndian(UInt32(0))
    payload.appendLittleEndian(UInt32(0))

    let event = Parsers.TuningParamsResponse.parse(payload)

    guard case let .tuningParamsResponse(response) = event else {
      Issue.record("Expected tuningParamsResponse")
      return
    }

    #expect(abs(response.rxDelayBase - 0.0) <= 0.001)
    #expect(abs(response.airtimeFactor - 0.0) <= 0.001)
  }
}
