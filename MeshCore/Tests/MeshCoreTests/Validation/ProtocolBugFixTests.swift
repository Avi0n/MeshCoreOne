import Foundation
@testable import MeshCore
import Testing

/// Tests that verify protocol bugs stay fixed.
/// These tests encode the specific byte-level expectations from firmware/Python.
@Suite("Protocol Bug Fixes")
struct ProtocolBugFixTests {
  // MARK: - Bug A: appStart Alignment

  @Test
  func `appStart client ID starts at byte 8`() {
    let packet = PacketBuilder.appStart(clientId: "Test")

    // Bytes 0-1: command + subtype
    #expect(packet[0] == 0x01, "Byte 0 should be command code 0x01")
    #expect(packet[1] == 0x03, "Byte 1 should be subtype 0x03")

    // Bytes 2-7: reserved (spaces = 0x20)
    #expect(packet[2] == 0x20, "Byte 2 should be space (reserved)")
    #expect(packet[3] == 0x20, "Byte 3 should be space (reserved)")
    #expect(packet[4] == 0x20, "Byte 4 should be space (reserved)")
    #expect(packet[5] == 0x20, "Byte 5 should be space (reserved)")
    #expect(packet[6] == 0x20, "Byte 6 should be space (reserved)")
    #expect(packet[7] == 0x20, "Byte 7 should be space (reserved)")

    // Bytes 8+: client ID
    let clientIdBytes = Data(packet[8...])
    let clientId = String(data: clientIdBytes, encoding: .utf8)
    #expect(clientId == "Test", "Client ID should start at byte 8")
  }

  @Test
  func `appStart truncates long client ID`() {
    // Client IDs longer than 5 chars should be truncated
    let packet = PacketBuilder.appStart(clientId: "LongClientName")

    // Should only have first 5 characters
    let clientIdBytes = Data(packet[8...])
    let clientId = String(data: clientIdBytes, encoding: .utf8)
    #expect(clientId == "LongC", "Client ID should be truncated to 5 chars")
    #expect(packet.count == 13, "Packet should be 2 + 6 + 5 = 13 bytes")
  }

  @Test
  func `appStart default client ID`() {
    // Default should be "MCore"
    let packet = PacketBuilder.appStart()

    let clientIdBytes = Data(packet[8...])
    let clientId = String(data: clientIdBytes, encoding: .utf8)
    #expect(clientId == "MCore", "Default client ID should be 'MCore'")
  }

  // MARK: - Bug C: StatusResponse Offset

