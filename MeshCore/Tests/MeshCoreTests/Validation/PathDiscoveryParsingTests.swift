import Foundation
@testable import MeshCore
import Testing

@Suite("PathDiscovery Parsing")
struct PathDiscoveryParsingTests {
  @Test
  func `pathDiscoveryResponse skips reserved byte`() {
    // Firmware format: [reserved:1][pubkey:6][out_len:1][out_path...][in_len:1][in_path...]
    var payload = Data()
    payload.append(0x00) // Reserved byte (should be skipped)
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]) // Pubkey prefix
    payload.append(0x02) // out_path_len = 2
    payload.append(contentsOf: [0x11, 0x22]) // out_path
    payload.append(0x03) // in_path_len = 3
    payload.append(contentsOf: [0x33, 0x44, 0x55]) // in_path

    let event = Parsers.PathDiscoveryResponse.parse(payload)

    guard case let .pathResponse(pathInfo) = event else {
      Issue.record("Expected pathResponse, got \(event)")
      return
    }

    #expect(pathInfo.publicKeyPrefix.hexString == "aabbccddeeff",
            "Pubkey should start at byte 1")
    #expect(pathInfo.outPath == Data([0x11, 0x22]),
            "Out path should be [0x11, 0x22]")
    #expect(pathInfo.inPath == Data([0x33, 0x44, 0x55]),
            "In path should be [0x33, 0x44, 0x55]")
  }

  @Test
  func `pathDiscoveryResponse handles empty paths`() {
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66]) // Pubkey
    payload.append(0x00) // out_path_len = 0
    payload.append(0x00) // in_path_len = 0

    let event = Parsers.PathDiscoveryResponse.parse(payload)

    guard case let .pathResponse(pathInfo) = event else {
      Issue.record("Expected pathResponse")
      return
    }

    #expect(pathInfo.outPath.count == 0)
    #expect(pathInfo.inPath.count == 0)
  }

  @Test
  func `pathDiscoveryResponse rejects short payload`() {
    let shortPayload = Data([0x00, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE]) // Only 6 bytes

    let event = Parsers.PathDiscoveryResponse.parse(shortPayload)

    guard case .parseFailure = event else {
      Issue.record("Expected parseFailure for short payload")
      return
    }
  }

  @Test
  func `pathDiscoveryResponse preserves mode-1 out_path_len byte`() {
    // 0x42 = 0b01_000010 → mode 1, 2 hops, 4 bytes on wire
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66]) // Pubkey prefix
    payload.append(0x42) // out_path_len
    payload.append(contentsOf: [0xA1, 0xB2, 0xC3, 0xD4]) // 2 hops × 2 bytes
    payload.append(0x00) // in_path_len = 0
    payload.append(0x00) // in reserved

    let event = Parsers.PathDiscoveryResponse.parse(payload)

    guard case let .pathResponse(pathInfo) = event else {
      Issue.record("Expected pathResponse, got \(event)")
      return
    }

    #expect(pathInfo.outPathLength == 0x42,
            "Wire byte should be preserved verbatim on PathInfo")
    #expect(pathInfo.outHopCount == 2,
            "Mode-1 with 4 bytes should resolve to 2 hops")
    #expect(pathInfo.outPath == Data([0xA1, 0xB2, 0xC3, 0xD4]))
  }

  @Test
  func `pathDiscoveryResponse preserves mode-2 out_path_len byte`() {
    // 0x83 = 0b10_000011 → mode 2, 3 hops, 9 bytes on wire
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(contentsOf: [0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC]) // Pubkey
    payload.append(0x83) // out_path_len
    payload.append(contentsOf: [
      0x01, 0x02, 0x03,
      0x04, 0x05, 0x06,
      0x07, 0x08, 0x09
    ]) // 3 hops × 3 bytes
    payload.append(0x00) // in_path_len = 0

    let event = Parsers.PathDiscoveryResponse.parse(payload)

    guard case let .pathResponse(pathInfo) = event else {
      Issue.record("Expected pathResponse")
      return
    }

    #expect(pathInfo.outPathLength == 0x83)
    #expect(pathInfo.outHopCount == 3,
            "Mode-2 with 9 bytes should resolve to 3 hops")
    #expect(pathInfo.outPath.count == 9)
  }

  @Test
  func `pathDiscoveryResponse preserves both out and in length bytes`() {
    // Out: mode 0 (1B/hop), 2 hops → pathLength = 0x02, 2 bytes
    // In:  mode 2 (3B/hop), 1 hop  → pathLength = 0x81, 3 bytes
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(contentsOf: [0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01]) // Pubkey
    payload.append(0x02)
    payload.append(contentsOf: [0xA0, 0xA1])
    payload.append(0x81)
    payload.append(contentsOf: [0xB0, 0xB1, 0xB2])

    let event = Parsers.PathDiscoveryResponse.parse(payload)

    guard case let .pathResponse(pathInfo) = event else {
      Issue.record("Expected pathResponse")
      return
    }

    #expect(pathInfo.outPathLength == 0x02)
    #expect(pathInfo.outHopCount == 2)
    #expect(pathInfo.inPathLength == 0x81)
    #expect(pathInfo.inHopCount == 1)
  }

  @Test
  func `pathDiscoveryResponse outHopCount is nil for reserved mode`() {
    // 0xC0 = mode 3 (reserved) — parser preserves the byte but decodePathLen returns nil
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
    payload.append(0xC0) // Reserved mode — no bytes consumed after
    payload.append(0x00) // in_path_len = 0

    let event = Parsers.PathDiscoveryResponse.parse(payload)

    guard case let .pathResponse(pathInfo) = event else {
      Issue.record("Expected pathResponse")
      return
    }

    #expect(pathInfo.outPathLength == 0xC0,
            "Reserved byte should be preserved verbatim for diagnostics")
    #expect(pathInfo.outHopCount == nil,
            "Reserved mode yields nil hopCount so UI can fall back")
  }
}
