import Foundation
@testable import MeshCore
import Testing

@Suite("RxLogParser")
struct RxLogParserTests {
  @Test
  func `Parse empty payload returns nil`() {
    let result = RxLogParser.parse(snr: 5.0, rssi: -80, payload: Data())
    #expect(result == nil)
  }

  @Test
  func `Parse FLOOD GROUP_TEXT packet`() {
    // Header: routeType=1 (FLOOD), payloadType=5 (GROUP_TEXT), version=0
    // Header byte: 0b00_0101_01 = 0x15
    // pathLen=0, packetPayload follows
    let payload = Data([0x15, 0x00, 0xAA, 0xBB, 0xCC])

    let result = RxLogParser.parse(snr: 8.0, rssi: -85, payload: payload)

    #expect(result != nil)
    #expect(result?.routeType == .flood)
    #expect(result?.payloadType == .groupText)
    #expect(result?.payloadVersion == 0)
    #expect(result?.transportCode == nil)
    #expect(result?.pathLength == 0)
    #expect(result?.pathNodes == [])
    #expect(result?.packetPayload == Data([0xAA, 0xBB, 0xCC]))
  }

  @Test
  func `Parse TC_FLOOD with transport code and path`() {
    // Header: routeType=0 (TC_FLOOD), payloadType=5 (GROUP_TEXT), version=1
    // Header byte: 0b01_0101_00 = 0x54
    let payload = Data([
      0x54, // header
      0x01, 0x02, 0x03, 0x04, // transport code
      0x02, // pathLen
      0x3A, 0x7F, // pathNodes
      0xDE, 0xAD, 0xBE, 0xEF // packetPayload
    ])

    let result = RxLogParser.parse(snr: nil, rssi: nil, payload: payload)

    #expect(result != nil)
    #expect(result?.routeType == .tcFlood)
    #expect(result?.payloadType == .groupText)
    #expect(result?.payloadVersion == 1)
    #expect(result?.transportCode == Data([0x01, 0x02, 0x03, 0x04]))
    #expect(result?.pathLength == 2)
    #expect(result?.pathNodes == [0x3A, 0x7F])
    #expect(result?.packetPayload == Data([0xDE, 0xAD, 0xBE, 0xEF]))
  }

  @Test
  func `Parse DIRECT packet (no transport code)`() {
    // Header: routeType=2 (DIRECT), payloadType=2 (TEXT_MSG), version=0
    // Header byte: 0b00_0010_10 = 0x0A
    let payload = Data([0x0A, 0x01, 0xFF, 0x48, 0x69])

    let result = RxLogParser.parse(snr: 6.5, rssi: -70, payload: payload)

    #expect(result != nil)
    #expect(result?.routeType == .direct)
    #expect(result?.payloadType == .textMessage)
    #expect(result?.transportCode == nil)
    #expect(result?.pathLength == 1)
    #expect(result?.pathNodes == [0xFF])
    #expect(result?.packetPayload == Data([0x48, 0x69]))
  }

  @Test
  func `Parse TC_DIRECT with transport code`() {
    // Header: routeType=3 (TC_DIRECT), payloadType=2 (TEXT_MSG), version=0
    // Header byte: 0b00_0010_11 = 0x0B
    let payload = Data([
      0x0B, // header
      0xAA, 0xBB, 0xCC, 0xDD, // transport code
      0x01, // pathLen
      0x42, // pathNodes
      0x48, 0x69 // packetPayload ("Hi")
    ])

    let result = RxLogParser.parse(snr: 7.0, rssi: -75, payload: payload)

    #expect(result != nil)
    #expect(result?.routeType == .tcDirect)
    #expect(result?.payloadType == .textMessage)
    #expect(result?.transportCode == Data([0xAA, 0xBB, 0xCC, 0xDD]))
    #expect(result?.pathLength == 1)
    #expect(result?.pathNodes == [0x42])
    #expect(result?.packetPayload == Data([0x48, 0x69]))
  }

  @Test
  func `Parse packet with unknown payload type`() {
    // Header: routeType=1 (FLOOD), payloadType=14 (undefined), version=0
    // Header byte: 0b00_1110_01 = 0x39
    let payload = Data([0x39, 0x00, 0x01, 0x02])

    let result = RxLogParser.parse(snr: nil, rssi: nil, payload: payload)

    #expect(result != nil)
    #expect(result?.payloadType == .unknown)
  }

  @Test
  func `Parse DIRECT TEXT_MSG extracts sender and recipient pubkey hashes`() {
    // Header: routeType=2 (DIRECT), payloadType=2 (TEXT_MSG), version=0
    // Header byte: 0b00_0010_10 = 0x0A
    // pathLen=0, then payload: [dest: 1B] [src: 1B] [MAC + encrypted]
    let destHash: UInt8 = 0x07 // recipient
    let srcHash: UInt8 = 0x0A // sender
    let encryptedContent = Data([0x48, 0x69, 0xAB, 0xCD]) // MAC + content
    let payload = Data([0x0A, 0x00, destHash, srcHash]) + encryptedContent

    let result = RxLogParser.parse(snr: 5.0, rssi: -80, payload: payload)

    #expect(result != nil)
    #expect(result?.senderPubkeyPrefix == Data([srcHash]))
    #expect(result?.recipientPubkeyPrefix == Data([destHash]))
  }

