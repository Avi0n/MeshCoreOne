import Foundation
@testable import MeshCore
import Testing

@Suite("TraceData Parsing")
struct TraceDataParsingTests {
  @Test
  func `traceData pathSz=0 single byte hashes`() {
    // path_sz=0: 1-byte hashes, pathLength = hop count
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(0x02) // pathLength = 2 hashes
    payload.append(0x00) // flags: path_sz = 0
    payload.appendLittleEndian(UInt32(12345)) // tag
    payload.appendLittleEndian(UInt32(67890)) // authCode
    payload.append(contentsOf: [0xAA, 0xBB]) // 2 hash bytes
    payload.append(contentsOf: [0x28, 0x14]) // 2 SNR bytes (10.0, 5.0)
    payload.append(0x0C) // final SNR (3.0)

    let event = Parsers.TraceData.parse(payload)

    guard case let .traceData(trace) = event else {
      Issue.record("Expected traceData, got \(event)")
      return
    }

    #expect(trace.tag == 12345)
    #expect(trace.authCode == 67890)
    #expect(trace.path.count == 3, "Should have 2 hops + 1 destination")

    // Check hash bytes
    #expect(trace.path[0].hashBytes == Data([0xAA]))
    #expect(trace.path[1].hashBytes == Data([0xBB]))
    #expect(trace.path[2].hashBytes == nil, "Destination has no hash")

    // Check SNRs
    #expect(abs(trace.path[0].snr - 10.0) <= 0.001)
    #expect(abs(trace.path[1].snr - 5.0) <= 0.001)
    #expect(abs(trace.path[2].snr - 3.0) <= 0.001)
  }

