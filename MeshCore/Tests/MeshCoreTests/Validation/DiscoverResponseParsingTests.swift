import Foundation
@testable import MeshCore
import Testing

@Suite("DiscoverResponse Parsing")
struct DiscoverResponseParsingTests {
  @Test
  func `controlData parses discover response`() {
    // Control data format: [snr:1][rssi:1][pathLen:1][payloadType:1][payload...]
    // DISCOVER_RESP payload: [snr_in:1][tag:4][pubkey:8 or 32]
    var payload = Data()
    payload.append(0x28) // SNR: 10.0 (40 / 4.0)
    payload.append(0xAB) // RSSI: -85 (signed)
    payload.append(0x02) // path length
    payload.append(0x95) // payloadType: 0x90 | 0x05 (DISCOVER_RESP, nodeType=5)
    // DISCOVER_RESP inner payload
    payload.append(0x14) // snr_in: 5.0 (20 / 4.0)
    payload.appendLittleEndian(UInt32(12345)) // tag
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88]) // 8-byte prefix

    let event = Parsers.ControlData.parse(payload)

    guard case let .discoverResponse(response) = event else {
      Issue.record("Expected discoverResponse, got \(event)")
      return
    }

    #expect(response.nodeType == 5)
    #expect(abs(response.snrIn - 5.0) <= 0.001)
    #expect(abs(response.snr - 10.0) <= 0.001)
    #expect(response.rssi == -85)
    #expect(response.pathLength == 2)
    #expect(response.tag == Data([0x39, 0x30, 0x00, 0x00])) // 12345 in LE
    #expect(response.publicKey.hexString == "1122334455667788")
  }

  @Test
  func `controlData parses full pubkey`() {
    var payload = Data()
    payload.append(0x28) // SNR
    payload.append(0xAB) // RSSI
    payload.append(0x01) // path length
    payload.append(0x91) // DISCOVER_RESP, nodeType=1
    payload.append(0x28) // snr_in
    payload.appendLittleEndian(UInt32(999)) // tag
    payload.append(Data(repeating: 0xAA, count: 32)) // full 32-byte pubkey

    let event = Parsers.ControlData.parse(payload)

    guard case let .discoverResponse(response) = event else {
      Issue.record("Expected discoverResponse")
      return
    }

    #expect(response.publicKey.count == 32)
    #expect(response.publicKey == Data(repeating: 0xAA, count: 32))
  }

  @Test
  func `controlData non-discover returns raw`() {
    var payload = Data()
    payload.append(0x28) // SNR
    payload.append(0xAB) // RSSI
    payload.append(0x01) // path length
    payload.append(0x80) // payloadType: NODE_DISCOVER_REQ (not RESP)
    payload.append(contentsOf: [0x01, 0x02, 0x03]) // some payload

    let event = Parsers.ControlData.parse(payload)

    guard case let .controlData(info) = event else {
      Issue.record("Expected controlData, got \(event)")
      return
    }

    #expect(info.payloadType == 0x80)
    #expect(info.payload == Data([0x01, 0x02, 0x03]))
  }

  @Test
  func `controlData discover resp too short falls back to controlData`() {
    // DISCOVER_RESP with insufficient payload (less than 5 bytes for inner payload)
    var payload = Data()
    payload.append(0x28) // SNR
    payload.append(0xAB) // RSSI
    payload.append(0x01) // path length
    payload.append(0x91) // DISCOVER_RESP
    payload.append(contentsOf: [0x01, 0x02, 0x03, 0x04]) // Only 4 bytes (need at least 5)

    let event = Parsers.ControlData.parse(payload)

    // Should fall back to controlData since inner payload is too short
    guard case let .controlData(info) = event else {
      Issue.record("Expected controlData for short DISCOVER_RESP payload, got \(event)")
      return
    }

    #expect(info.payloadType == 0x91)
  }
}
