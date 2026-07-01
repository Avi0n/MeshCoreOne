import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@MainActor
struct RxLogViewModelTests {
  // MARK: - RxLogEntryDTO Computed Properties

  @Test
  func `traceTargetHashes extracts 1-byte hashes when path_sz=0`() {
    // flags byte: path_sz=0 → hashSize = 1<<0 = 1
    var payload = Data(repeating: 0, count: 9) // [tag:4][auth:4][flags:1]
    payload[8] = 0x00 // path_sz = 0
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC])
    let dto = makeDTO(payloadType: .trace, packetPayload: payload)

    let hashes = dto.traceTargetHashes
    #expect(hashes?.count == 3)
    #expect(hashes?[0] == Data([0xAA]))
    #expect(hashes?[1] == Data([0xBB]))
    #expect(hashes?[2] == Data([0xCC]))
  }

  @Test
  func `traceTargetHashes extracts 2-byte hashes when path_sz=1`() {
    // flags byte: path_sz=1 → hashSize = 1<<1 = 2
    var payload = Data(repeating: 0, count: 9)
    payload[8] = 0x01
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC, 0xDD])
    let dto = makeDTO(payloadType: .trace, packetPayload: payload)

    let hashes = dto.traceTargetHashes
    #expect(hashes?.count == 2)
    #expect(hashes?[0] == Data([0xAA, 0xBB]))
    #expect(hashes?[1] == Data([0xCC, 0xDD]))
  }

  @Test
  func `traceTargetHashes returns nil for non-TRACE payload type`() {
    var payload = Data(repeating: 0, count: 12)
    payload[8] = 0x00
    let dto = makeDTO(payloadType: .textMessage, packetPayload: payload)
    #expect(dto.traceTargetHashes == nil)
  }

  @Test
  func `traceTargetHashes returns nil when payload is too short`() {
    let payload = Data(repeating: 0, count: 8) // needs > 9
    let dto = makeDTO(payloadType: .trace, packetPayload: payload)
    #expect(dto.traceTargetHashes == nil)
  }

  @Test
  func `traceTargetHashes returns nil when hash bytes don't divide evenly`() {
    // path_sz=1 → hashSize=2, but 3 remaining bytes don't divide evenly
    var payload = Data(repeating: 0, count: 9)
    payload[8] = 0x01
    payload.append(contentsOf: [0xAA, 0xBB, 0xCC])
    let dto = makeDTO(payloadType: .trace, packetPayload: payload)
    #expect(dto.traceTargetHashes == nil)
  }

  @Test
  func `senderPrefix extracts correct bytes for hashSize=1`() {
    // pathLength encodes hashSize=1: mode=0, hops=1 → 0x01
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF]) // [dest:1][src:1][rest]
    let dto = makeDTO(routeType: .direct, payloadType: .textMessage, pathLength: 0x01, packetPayload: payload)

    #expect(dto.senderPrefix == Data([0xAA]))
    #expect(dto.recipientPrefix == Data([0xDD]))
  }

  @Test
  func `senderPrefix uses fixed 1-byte payload hashes when path hashSize=2`() {
    // pathLength encodes hashSize=2: mode=1 → (1<<6)|hops = 0x41
    // DM payload hashes remain 1 byte even when routed path hashes are 2 bytes.
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF])
    let dto = makeDTO(routeType: .direct, payloadType: .textMessage, pathLength: 0x41, packetPayload: payload)

    #expect(dto.senderPrefix == Data([0xAA]))
    #expect(dto.recipientPrefix == Data([0xDD]))
  }

  @Test
  func `senderPrefix uses fixed 1-byte payload hashes when path hashSize=3`() {
    // pathLength encodes hashSize=3: mode=2 → (2<<6)|hops = 0x81
    // DM payload hashes remain 1 byte even when routed path hashes are 3 bytes.
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF])
    let dto = makeDTO(routeType: .direct, payloadType: .textMessage, pathLength: 0x81, packetPayload: payload)

    #expect(dto.senderPrefix == Data([0xAA]))
    #expect(dto.recipientPrefix == Data([0xDD]))
  }

  @Test
  func `senderPrefix returns nil for flood route`() {
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF])
    let dto = makeDTO(routeType: .flood, payloadType: .textMessage, pathLength: 0x01, packetPayload: payload)
    #expect(dto.senderPrefix == nil)
    #expect(dto.recipientPrefix == nil)
  }

  @Test
  func `senderPrefix returns nil for non-text payload`() {
    let payload = Data([0xDD, 0xAA, 0xFF, 0xFF])
    let dto = makeDTO(routeType: .direct, payloadType: .trace, pathLength: 0x01, packetPayload: payload)
    #expect(dto.senderPrefix == nil)
  }

  @Test
  func `pathHashSize decodes TRACE using standard pathLength encoding`() {
    // pathLength=0x41 → mode=1, hashSize=2
    let dto = makeDTO(payloadType: .trace, pathLength: 0x41)
    #expect(dto.pathHashSize == 2)
  }

  @Test
  func `hopCount decodes TRACE using standard pathLength encoding`() {
    // pathLength=0x43 → mode=1, hashSize=2, hopCount=3
    let dto = makeDTO(payloadType: .trace, pathLength: 0x43)
    #expect(dto.hopCount == 3)
  }

  @Test
  func `hopCount uses decodePathLen for non-TRACE`() {
    // pathLength=0x43 → mode=1, hashSize=2, hopCount=3
    let dto = makeDTO(payloadType: .textMessage, pathLength: 0x43)
    #expect(dto.hopCount == 3)
    #expect(dto.pathHashSize == 2)
  }

  // MARK: - buildNodeNameMap

  @Test
  func `Empty contacts produces empty map`() {
    let map = RxLogViewModel.buildNodeNameMap(from: [])
    #expect(map.isEmpty)
  }

  @Test
  func `Single contact generates entries for 1, 2, and 3-byte prefixes`() {
    let key = Data([0xAA, 0xBB, 0xCC, 0xDD])
    let contact = makeContact(name: "Alice", publicKey: key)
    let map = RxLogViewModel.buildNodeNameMap(from: [contact])

    #expect(map[Data([0xAA])] == "Alice")
    #expect(map[Data([0xAA, 0xBB])] == "Alice")
    #expect(map[Data([0xAA, 0xBB, 0xCC])] == "Alice")
  }

  @Test
  func `Two contacts with different first bytes resolve at all prefix lengths`() {
    let contacts = [
      makeContact(name: "Alice", publicKey: Data([0xAA, 0xBB, 0xCC, 0xDD])),
      makeContact(name: "Bob", publicKey: Data([0x11, 0x22, 0x33, 0x44]))
    ]
    let map = RxLogViewModel.buildNodeNameMap(from: contacts)

    #expect(map[Data([0xAA])] == "Alice")
    #expect(map[Data([0x11])] == "Bob")
    #expect(map[Data([0xAA, 0xBB])] == "Alice")
    #expect(map[Data([0x11, 0x22])] == "Bob")
    #expect(map[Data([0xAA, 0xBB, 0xCC])] == "Alice")
    #expect(map[Data([0x11, 0x22, 0x33])] == "Bob")
  }

  @Test
  func `Two contacts sharing first byte omit 1-byte entry but resolve at 2 and 3 bytes`() {
    let contacts = [
      makeContact(name: "Alice", publicKey: Data([0xAA, 0xBB, 0xCC, 0xDD])),
      makeContact(name: "Bob", publicKey: Data([0xAA, 0x22, 0x33, 0x44]))
    ]
    let map = RxLogViewModel.buildNodeNameMap(from: contacts)

    // 1-byte prefix is ambiguous — should not be in the map
    #expect(map[Data([0xAA])] == nil)

    // 2-byte prefixes are unique
    #expect(map[Data([0xAA, 0xBB])] == "Alice")
    #expect(map[Data([0xAA, 0x22])] == "Bob")

    // 3-byte prefixes are unique
    #expect(map[Data([0xAA, 0xBB, 0xCC])] == "Alice")
    #expect(map[Data([0xAA, 0x22, 0x33])] == "Bob")
  }

  @Test
  func `Two contacts sharing first two bytes omit 1 and 2-byte entries but resolve at 3 bytes`() {
    let contacts = [
      makeContact(name: "Alice", publicKey: Data([0xAA, 0xBB, 0xCC, 0xDD])),
      makeContact(name: "Bob", publicKey: Data([0xAA, 0xBB, 0x33, 0x44]))
    ]
    let map = RxLogViewModel.buildNodeNameMap(from: contacts)

    #expect(map[Data([0xAA])] == nil)
    #expect(map[Data([0xAA, 0xBB])] == nil)
    #expect(map[Data([0xAA, 0xBB, 0xCC])] == "Alice")
    #expect(map[Data([0xAA, 0xBB, 0x33])] == "Bob")
  }

  @Test
  func `Contact with short public key only generates entries for available lengths`() {
    let contacts = [
      makeContact(name: "Short", publicKey: Data([0xAA, 0xBB]))
    ]
    let map = RxLogViewModel.buildNodeNameMap(from: contacts)

    #expect(map[Data([0xAA])] == "Short")
    #expect(map[Data([0xAA, 0xBB])] == "Short")
    // 3-byte prefix not generated since key only has 2 bytes
    #expect(map[Data([0xAA, 0xBB])] == "Short")
    #expect(map.count == 2)
  }

  @Test
  func `Nickname takes precedence over name via displayName`() {
    let contact = makeContact(name: "Alice Jones", publicKey: Data([0xAA, 0xBB, 0xCC, 0xDD]), nickname: "AJ")
    let map = RxLogViewModel.buildNodeNameMap(from: [contact])

    #expect(map[Data([0xAA])] == "AJ")
  }

  // MARK: - Helpers

  private func makeDTO(
    routeType: RouteType = .flood,
    payloadType: PayloadType = .unknown,
    pathLength: UInt8 = 0,
    pathNodes: [UInt8] = [],
    packetPayload: Data = Data()
  ) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: nil,
      rssi: nil,
      rawPayload: Data(),
      routeType: routeType,
      payloadType: payloadType,
      payloadVersion: 0,
      payloadTypeBits: payloadType.rawValue & 0x0F,
      transportCode: nil,
      pathLength: pathLength,
      pathNodes: pathNodes,
      packetPayload: packetPayload
    )
    return RxLogEntryDTO(radioID: UUID(), from: parsed)
  }

  private func makeContact(
    name: String,
    publicKey: Data,
    nickname: String? = nil
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: publicKey,
      name: name,
      typeRawValue: 0,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nickname,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }
}