  @Test
  func `traceData pathSz=2 four byte hashes`() {
    // path_sz=2: 4-byte hashes, hopCount = pathLength / 4
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(0x08) // pathLength = 8 hash bytes = 2 hops
    payload.append(0x02) // flags: path_sz = 2 (means 4 bytes per hash)
    payload.appendLittleEndian(UInt32(111)) // tag
    payload.appendLittleEndian(UInt32(222)) // authCode
    // 8 hash bytes (2 hops x 4 bytes)
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44]) // hop 0
    payload.append(contentsOf: [0x55, 0x66, 0x77, 0x88]) // hop 1
    // 2 SNR bytes (one per hop)
    payload.append(contentsOf: [0x28, 0x14]) // SNRs: 10.0, 5.0
    payload.append(0x0C) // final SNR: 3.0

    let event = Parsers.TraceData.parse(payload)

    guard case let .traceData(trace) = event else {
      Issue.record("Expected traceData, got \(event)")
      return
    }

    #expect(trace.path.count == 3, "Should have 2 hops + 1 destination")

    // Check 4-byte hashes
    #expect(trace.path[0].hashBytes == Data([0x11, 0x22, 0x33, 0x44]))
    #expect(trace.path[1].hashBytes == Data([0x55, 0x66, 0x77, 0x88]))
    #expect(trace.path[2].hashBytes == nil)

    // Legacy hash accessor (first byte only)
    #expect(trace.path[0].hash == 0x11)
  }

  @Test
  func `traceData pathSz=1 two byte hashes`() {
    // path_sz=1: 2-byte hashes, hopCount = pathLength / 2
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(0x04) // pathLength = 4 hash bytes = 2 hops
    payload.append(0x01) // flags: path_sz = 1 (means 2 bytes per hash)
    payload.appendLittleEndian(UInt32(100)) // tag
    payload.appendLittleEndian(UInt32(200)) // authCode
    // 4 hash bytes (2 hops x 2 bytes)
    payload.append(contentsOf: [0xAA, 0xBB]) // hop 0
    payload.append(contentsOf: [0xCC, 0xDD]) // hop 1
    // 2 SNR bytes (one per hop)
    payload.append(contentsOf: [0x28, 0x14]) // SNRs: 10.0, 5.0
    payload.append(0x0C) // final SNR: 3.0

    let event = Parsers.TraceData.parse(payload)

    guard case let .traceData(trace) = event else {
      Issue.record("Expected traceData, got \(event)")
      return
    }

    #expect(trace.path.count == 3, "Should have 2 hops + 1 destination")

    // Check 2-byte hashes
    #expect(trace.path[0].hashBytes == Data([0xAA, 0xBB]))
    #expect(trace.path[1].hashBytes == Data([0xCC, 0xDD]))
    #expect(trace.path[2].hashBytes == nil)
  }

  @Test
  func `traceData destination marker`() {
    // 0xFF hash means destination (no hash)
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(0x01) // pathLength = 1
    payload.append(0x00) // flags: path_sz = 0
    payload.appendLittleEndian(UInt32(1)) // tag
    payload.appendLittleEndian(UInt32(2)) // authCode
    payload.append(0xFF) // hash = 0xFF (destination marker)
    payload.append(0x28) // SNR
    payload.append(0x14) // final SNR

    let event = Parsers.TraceData.parse(payload)

    guard case let .traceData(trace) = event else {
      Issue.record("Expected traceData")
      return
    }

    // 0xFF hash should be interpreted as destination (nil)
    #expect(trace.path[0].hashBytes == nil)
  }

  @Test
  func `traceData empty path`() {
    // pathLength = 0 means direct connection (no intermediate hops)
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(0x00) // pathLength = 0
    payload.append(0x00) // flags: path_sz = 0
    payload.appendLittleEndian(UInt32(999)) // tag
    payload.appendLittleEndian(UInt32(888)) // authCode
    payload.append(0x28) // final SNR only

    let event = Parsers.TraceData.parse(payload)

    guard case let .traceData(trace) = event else {
      Issue.record("Expected traceData, got \(event)")
      return
    }

    #expect(trace.path.count == 1, "Should have destination only")
    #expect(trace.path[0].hashBytes == nil, "Destination has no hash")
    #expect(abs(trace.path[0].snr - 10.0) <= 0.001)
  }

  @Test
  func `traceData legacy hash accessor`() {
    // Verify legacy hash property works correctly
    var payload = Data()
    payload.append(0x00) // Reserved
    payload.append(0x01) // pathLength = 1
    payload.append(0x00) // flags: path_sz = 0
    payload.appendLittleEndian(UInt32(1)) // tag
    payload.appendLittleEndian(UInt32(2)) // authCode
    payload.append(0x42) // hash
    payload.append(0x28) // SNR
    payload.append(0x14) // final SNR

    let event = Parsers.TraceData.parse(payload)

    guard case let .traceData(trace) = event else {
      Issue.record("Expected traceData")
      return
    }

    // Legacy accessor should return first byte
    #expect(trace.path[0].hash == 0x42)
    #expect(trace.path[1].hash == nil, "Destination has no hash")
  }

  @Test
  func `traceData too short payload`() {
    // Less than 11 bytes should fail
    let shortPayload = Data([0x00, 0x01, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07])

    let event = Parsers.TraceData.parse(shortPayload)

    guard case .parseFailure = event else {
      Issue.record("Expected parseFailure for short payload")
      return
    }
  }

  @Test
  func `TraceNode init with hashBytes`() {
    let node = TraceNode(hashBytes: Data([0x11, 0x22, 0x33]), snr: 5.5)
    #expect(node.hashBytes == Data([0x11, 0x22, 0x33]))
    #expect(node.snr == 5.5)
    #expect(node.hash == 0x11, "Legacy accessor returns first byte")
  }

  @Test
  func `TraceNode init with nil hashBytes`() {
    let node = TraceNode(hashBytes: nil, snr: 3.0)
    #expect(node.hashBytes == nil)
    #expect(node.hash == nil)
    #expect(node.snr == 3.0)
  }

  @Test
  func `TraceNode legacy init with hash`() {
    let node = TraceNode(hash: 0xAB, snr: 7.5)
    #expect(node.hashBytes == Data([0xAB]))
    #expect(node.hash == 0xAB)
    #expect(node.snr == 7.5)
  }

  @Test
  func `TraceNode legacy init with nil hash`() {
    let node = TraceNode(hash: nil, snr: 2.0)
    #expect(node.hashBytes == nil)
    #expect(node.hash == nil)
    #expect(node.snr == 2.0)
  }
}
