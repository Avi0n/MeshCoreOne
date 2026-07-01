import Foundation
@testable import MeshCore
import Testing

@Suite("Telemetry Parsing")
struct TelemetryParsingTests {
  @Test
  func `telemetryResponse skips reserved byte`() {
    // Firmware format: [reserved:1][pubkey_prefix:6][lpp_data...]
    var payload = Data()
    payload.append(0x00) // Reserved byte (should be skipped)
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]) // Pubkey prefix
    payload.append(contentsOf: [0x01, 0x67, 0x00, 0xFA]) // LPP: channel 1, temp, 25.0C

    let event = Parsers.TelemetryResponse.parse(payload)

    guard case let .telemetryResponse(response) = event else {
      Issue.record("Expected telemetryResponse, got \(event)")
      return
    }

    #expect(response.publicKeyPrefix.hexString == "aabbccddeeff",
            "Pubkey should start at byte 1, not byte 0")
    #expect(response.tag == nil, "Push telemetry should have no tag")
    #expect(response.rawData == Data([0x01, 0x67, 0x00, 0xFA]),
            "LPP data should start at byte 7")
  }

  @Test
  func `telemetryResponse rejects short payload`() {
    let shortPayload = Data([0x00, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE]) // Only 6 bytes

    let event = Parsers.TelemetryResponse.parse(shortPayload)

    guard case .parseFailure = event else {
      Issue.record("Expected parseFailure for short payload")
      return
    }
  }

  @Test
  func `telemetryResponse handles empty LPP data`() {
    // Minimum valid: reserved + pubkey = 7 bytes, no LPP data
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66]) // Pubkey

    let event = Parsers.TelemetryResponse.parse(payload)

    guard case let .telemetryResponse(response) = event else {
      Issue.record("Expected telemetryResponse")
      return
    }

    #expect(response.rawData.count == 0, "Should have empty LPP data")
  }
}