  @Test
  func `Parse TC_DIRECT TEXT_MSG extracts sender and recipient pubkey hashes`() {
    // Header: routeType=3 (TC_DIRECT), payloadType=2 (TEXT_MSG), version=0
    // Header byte: 0b00_0010_11 = 0x0B
    let destHash: UInt8 = 0x12 // recipient
    let srcHash: UInt8 = 0x34 // sender
    let payload = Data([
      0x0B, // header
      0xAA, 0xBB, 0xCC, 0xDD, // transport code
      0x00, // pathLen
      destHash, srcHash, // dest, src hashes
      0xDE, 0xAD, 0xBE, 0xEF // MAC + content
    ])

    let result = RxLogParser.parse(snr: 5.0, rssi: -80, payload: payload)

    #expect(result != nil)
    #expect(result?.senderPubkeyPrefix == Data([srcHash]))
    #expect(result?.recipientPubkeyPrefix == Data([destHash]))
  }

  @Test
  func `Parse DIRECT TEXT_MSG with hashSize > 1 path still extracts 1-byte payload hashes`() {
    // Header: routeType=2 (DIRECT), payloadType=2 (TEXT_MSG), version=0
    // Header byte: 0b00_0010_10 = 0x0A
    // pathLen: hashSize=2, hopCount=1 → mode=1 (0b01), encoded = 0b01_000001 = 0x41
    // path: 2 bytes (hashSize * hopCount)
    // payload hashes are always 1 byte per spec, regardless of path hash size
    let destHash: UInt8 = 0x07
    let srcHash: UInt8 = 0x0A
    let pathLenByte: UInt8 = 0x41 // hashSize=2, hopCount=1
    let payload = Data([
      0x0A, // header
      pathLenByte, // pathLen (hashSize=2, 1 hop)
      0xAB, 0xCD, // path (2 bytes: hashSize * hopCount)
      destHash, srcHash, // 1-byte dest, 1-byte src (per spec)
      0xDE, 0xAD, 0xBE, 0xEF // MAC + content
    ])

    let result = RxLogParser.parse(snr: 5.0, rssi: -80, payload: payload)

    #expect(result != nil)
    #expect(result?.senderPubkeyPrefix == Data([srcHash]))
    #expect(result?.recipientPubkeyPrefix == Data([destHash]))
  }

  @Test
  func `Parse FLOOD GROUP_TEXT has nil sender pubkey prefix`() {
    let payload = Data([0x15, 0x00, 0xAA, 0xBB, 0xCC])

    let result = RxLogParser.parse(snr: 8.0, rssi: -85, payload: payload)

    #expect(result != nil)
    #expect(result?.senderPubkeyPrefix == nil)
  }

  @Test
  func `MeshEvent.rxLogData holds ParsedRxLogData`() {
    let parsed = ParsedRxLogData(
      snr: 5.0, rssi: -80, rawPayload: Data([0x15, 0x00, 0xAA]),
      routeType: .flood, payloadType: .groupText, payloadVersion: 0,
      payloadTypeBits: 5,
      transportCode: nil, pathLength: 0, pathNodes: [],
      packetPayload: Data([0xAA])
    )

    let event = MeshEvent.rxLogData(parsed)

    if case let .rxLogData(data) = event {
      #expect(data.routeType == .flood)
      #expect(data.packetHash.count == 16)
    } else {
      Issue.record("Expected rxLogData event")
    }
  }

  @Test
  func `Parse rejects reserved (mode 3) path length encoding`() {
    // Header: routeType=2 (DIRECT), payloadType=2 (TEXT_MSG), version=0 -> 0x0A
    // pathLen: bits 7-6 = 0b11 (mode 3, reserved), hopCount=1 -> 0b11_000001 = 0xC1
    let reservedPathLen: UInt8 = 0xC1
    let payload = Data([0x0A, reservedPathLen, 0x07, 0x0A, 0xDE, 0xAD])

    let result = RxLogParser.parse(snr: 5.0, rssi: -80, payload: payload)

    #expect(result == nil)
  }

  @Test
  func `Parse FLOOD TEXT_MSG extracts sender and recipient pubkey hashes`() {
    // Header: routeType=1 (FLOOD), payloadType=2 (TEXT_MSG), version=0
    // Header byte: 0b00_0010_01 = 0x09
    // pathLen=0, then payload: [dest: 1B] [src: 1B] [MAC + encrypted]
    let destHash: UInt8 = 0x07 // recipient
    let srcHash: UInt8 = 0x0A // sender
    let encryptedContent = Data([0x48, 0x69, 0xAB, 0xCD]) // MAC + content
    let payload = Data([0x09, 0x00, destHash, srcHash]) + encryptedContent

    let result = RxLogParser.parse(snr: 5.0, rssi: -80, payload: payload)

    #expect(result != nil)
    #expect(result?.routeType == .flood)
    #expect(result?.payloadType == .textMessage)
    #expect(result?.senderPubkeyPrefix == Data([srcHash]))
    #expect(result?.recipientPubkeyPrefix == Data([destHash]))
  }
}
