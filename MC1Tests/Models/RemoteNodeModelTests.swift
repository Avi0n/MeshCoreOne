import Foundation
@testable import MC1Services
import Testing

@Suite("Remote Node Model Tests")
struct RemoteNodeModelTests {
  // MARK: - RemoteNodeSession Tests

  @Test
  func `RemoteNodeSession correctly stores role`() async throws {
    let container = try DataStore.createContainer(inMemory: true)
    let dataStore = DataStore(modelContainer: container)

    let deviceID = UUID()
    let publicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })

    // Create room session
    let roomSession = RemoteNodeSessionDTO(
      radioID: deviceID,
      publicKey: publicKey,
      name: "TestRoom",
      role: .roomServer
    )

    try await dataStore.saveRemoteNodeSessionDTO(roomSession)
    let fetched = try await dataStore.fetchRemoteNodeSession(id: roomSession.id)

    #expect(fetched?.role == .roomServer)
    #expect(fetched?.isRoom == true)
    #expect(fetched?.isRepeater == false)
  }

  @Test
  func `RemoteNodeSession correctly stores repeater role`() async throws {
    let container = try DataStore.createContainer(inMemory: true)
    let dataStore = DataStore(modelContainer: container)

    let deviceID = UUID()
    let publicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })

    let repeaterSession = RemoteNodeSessionDTO(
      radioID: deviceID,
      publicKey: publicKey,
      name: "TestRepeater",
      role: .repeater
    )

    try await dataStore.saveRemoteNodeSessionDTO(repeaterSession)
    let fetched = try await dataStore.fetchRemoteNodeSession(id: repeaterSession.id)

    #expect(fetched?.role == .repeater)
    #expect(fetched?.isRoom == false)
    #expect(fetched?.isRepeater == true)
  }

  // MARK: - RemoteNodeSessionDTO Tests

  @Test
  func `RemoteNodeSessionDTO computed properties work`() {
    let publicKey = Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF] + Array(repeating: UInt8(0), count: 26))

    let session = RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: publicKey,
      name: "Test",
      role: .roomServer,
      isConnected: true,
      permissionLevel: .readWrite
    )

    // Test public key prefix
    #expect(session.publicKeyPrefix.count == 6)
    #expect(session.publicKeyPrefix == Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]))

    // Test hex string
    #expect(session.publicKeyHex.hasPrefix("AABBCCDDEEFF"))

    // Test role helpers
    #expect(session.isRoom == true)
    #expect(session.isRepeater == false)

    // Test permission helpers
    #expect(session.canPost == true) // Room + readWrite
    #expect(session.isAdmin == false)
  }

  @Test
  func `RemoteNodeSessionDTO canPost requires room and readWrite`() {
    // Room + guest = can't post
    let guestRoom = RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0, count: 32),
      name: "Test",
      role: .roomServer,
      permissionLevel: .guest
    )
    #expect(guestRoom.canPost == false)

    // Repeater + admin = can't post (not a room)
    let adminRepeater = RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0, count: 32),
      name: "Test",
      role: .repeater,
      permissionLevel: .admin
    )
    #expect(adminRepeater.canPost == false)

    // Room + admin = can post
    let adminRoom = RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0, count: 32),
      name: "Test",
      role: .roomServer,
      permissionLevel: .admin
    )
    #expect(adminRoom.canPost == true)
  }

  // MARK: - RoomMessage Tests

  @Test
  func `RoomMessage.generateDeduplicationKey produces consistent keys`() {
    let timestamp: UInt32 = 1_702_500_000
    let authorPrefix = Data([0xAA, 0xBB, 0xCC, 0xDD])
    let text = "Hello world"

    let key1 = RoomMessage.generateDeduplicationKey(
      timestamp: timestamp,
      authorKeyPrefix: authorPrefix,
      text: text
    )

    let key2 = RoomMessage.generateDeduplicationKey(
      timestamp: timestamp,
      authorKeyPrefix: authorPrefix,
      text: text
    )

    #expect(key1 == key2)
  }

  @Test
  func `RoomMessage.generateDeduplicationKey differs for different content`() {
    let timestamp: UInt32 = 1_702_500_000
    let authorPrefix = Data([0xAA, 0xBB, 0xCC, 0xDD])

    let key1 = RoomMessage.generateDeduplicationKey(
      timestamp: timestamp,
      authorKeyPrefix: authorPrefix,
      text: "Hello"
    )

    let key2 = RoomMessage.generateDeduplicationKey(
      timestamp: timestamp,
      authorKeyPrefix: authorPrefix,
      text: "World"
    )

    #expect(key1 != key2)
  }

  @Test
  func `RoomMessage.generateDeduplicationKey differs for different timestamps`() {
    let authorPrefix = Data([0xAA, 0xBB, 0xCC, 0xDD])
    let text = "Same text"

    let key1 = RoomMessage.generateDeduplicationKey(
      timestamp: 1_702_500_000,
      authorKeyPrefix: authorPrefix,
      text: text
    )

    let key2 = RoomMessage.generateDeduplicationKey(
      timestamp: 1_702_500_001,
      authorKeyPrefix: authorPrefix,
      text: text
    )

    #expect(key1 != key2)
  }

  @Test
  func `RoomMessage.generateDeduplicationKey differs for different authors`() {
    let timestamp: UInt32 = 1_702_500_000
    let text = "Same text"

    let key1 = RoomMessage.generateDeduplicationKey(
      timestamp: timestamp,
      authorKeyPrefix: Data([0xAA, 0xBB, 0xCC, 0xDD]),
      text: text
    )

    let key2 = RoomMessage.generateDeduplicationKey(
      timestamp: timestamp,
      authorKeyPrefix: Data([0x11, 0x22, 0x33, 0x44]),
      text: text
    )

    #expect(key1 != key2)
  }

  @Test
  func `RoomMessage author display name fallback works`() {
    // With author name
    let messageWithName = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0xAA, 0xBB, 0xCC, 0xDD]),
      authorName: "Alice",
      text: "Hello",
      timestamp: 1_702_500_000
    )
    #expect(messageWithName.authorDisplayName == "Alice")

    // Without author name (should use hex)
    let messageWithoutName = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0xAA, 0xBB, 0xCC, 0xDD]),
      authorName: nil,
      text: "Hello",
      timestamp: 1_702_500_000
    )
    #expect(messageWithoutName.authorDisplayName == "AABBCCDD")
  }

  @Test
  func `RoomMessageDTO date conversion works`() {
    let timestamp: UInt32 = 1_702_500_000
    let message = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0xAA, 0xBB, 0xCC, 0xDD]),
      authorName: nil,
      text: "Test",
      timestamp: timestamp
    )

    let expectedDate = Date(timeIntervalSince1970: TimeInterval(timestamp))
    #expect(message.date == expectedDate)
  }
}
