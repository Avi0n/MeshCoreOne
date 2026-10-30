import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Room session snapshot writes", .timeLimit(.minutes(1)))
struct RoomSessionSnapshotWriteTests {
  @Test
  func `held createSession keeps newer unread activity notification telemetry and pin`() async throws {
    let (store, service) = try makeStack()
    let radioID = UUID()
    let publicKey = Data(repeating: 0x21, count: ProtocolLimits.publicKeySize)
    let seedDate = Date(timeIntervalSince1970: 1_600_000_100)
    let connectedAt = Date(timeIntervalSince1970: 1_600_000_000)
    let seed = RemoteNodeSessionDTO(
      radioID: radioID,
      publicKey: publicKey,
      name: "Old Room",
      role: .roomServer,
      latitude: 1,
      longitude: 2,
      isConnected: true,
      permissionLevel: .admin,
      lastConnectedDate: connectedAt,
      lastBatteryMillivolts: 3700,
      lastUptimeSeconds: 120,
      lastNoiseFloor: -90,
      unreadCount: 0,
      notificationLevel: .all,
      isPinned: true,
      lastRxAirtimeSeconds: 15,
      neighborCount: 4,
      lastSyncTimestamp: 10,
      lastMessageDate: seedDate
    )
    try await store.saveRemoteNodeSessionDTO(seed)

    let gate = SuspendGate()
    await service.setRoomReuseHoldForTesting {
      await gate.enter()
      await gate.waitForRelease()
    }
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: publicKey,
      name: "North Room",
      typeRawValue: ContactType.room.rawValue,
      latitude: 47.6,
      longitude: -122.3
    )
    let create = Task {
      try await service.createSession(radioID: radioID, contact: contact)
    }
    await gate.waitForEntry()
    try await store.incrementRoomUnreadCount(seed.id)
    try await store.setSessionNotificationLevel(seed.id, level: .muted)
    try await store.updateRoomActivity(seed.id, syncTimestamp: 20)
    await gate.release()

    let saved = try await create.value
    #expect(saved.id == seed.id)
    #expect(saved.name == "North Room")
    #expect(saved.latitude == 47.6)
    #expect(saved.longitude == -122.3)
    #expect(saved.isConnected == false)
    #expect(saved.unreadCount == 1)
    #expect(saved.notificationLevel == .muted)
    #expect(saved.lastSyncTimestamp == 20)
    let activity = try #require(saved.lastMessageDate)
    #expect(activity > seedDate)
    #expect(saved.isPinned == true)
    #expect(saved.permissionLevel == .admin)
    #expect(saved.lastConnectedDate == connectedAt)
    #expect(saved.lastBatteryMillivolts == 3700)
    #expect(saved.lastUptimeSeconds == 120)
    #expect(saved.lastNoiseFloor == -90)
    #expect(saved.lastRxAirtimeSeconds == 15)
    #expect(saved.neighborCount == 4)

    let stored = try #require(try await store.fetchRemoteNodeSession(id: seed.id))
    #expect(stored.unreadCount == 1)
    #expect(stored.notificationLevel == .muted)
    #expect(stored.name == "North Room")
    #expect(stored.isConnected == false)
  }

  @Test
  func `concurrent createSession calls keep one session id`() async throws {
    let (store, service) = try makeStack()
    let radioID = UUID()
    let publicKey = Data(repeating: 0x22, count: ProtocolLimits.publicKeySize)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: publicKey,
      name: "Shared Room",
      typeRawValue: ContactType.room.rawValue
    )
    let barrier = ArrivalBarrier(2)
    await service.setRoomReuseHoldForTesting {
      await barrier.arrive()
    }

    async let first = service.createSession(radioID: radioID, contact: contact)
    async let second = service.createSession(radioID: radioID, contact: contact)
    let saved = try await [first, second]

    #expect(saved[0].id == saved[1].id)
    let sessions = try await store.fetchRemoteNodeSessions(radioID: radioID)
    #expect(sessions.count == 1)
    #expect(sessions[0].isConnected == false)
    #expect(sessions[0].name == "Shared Room")
  }

  @Test
  func `reuse leaves another radio's session for the same key untouched`() async throws {
    let (store, service) = try makeStack()
    let publicKey = Data(repeating: 0x23, count: ProtocolLimits.publicKeySize)
    let radioID = UUID()
    let otherRadioID = UUID()
    let other = RemoteNodeSessionDTO(
      radioID: otherRadioID,
      publicKey: publicKey,
      name: "Other Radio",
      role: .roomServer,
      unreadCount: 7,
      notificationLevel: .muted,
      isPinned: true,
      lastSyncTimestamp: 40
    )
    try await store.saveRemoteNodeSessionDTO(other)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: publicKey,
      name: "This Radio",
      typeRawValue: ContactType.room.rawValue
    )

    let saved = try await service.createSession(radioID: radioID, contact: contact)
    let storedOther = try #require(try await store.fetchRemoteNodeSession(id: other.id))

    #expect(saved.id != other.id)
    #expect(saved.radioID == radioID)
    #expect(storedOther.name == "Other Radio")
    #expect(storedOther.unreadCount == 7)
    #expect(storedOther.notificationLevel == .muted)
    #expect(storedOther.isPinned == true)
    #expect(storedOther.lastSyncTimestamp == 40)
    #expect(storedOther.radioID == otherRadioID)
  }

  private func makeStack() throws -> (PersistenceStore, RemoteNodeService) {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let service = RemoteNodeService(
      session: MockMeshCoreSession(),
      dataStore: store,
      keychainService: KeychainService()
    )
    return (store, service)
  }
}

private actor SuspendGate {
  private var hasEntered = false
  private var entryWaiters: [CheckedContinuation<Void, Never>] = []
  private var releaseWaiter: CheckedContinuation<Void, Never>?
  private var isReleased = false

  func enter() {
    hasEntered = true
    let waiters = entryWaiters
    entryWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  func waitForEntry() async {
    if hasEntered { return }
    await withCheckedContinuation { entryWaiters.append($0) }
  }

  func waitForRelease() async {
    if isReleased { return }
    await withCheckedContinuation { releaseWaiter = $0 }
  }

  func release() {
    isReleased = true
    releaseWaiter?.resume()
    releaseWaiter = nil
  }
}

private actor ArrivalBarrier {
  private let expected: Int
  private var arrived = 0
  private var waiters: [CheckedContinuation<Void, Never>] = []

  init(_ expected: Int) {
    self.expected = expected
  }

  func arrive() async {
    arrived += 1
    if arrived >= expected {
      let pending = waiters
      waiters.removeAll()
      for waiter in pending {
        waiter.resume()
      }
      return
    }
    await withCheckedContinuation { waiters.append($0) }
  }
}
