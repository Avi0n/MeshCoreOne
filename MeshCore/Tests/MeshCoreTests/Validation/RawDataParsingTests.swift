import Foundation
@testable import MeshCore
import Testing

@Suite("RawData Parsing")
struct RawDataParsingTests {
  @Test
  func `rawData skips reserved byte`() {
    // Firmware format: [snr:1][rssi:1][reserved:1][payload...]
    var payload = Data()
    payload.append(0x28) // SNR: 40/4 = 10.0
    payload.append(0xAB) // RSSI: -85 (signed)
    payload.append(0xFF) // Reserved byte (should be skipped)
    payload.append(contentsOf: [0x01, 0x02, 0x03, 0x04]) // Actual payload

    let event = Parsers.RawData.parse(payload)

    guard case let .rawData(info) = event else {
      Issue.record("Expected rawData, got \(event)")
      return
    }

    #expect(abs(info.snr - 10.0) <= 0.001)
    #expect(info.rssi == -85)
    #expect(info.payload == Data([0x01, 0x02, 0x03, 0x04]),
            "Payload should not include reserved byte 0xFF")
  }

  @Test
  func `rawData rejects short payload`() {
    let shortPayload = Data([0x28, 0xAB]) // Only 2 bytes, need 3

    let event = Parsers.RawData.parse(shortPayload)

    guard case .parseFailure = event else {
      Issue.record("Expected parseFailure for short payload")
      return
    }
  }

  @Test
  func `rawData handles empty payload`() {
    // Minimum valid: snr + rssi + reserved = 3 bytes, no actual payload
    let payload = Data([0x28, 0xAB, 0xFF])

    let event = Parsers.RawData.parse(payload)

    guard case let .rawData(info) = event else {
      Issue.record("Expected rawData")
      return
    }

    #expect(info.payload.count == 0, "Should have empty payload")
  }
}