  @Test
  func `statusResponse skips reserved byte`() {
    // Build a StatusResponse payload as firmware would send it (after response code stripped)
    // Format: reserved(1) + pubkey(6) + fields(52) = 59 bytes total
    var payload = Data()
    payload.append(0x00) // Reserved byte
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]) // Pubkey prefix (6)
    payload.append(contentsOf: [0xE8, 0x03]) // Battery: 1000mV (little-endian)
    payload.append(contentsOf: [0x05, 0x00]) // txQueue: 5
    payload.append(contentsOf: [0x92, 0xFF]) // noiseFloor: -110 (signed)
    payload.append(contentsOf: [0xAB, 0xFF]) // lastRSSI: -85 (signed)
    // Add remaining fields: 44 bytes
    payload.append(Data(repeating: 0, count: 44))

    #expect(payload.count == 59, "Payload should be 59 bytes total")

    let event = Parsers.StatusResponse.parse(payload)

    guard case let .statusResponse(status) = event else {
      Issue.record("Expected statusResponse event, got \(event)")
      return
    }

    // Verify pubkey starts at byte 1, not byte 0
    #expect(status.publicKeyPrefix.hexString == "aabbccddeeff",
            "Pubkey should be read from bytes 1-6, not 0-5")

    // Verify battery is read from correct offset
    #expect(status.battery == 1000,
            "Battery should be 1000mV, not corrupted by offset error")

    // Verify other fields
    #expect(status.txQueueLength == 5, "txQueue should be 5")
    #expect(status.noiseFloor == -110, "noiseFloor should be -110")
    #expect(status.lastRSSI == -85, "lastRSSI should be -85")
  }

  @Test
  func `statusResponse rejects short payload`() {
    // Payload too short should return parseFailure
    let shortPayload = Data(repeating: 0, count: 50)

    let event = Parsers.StatusResponse.parse(shortPayload)

    guard case .parseFailure = event else {
      Issue.record("Expected parseFailure for short payload, got \(event)")
      return
    }
  }

  @Test
  func `statusResponse handles max values`() {
    // Test with maximum realistic values (59 bytes total)
    var payload = Data()
    payload.append(0x00) // Reserved byte
    payload.append(contentsOf: [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]) // Pubkey prefix (6)
    payload.append(contentsOf: [0xDC, 0x05]) // Battery: 1500mV
    payload.append(contentsOf: [0x00, 0x00]) // txQueue: 0
    payload.append(contentsOf: [0x88, 0xFF]) // noiseFloor: -120
    payload.append(contentsOf: [0xD6, 0xFF]) // lastRSSI: -42
    payload.append(Data(repeating: 0, count: 44)) // Remaining 44 bytes

    let event = Parsers.StatusResponse.parse(payload)

    guard case let .statusResponse(status) = event else {
      Issue.record("Expected statusResponse event")
      return
    }

    #expect(status.battery == 1500)
    #expect(status.noiseFloor == -120)
    #expect(status.lastRSSI == -42)
  }

  // MARK: - Binary Response Status Parsing (Format 2)

  @Test
  func `statusResponse parseFromBinaryResponse valid payload`() {
    // Binary response format: fields start at offset 0 (no reserved byte, no pubkey)
    var payload = Data()
    payload.append(contentsOf: [0xE8, 0x03]) // Battery: 1000mV (little-endian)
    payload.append(contentsOf: [0x05, 0x00]) // txQueue: 5
    payload.append(contentsOf: [0x92, 0xFF]) // noiseFloor: -110 (signed)
    payload.append(contentsOf: [0xAB, 0xFF]) // lastRSSI: -85 (signed)
    payload.append(contentsOf: [0x64, 0x00, 0x00, 0x00]) // packetsRecv: 100
    payload.append(contentsOf: [0xC8, 0x00, 0x00, 0x00]) // packetsSent: 200
    payload.append(contentsOf: [0x10, 0x27, 0x00, 0x00]) // airtime: 10000
    payload.append(contentsOf: [0x58, 0x02, 0x00, 0x00]) // uptime: 600
    payload.append(contentsOf: [0x0A, 0x00, 0x00, 0x00]) // sentFlood: 10
    payload.append(contentsOf: [0x14, 0x00, 0x00, 0x00]) // sentDirect: 20
    payload.append(contentsOf: [0x1E, 0x00, 0x00, 0x00]) // recvFlood: 30
    payload.append(contentsOf: [0x28, 0x00, 0x00, 0x00]) // recvDirect: 40
    payload.append(contentsOf: [0x03, 0x00]) // fullEvents: 3
    payload.append(contentsOf: [0x28, 0x00]) // lastSNR: 40/4 = 10.0
    payload.append(contentsOf: [0x02, 0x00]) // directDups: 2
    payload.append(contentsOf: [0x01, 0x00]) // floodDups: 1
    payload.append(contentsOf: [0x20, 0x4E, 0x00, 0x00]) // rxAirtime: 20000

    let pubkeyPrefix = Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])

    let status = Parsers.StatusResponse.parseFromBinaryResponse(payload, publicKeyPrefix: pubkeyPrefix)

    guard let status else {
      Issue.record("Should successfully parse valid binary response")
      return
    }

    #expect(status.publicKeyPrefix.hexString == "aabbccddeeff")
    #expect(status.battery == 1000)
    #expect(status.txQueueLength == 5)
    #expect(status.noiseFloor == -110)
    #expect(status.lastRSSI == -85)
    #expect(status.packetsReceived == 100)
    #expect(status.packetsSent == 200)
    #expect(status.airtime == 10000)
    #expect(status.uptime == 600)
    #expect(status.sentFlood == 10)
    #expect(status.sentDirect == 20)
    #expect(status.receivedFlood == 30)
    #expect(status.receivedDirect == 40)
    #expect(status.fullEvents == 3)
    #expect(abs(status.lastSNR - 10.0) <= 0.001)
    #expect(status.directDuplicates == 2)
    #expect(status.floodDuplicates == 1)
    #expect(status.rxAirtime == 20000)
  }

  @Test
  func `statusResponse parseFromBinaryResponse rejects short payload`() {
    let shortPayload = Data(repeating: 0, count: 47) // Less than 48 bytes
    let pubkeyPrefix = Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])

    let status = Parsers.StatusResponse.parseFromBinaryResponse(shortPayload, publicKeyPrefix: pubkeyPrefix)

    #expect(status == nil, "Should return nil for payload shorter than 48 bytes")
  }

  @Test
  func `statusResponse parseFromBinaryResponse handles minimal payload`() {
    // Exactly 48 bytes (no rxAirtime, no receiveErrors)
    var payload = Data(repeating: 0, count: 48)
    payload[0] = 0xE8
    payload[1] = 0x03 // Battery: 1000mV

    let pubkeyPrefix = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])

    let status = Parsers.StatusResponse.parseFromBinaryResponse(payload, publicKeyPrefix: pubkeyPrefix)

    guard let status else {
      Issue.record("Should parse minimal payload")
      return
    }

    #expect(status.battery == 1000)
    #expect(status.rxAirtime == 0, "rxAirtime should default to 0 when not present")
    #expect(status.receiveErrors == 0, "receiveErrors should default to 0 when not present")
  }

  @Test
  func `battery response rejects partial extended payload`() {
    var packet = Data([ResponseCode.battery.rawValue])
    packet.append(contentsOf: [0xE8, 0x03]) // 1000 mV
    packet.append(contentsOf: [0x01, 0x02, 0x03]) // partial extended fields only

    let event = PacketParser.parse(packet)

    guard case let .parseFailure(_, reason) = event else {
      Issue.record("Expected .parseFailure event, got \(event)")
      return
    }

    #expect(reason.contains("partial extended payload"))
  }

  @Test
  func `statusResponse parseFromBinaryResponse 52 bytes parses rxAirtime`() {
    // 52 bytes: has rxAirtime but no receiveErrors
    var payload = Data(repeating: 0, count: 52)
    payload[0] = 0xE8
    payload[1] = 0x03 // Battery: 1000mV
    // rxAirtime at offset 48: 5000 = 0x1388
    payload[48] = 0x88
    payload[49] = 0x13
    payload[50] = 0x00
    payload[51] = 0x00

    let pubkeyPrefix = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
    let status = Parsers.StatusResponse.parseFromBinaryResponse(payload, publicKeyPrefix: pubkeyPrefix)

    guard let status else {
      Issue.record("Should parse 52-byte payload")
      return
    }

    #expect(status.battery == 1000)
    #expect(status.rxAirtime == 5000)
    #expect(status.receiveErrors == 0, "receiveErrors should default to 0 for 52-byte payload")
  }

  @Test
  func `statusResponse parseFromBinaryResponse room server 52 bytes parses room counters`() {
    var payload = Data(repeating: 0, count: 52)
    payload[0] = 0x3C
    payload[1] = 0x0F // Battery: 3900mV
    payload[2] = 0x02
    payload[3] = 0x00 // txQueueLength: 2
    payload[4] = 0x8D
    payload[5] = 0xFF // noiseFloor: -115
    payload[6] = 0xA9
    payload[7] = 0xFF // lastRSSI: -87
    payload[8] = 0x78
    payload[12] = 0x2D
    payload[16] = 0x10
    payload[17] = 0x0E // airtime: 3600
    payload[20] = 0x20
    payload[21] = 0x1C // uptime: 7200
    payload[24] = 0x0C
    payload[28] = 0x08
    payload[32] = 0x0E
    payload[36] = 0x0A
    payload[40] = 0x03
    payload[42] = 0x18
    payload[44] = 0x01
    payload[46] = 0x02
    payload[48] = 0x11
    payload[49] = 0x00 // roomServerPostedCount: 17
    payload[50] = 0x09
    payload[51] = 0x00 // roomServerPostPushCount: 9

    let pubkeyPrefix = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
    let status = Parsers.StatusResponse.parseFromBinaryResponse(
      payload,
      publicKeyPrefix: pubkeyPrefix,
      layout: .roomServer
    )

    guard let status else {
      Issue.record("Should parse 52-byte room server payload")
      return
    }

    #expect(status.layout == .roomServer)
    #expect(status.roomServerPostedCount == 17)
    #expect(status.roomServerPostPushCount == 9)
    #expect(status.rxAirtime == 0)
    #expect(status.receiveErrors == 0)
  }

  @Test
  func `statusResponse parseFromBinaryResponse 56 bytes parses receiveErrors`() {
    // 56 bytes: has rxAirtime and receiveErrors (v1.12+)
    var payload = Data(repeating: 0, count: 56)
    payload[0] = 0xE8
    payload[1] = 0x03 // Battery: 1000mV
    // rxAirtime at offset 48: 5000 = 0x1388
    payload[48] = 0x88
    payload[49] = 0x13
    payload[50] = 0x00
    payload[51] = 0x00
    // receiveErrors at offset 52: 42 = 0x2A
    payload[52] = 0x2A
    payload[53] = 0x00
    payload[54] = 0x00
    payload[55] = 0x00

    let pubkeyPrefix = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
    let status = Parsers.StatusResponse.parseFromBinaryResponse(payload, publicKeyPrefix: pubkeyPrefix)

    guard let status else {
      Issue.record("Should parse 56-byte payload")
      return
    }

    #expect(status.battery == 1000)
    #expect(status.rxAirtime == 5000)
    #expect(status.receiveErrors == 42)
  }

  @Test
  func `statusResponse parseFromBinaryResponse rejects incomplete payload`() {
    // Reject sizes between valid field boundaries
    for size in [49, 50, 51, 53, 54, 55] {
      let payload = Data(repeating: 0, count: size)
      let pubkeyPrefix = Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])
      let status = Parsers.StatusResponse.parseFromBinaryResponse(payload, publicKeyPrefix: pubkeyPrefix)
      #expect(status == nil, "Should reject \(size)-byte payload")
    }
  }

  @Test
  func `statusResponse parseFromBinaryResponse handles extra data`() {
    // 60 bytes: 56 + 4 extra bytes from future firmware
    var payload = Data(repeating: 0, count: 60)
    payload[0] = 0xE8
    payload[1] = 0x03 // Battery: 1000mV
    // receiveErrors at offset 52: 7
    payload[52] = 0x07
    let pubkeyPrefix = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
    let status = Parsers.StatusResponse.parseFromBinaryResponse(payload, publicKeyPrefix: pubkeyPrefix)
    #expect(status != nil, "Should parse payload with extra data")
    #expect(status?.battery == 1000)
    #expect(status?.receiveErrors == 7)
  }

  // MARK: - Push Status Parse with receiveErrors

  @Test
  func `statusResponse parse legacy size defaults receiveErrors`() {
    // 59 bytes: legacy v1.11 format (no receiveErrors)
    var payload = Data()
    payload.append(0x00) // Reserved byte
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]) // Pubkey prefix
    payload.append(contentsOf: [0xE8, 0x03]) // Battery: 1000mV
    payload.append(contentsOf: [0x05, 0x00]) // txQueue: 5
    payload.append(contentsOf: [0x92, 0xFF]) // noiseFloor: -110
    payload.append(contentsOf: [0xAB, 0xFF]) // lastRSSI: -85
    payload.append(Data(repeating: 0, count: 44)) // Remaining fields

    #expect(payload.count == 59)

    let event = Parsers.StatusResponse.parse(payload)

    guard case let .statusResponse(status) = event else {
      Issue.record("Expected statusResponse event, got \(event)")
      return
    }

    #expect(status.battery == 1000)
    #expect(status.receiveErrors == 0, "receiveErrors should default to 0 for legacy payload")
  }

  @Test
  func `statusResponse parse extended size parses receiveErrors`() {
    // 63 bytes: v1.12 format with receiveErrors
    var payload = Data()
    payload.append(0x00) // Reserved byte
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]) // Pubkey prefix
    payload.append(contentsOf: [0xE8, 0x03]) // Battery: 1000mV
    payload.append(contentsOf: [0x05, 0x00]) // txQueue: 5
    payload.append(contentsOf: [0x92, 0xFF]) // noiseFloor: -110
    payload.append(contentsOf: [0xAB, 0xFF]) // lastRSSI: -85
    payload.append(Data(repeating: 0, count: 44))
    // receiveErrors: 99 = 0x63
    payload.append(contentsOf: [0x63, 0x00, 0x00, 0x00])

    #expect(payload.count == 63)

    let event = Parsers.StatusResponse.parse(payload)

    guard case let .statusResponse(status) = event else {
      Issue.record("Expected statusResponse event, got \(event)")
      return
    }

    #expect(status.battery == 1000)
    #expect(status.receiveErrors == 99)
  }

  // MARK: - Push Status Parse with Room Server Layout

  @Test
  func `statusResponse parse room server layout decodes post counters`() {
    // 59 bytes: room server push payload (1 reserved + 6 pubkey + 52 ServerStats)
    var payload = Data()
    payload.append(0x00) // Reserved byte
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]) // Pubkey prefix
    payload.append(contentsOf: [0xE8, 0x03]) // Battery: 1000mV
    payload.append(contentsOf: [0x05, 0x00]) // txQueue: 5
    payload.append(contentsOf: [0x92, 0xFF]) // noiseFloor: -110
    payload.append(contentsOf: [0xAB, 0xFF]) // lastRSSI: -85
    payload.append(Data(repeating: 0, count: 40)) // Remaining common fields (zero)
    // Room-server tail: n_posted = 17 (0x0011), n_post_push = 9 (0x0009)
    payload.append(contentsOf: [0x11, 0x00]) // roomServerPostedCount: 17
    payload.append(contentsOf: [0x09, 0x00]) // roomServerPostPushCount: 9

    #expect(payload.count == 59)

    let event = Parsers.StatusResponse.parse(payload, layout: .roomServer)

    guard case let .statusResponse(status) = event else {
      Issue.record("Expected statusResponse event, got \(event)")
      return
    }

    #expect(status.layout == .roomServer)
    #expect(status.roomServerPostedCount == 17)
    #expect(status.roomServerPostPushCount == 9)
    #expect(status.rxAirtime == 0)
    #expect(status.receiveErrors == 0)
    #expect(status.battery == 1000)
  }

  @Test
  func `statusResponse parse defaults to repeater layout`() {
    // Same 59-byte payload as above, parsed without explicit layout
    var payload = Data()
    payload.append(0x00) // Reserved byte
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]) // Pubkey prefix
    payload.append(contentsOf: [0xE8, 0x03]) // Battery: 1000mV
    payload.append(contentsOf: [0x05, 0x00]) // txQueue: 5
    payload.append(contentsOf: [0x92, 0xFF]) // noiseFloor: -110
    payload.append(contentsOf: [0xAB, 0xFF]) // lastRSSI: -85
    payload.append(Data(repeating: 0, count: 40)) // Remaining common fields (zero)
    // Tail bytes: 0x11 0x00 0x09 0x00 — interpreted as rxAirtime UInt32LE = 0x00090011 = 589841
    payload.append(contentsOf: [0x11, 0x00, 0x09, 0x00])

    #expect(payload.count == 59)

    let event = Parsers.StatusResponse.parse(payload)

    guard case let .statusResponse(status) = event else {
      Issue.record("Expected statusResponse event, got \(event)")
      return
    }

    #expect(status.layout == .repeater)
    #expect(status.rxAirtime == 0x0009_0011)
    #expect(status.roomServerPostedCount == nil)
    #expect(status.roomServerPostPushCount == nil)
  }

  // MARK: - Telemetry Request Payload

  @Test
  func `binaryRequest telemetry includes permission mask payload`() {
    let publicKey = Data(repeating: 0xAB, count: 32)
    let payload = Data([0x00, 0x00, 0x00, 0x00])
    let packet = PacketBuilder.binaryRequest(to: publicKey, type: .telemetry, payload: payload)

    // Structure: [cmd:1][pubkey:32][type:1][payload:4] = 38 bytes
    #expect(packet.count == 38, "Telemetry request should be 38 bytes with 4-byte payload")
    #expect(packet[33] == BinaryRequestType.telemetry.rawValue, "Byte 33 should be telemetry type")
    // Payload bytes: permission mask + 3 reserved
    #expect(packet[34] == 0x00, "Permission mask byte should be 0x00 (inverts to 0xFF)")
    #expect(packet[35] == 0x00, "Reserved byte 1")
    #expect(packet[36] == 0x00, "Reserved byte 2")
    #expect(packet[37] == 0x00, "Reserved byte 3")
  }

  // MARK: - Bug B & D: Binary Response Routing & Neighbours Parser

  @Test
  func `neighboursParser parses valid response`() {
    var payload = Data()
    payload.append(contentsOf: [0x03, 0x00]) // total_count: 3 (little-endian)
    payload.append(contentsOf: [0x02, 0x00]) // results_count: 2

    // Entry 1: pubkey_prefix(4) + secs_ago(4) + snr(1)
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44]) // pubkey prefix
    payload.append(contentsOf: [0x3C, 0x00, 0x00, 0x00]) // secs_ago: 60
    payload.append(0x28) // snr: 40/4 = 10.0

    // Entry 2
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD]) // pubkey prefix
    payload.append(contentsOf: [0x78, 0x00, 0x00, 0x00]) // secs_ago: 120
    payload.append(0xF0) // snr: -16/4 = -4.0 (signed)

    let response = NeighboursParser.parse(
      payload,
      publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]),
      tag: Data([0xDE, 0xAD, 0xBE, 0xEF]),
      prefixLength: 4
    )

    #expect(response.totalCount == 3, "Total count should be 3")
    #expect(response.neighbours.count == 2, "Should have 2 neighbour entries")

    // Verify first neighbour
    #expect(response.neighbours[0].publicKeyPrefix.hexString == "11223344")
    #expect(response.neighbours[0].secondsAgo == 60)
    #expect(abs(response.neighbours[0].snr - 10.0) <= 0.001)

    // Verify second neighbour
    #expect(response.neighbours[1].publicKeyPrefix.hexString == "aabbccdd")
    #expect(response.neighbours[1].secondsAgo == 120)
    #expect(abs(response.neighbours[1].snr - -4.0) <= 0.001)
  }

  @Test
  func `neighboursParser handles empty response`() {
    var payload = Data()
    payload.append(contentsOf: [0x00, 0x00]) // total_count: 0
    payload.append(contentsOf: [0x00, 0x00]) // results_count: 0

    let response = NeighboursParser.parse(
      payload,
      publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]),
      tag: Data([0xDE, 0xAD, 0xBE, 0xEF]),
      prefixLength: 4
    )

    #expect(response.totalCount == 0)
    #expect(response.neighbours.count == 0)
  }

  @Test
  func `neighboursParser handles short payload`() {
    let shortPayload = Data([0x01, 0x00]) // Only 2 bytes

    let response = NeighboursParser.parse(
      shortPayload,
      publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]),
      tag: Data([0xDE, 0xAD, 0xBE, 0xEF]),
      prefixLength: 4
    )

    #expect(response.totalCount == 0)
    #expect(response.neighbours.count == 0)
  }

  @Test
  func `neighboursParser handles 6-byte prefix length`() {
    var payload = Data()
    payload.append(contentsOf: [0x01, 0x00]) // total_count: 1
    payload.append(contentsOf: [0x01, 0x00]) // results_count: 1

    // Entry with 6-byte prefix
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66]) // pubkey prefix (6)
    payload.append(contentsOf: [0x1E, 0x00, 0x00, 0x00]) // secs_ago: 30
    payload.append(0x14) // snr: 20/4 = 5.0

    let response = NeighboursParser.parse(
      payload,
      publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]),
      tag: Data([0xDE, 0xAD, 0xBE, 0xEF]),
      prefixLength: 6
    )

    #expect(response.neighbours.count == 1)
    #expect(response.neighbours[0].publicKeyPrefix.hexString == "112233445566")
    #expect(response.neighbours[0].secondsAgo == 30)
    #expect(abs(response.neighbours[0].snr - 5.0) <= 0.001)
  }

  // MARK: - Neighbours Pagination

  /// Builds one page of a paginated neighbour table. `secondsAgo` encodes the global
  /// index so a test can assert each page was fetched at the correct offset and
  /// concatenated in order.
  private func neighboursPage(total: Int, from start: Int, pageSize: Int) -> NeighboursResponse {
    let end = min(start + pageSize, total)
    let entries = (start..<end).map { index in
      Neighbour(publicKeyPrefix: Data([UInt8(index & 0xFF)]), secondsAgo: index, snr: Double(index))
    }
    return NeighboursResponse(
      publicKeyPrefix: Data([0x01]),
      tag: Data([0x02]),
      totalCount: total,
      neighbours: entries
    )
  }

  @Test
  func `collectingAllPages aggregates every page until the total is reached`() async {
    // A 34-neighbour table delivered in frames of 11, mirroring a repeater whose
    // single-frame response caps well below its total.
    let result = await NeighboursResponse.collectingAllPages { offset in
      neighboursPage(total: 34, from: Int(offset), pageSize: 11)
    }

    #expect(result.totalCount == 34)
    #expect(result.neighbours.count == 34)
    // Strictly increasing indices prove pages were fetched at 0, 11, 22, 33 and joined in order.
    #expect(result.neighbours.map(\.secondsAgo) == Array(0..<34))
  }

  @Test
  func `collectingAllPages stops when a stalled page returns no rows`() async {
    // Node advertises 34 but never delivers past the first frame. Without the
    // empty-page guard this would loop forever and hang the suite.
    let result = await NeighboursResponse.collectingAllPages { offset in
      if offset == 0 {
        return neighboursPage(total: 34, from: 0, pageSize: 11)
      }
      return NeighboursResponse(
        publicKeyPrefix: Data([0x01]),
        tag: Data([0x02]),
        totalCount: 34,
        neighbours: []
      )
    }

    #expect(result.neighbours.count == 11)
    #expect(result.totalCount == 34)
  }

  @Test
  func `collectingAllPages returns a single complete page without over-fetching`() async {
    let result = await NeighboursResponse.collectingAllPages { offset in
      neighboursPage(total: 5, from: Int(offset), pageSize: 20)
    }

    #expect(result.neighbours.count == 5)
    #expect(result.totalCount == 5)
  }

  @Test
  func `ACL parser parses valid response`() {
    var payload = Data()

    // Entry 1
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66]) // pubkey prefix (6)
    payload.append(0x01) // permissions

    // Entry 2
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]) // pubkey prefix (6)
    payload.append(0x03) // permissions

    let entries = ACLParser.parse(payload)

    #expect(entries.count == 2, "Should have 2 ACL entries")
    #expect(entries[0].keyPrefix.hexString == "112233445566")
    #expect(entries[0].permissions == 0x01)
    #expect(entries[1].keyPrefix.hexString == "aabbccddeeff")
    #expect(entries[1].permissions == 0x03)
  }

  @Test
  func `ACL parser skips null entries`() {
    var payload = Data()

    // Entry 1 (valid)
    payload.append(contentsOf: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
    payload.append(0x01)

    // Entry 2 (null - should be skipped)
    payload.append(contentsOf: [0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
    payload.append(0x00)

    // Entry 3 (valid)
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])
    payload.append(0x02)

    let entries = ACLParser.parse(payload)

    #expect(entries.count == 2, "Should have 2 entries (null entry skipped)")
    #expect(entries[0].keyPrefix.hexString == "112233445566")
    #expect(entries[1].keyPrefix.hexString == "aabbccddeeff")
  }

  @Test
  func `MMA parser parses temperature entry`() {
    var payload = Data()

    // Temperature entry: channel 1, type 0x67
    payload.append(0x01) // channel
    payload.append(0x67) // type: temperature
    // Values are big-endian, scaled by 10
    payload.append(contentsOf: [0x00, 0xC8]) // min: 200 = 20.0C
    payload.append(contentsOf: [0x01, 0x2C]) // max: 300 = 30.0C
    payload.append(contentsOf: [0x00, 0xFA]) // avg: 250 = 25.0C

    let entries = MMAParser.parse(payload)

    #expect(entries.count == 1, "Should have 1 MMA entry")
    #expect(entries[0].channel == 1)
    #expect(entries[0].type == "Temperature")
    #expect(abs(entries[0].min - 20.0) <= 0.001)
    #expect(abs(entries[0].max - 30.0) <= 0.001)
    #expect(abs(entries[0].avg - 25.0) <= 0.001)
  }

  @Test
  func `MMA parser parses humidity entry`() {
    // Humidity entry: type 0x68, values scaled by 0.5
    var payload = Data()

    payload.append(0x02) // channel
    payload.append(0x68) // type: humidity
    // Values are 1 byte each, scaled by 0.5
    payload.append(0x64) // min: 100 * 0.5 = 50%
    payload.append(0x96) // max: 150 * 0.5 = 75%
    payload.append(0x82) // avg: 130 * 0.5 = 65%

    let entries = MMAParser.parse(payload)

    #expect(entries.count == 1)
    #expect(entries[0].type == "Humidity")
    #expect(abs(entries[0].min - 50.0) <= 0.001)
    #expect(abs(entries[0].max - 75.0) <= 0.001)
    #expect(abs(entries[0].avg - 65.0) <= 0.001)
  }

  // MARK: - Binary Response Telemetry Parsing (Format 2)

  @Test
  func `telemetryResponse parseFromBinaryResponse valid payload`() {
    var payload = Data()
    // Temperature reading: channel 1, type 0x67
    payload.append(0x01) // channel
    payload.append(0x67) // type: temperature
    payload.append(contentsOf: [0x00, 0xFA]) // 250 = 25.0C (big-endian, /10)

    let pubkeyPrefix = Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])

    let response = Parsers.TelemetryResponse.parseFromBinaryResponse(payload, publicKeyPrefix: pubkeyPrefix)

    #expect(response.publicKeyPrefix.hexString == "aabbccddeeff")
    #expect(response.rawData == payload)
    #expect(response.dataPoints.count == 1)
    #expect(response.dataPoints.first?.channel == 1)
  }

  @Test
  func `telemetryResponse parseFromBinaryResponse empty payload`() {
    let emptyPayload = Data()
    let pubkeyPrefix = Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])

    let response = Parsers.TelemetryResponse.parseFromBinaryResponse(emptyPayload, publicKeyPrefix: pubkeyPrefix)

    // Empty payload is valid (just no data points)
    #expect(response.dataPoints.count == 0)
  }

  @Test
  func `requestStatus throws device error when error response received`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "Test")
    )

    let startTask = Task {
      try await session.start()
    }

    try await waitUntil("transport should have sent appStart") {
      await transport.sentData.count >= 1
    }

    var selfInfoPayload = Data()
    selfInfoPayload.append(0)
    selfInfoPayload.append(0)
    selfInfoPayload.append(0)
    selfInfoPayload.append(Data(repeating: 0x01, count: 32))
    selfInfoPayload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    selfInfoPayload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    selfInfoPayload.append(0)
    selfInfoPayload.append(0)
    selfInfoPayload.append(0)
    selfInfoPayload.append(0)
    selfInfoPayload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    selfInfoPayload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    selfInfoPayload.append(7)
    selfInfoPayload.append(5)
    selfInfoPayload.append(contentsOf: "Test".utf8)

    var selfInfoPacket = Data([ResponseCode.selfInfo.rawValue])
    selfInfoPacket.append(selfInfoPayload)
    await transport.simulateReceive(selfInfoPacket)

    try await startTask.value

    let publicKey = Data(repeating: 0x31, count: 32)
    let statusTask = Task {
      try await session.requestStatus(from: publicKey)
    }

    try await waitUntil("transport should have sent status request") {
      await transport.sentData.count >= 2
    }

    await transport.simulateError(code: 10)

    do {
      _ = try await statusTask.value
      Issue.record("Expected requestStatus(from:) to throw")
    } catch let error as MeshCoreError {
      guard case let .deviceError(code) = error else {
        Issue.record("Expected MeshCoreError.deviceError, got \(error)")
        return
      }
      #expect(code == 10)
    } catch {
      Issue.record("Unexpected error: \(error)")
    }

    await session.stop()
  }

  @Test
  func `requestNeighbours rejects a short public key before sending`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "Test")
    )

    let shortKey = Data(repeating: 0x31, count: 6)
    do {
      _ = try await session.requestNeighbours(from: shortKey)
      Issue.record("Expected requestNeighbours(from:) to throw for a short key")
    } catch let error as MeshCoreError {
      guard case .invalidInput = error else {
        Issue.record("Expected MeshCoreError.invalidInput, got \(error)")
        return
      }
    } catch {
      Issue.record("Unexpected error: \(error)")
    }

    // The guard must run before any frame reaches the transport.
    #expect(await transport.sentData.isEmpty)

    await session.stop()
  }
}
