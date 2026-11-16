import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Room keep-alive recovery", .timeLimit(.minutes(1)))
struct RoomKeepAliveRecoveryTests {
  private static let savedBookmark: UInt32 = 40
  private static let missingPost: UInt32 = 41
  private static let newestPost: UInt32 = 42

  private struct Harness {
    let room: RemoteNodeSessionDTO
    let store: PersistenceStore
    let transport: RoomReplayTransport
    let mesh: MeshCoreSession
    let service: RemoteNodeService
    let roomService: RoomServerService
    let lifetime: ReceiveScope.Lifetime
    let policy: Policy
  }

  private actor Policy {
    private var preference = TextRulesPreference()

    init() {
      preference.setSelection(.all, for: .room)
    }

    func snapshot() -> TextRulesPreference {
      preference
    }

    func turnOff() {
      preference.setSelection(.off, for: .room)
    }

    func block(_ text: String) {
      preference.append(.init(phrase: text, lookIn: .message, match: .wholeField))
    }
  }

  private func makeHarness(
    bookmark: UInt32 = Self.savedBookmark,
    clock: TestClock? = nil
  ) async throws -> Harness {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let room = RemoteNodeSessionDTO.testSession(
      radioID: radioID,
      isConnected: true,
      lastSyncTimestamp: bookmark
    )
    try await store.saveRemoteNodeSessionDTO(room)
    try await store.saveContact(ContactDTO.testContact(
      radioID: radioID,
      publicKey: room.publicKey,
      outPathLength: 0
    ))
    let saved = try #require(try await store.fetchRemoteNodeSession(id: room.id))
    let transport = RoomReplayTransport(serverCursor: Self.newestPost)
    let mesh = MeshCoreSession(transport: transport, clock: clock)
    try await mesh.start()

    let lifetime = ReceiveScope.Lifetime()
    let policy = Policy()
    let service = RemoteNodeService(
      session: mesh,
      dataStore: store,
      keychainService: KeychainService(),
      textRulesPreference: { await policy.snapshot() },
      receiveLifetime: lifetime
    )
    let roomService = RoomServerService(
      session: mesh, remoteNodeService: service, dataStore: store, radioID: radioID,
      textRulesPreference: { await policy.snapshot() }, receiveLifetime: lifetime
    )
    return Harness(
      room: saved, store: store, transport: transport, mesh: mesh, service: service,
      roomService: roomService, lifetime: lifetime, policy: policy
    )
  }

  private func admitNextPost(_ harness: Harness) async throws -> RoomMessageDTO? {
    guard let timestamp = await harness.transport.nextPost else { return nil }
    let saved = try await harness.roomService.handleIncomingMessage(
      senderPublicKeyPrefix: Data(harness.room.publicKey.prefix(6)),
      timestamp: timestamp,
      authorPrefix: Data([0xAB, 0xCD, 0xEF, 0x12]),
      text: "Post \(timestamp)"
    )
    await harness.transport.acknowledgeNextPost()
    return saved
  }

  private func holdRequestSlot(_ harness: Harness) async throws -> Task<Bool, Error> {
    let held = Task {
      try await harness.mesh.sendAndWait(Data([CommandCode.setTime.rawValue])) { event in
        if case .ok = event { return true }
        return nil
      }
    }
    try await waitUntil("unrelated command never held the request slot") {
      await harness.transport.isRequestHeld
    }
    return held
  }

  private func reserveRecovery(_ harness: Harness) async throws -> Task<Void, Error> {
    let key = try #require(harness.room.textRulesID)
    await harness.service.startSessionKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    try await waitUntil("no recovery reserved") {
      await harness.service.roomKeepAliveSchedules[key]?.recovery != nil
    }
    return try #require(await harness.service.roomKeepAliveSchedules[key]?.recovery)
  }

  @Test
  func `restarting a room before submission still rewinds once to recover the missed post`() async throws {
    let harness = try await makeHarness()
    defer {
      Task {
        await harness.service.stopAllKeepAlives()
        await harness.mesh.stop()
      }
    }
    let hiddenText = "Filtered post"
    await harness.policy.block(hiddenText)
    #expect(try await harness.roomService.handleIncomingMessage(
      senderPublicKeyPrefix: Data(harness.room.publicKey.prefix(6)),
      timestamp: Self.newestPost,
      authorPrefix: Data([0xAB, 0xCD, 0xEF, 0x12]),
      text: hiddenText
    ) == nil)
    #expect(try await harness.store.recentlyBlockedMessages().map(\.body) == [hiddenText])
    #expect(try await harness.store.fetchRemoteNodeSession(id: harness.room.id)?.lastSyncTimestamp == Self.savedBookmark)

    let held = try await holdRequestSlot(harness)
    let recovery = try await reserveRecovery(harness)

    #expect(await harness.transport.watermarks.isEmpty)
    #expect(await harness.transport.nextPost == nil)
    await harness.service.stopSessionKeepAlive(sessionID: harness.room.id)
    await harness.service.startSessionKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    await harness.transport.releaseRequest()
    #expect(try await held.value)
    try await recovery.value

    await harness.service.stopSessionKeepAlive(sessionID: harness.room.id)
    #expect(try await admitNextPost(harness)?.timestamp == Self.missingPost)

    for _ in 0..<2 {
      try await harness.service.sendScheduledKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    }
    let watermarks = await harness.transport.watermarks
    #expect(watermarks.first == Self.savedBookmark)
    #expect(watermarks.count >= 3)
    #expect(watermarks.dropFirst().allSatisfy { $0 == 0 })
  }

  @Test
  func `slow replay advances across routine keep-alives and a policy change`() async throws {
    let harness = try await makeHarness()
    defer { Task { await harness.mesh.stop() } }
    try await harness.service.sendScheduledKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    #expect(try await admitNextPost(harness)?.timestamp == Self.missingPost)

    try await harness.service.sendScheduledKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    #expect(await harness.transport.nextPost == Self.newestPost)
    await harness.policy.turnOff()

    try await harness.service.sendScheduledKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    #expect(try await admitNextPost(harness)?.timestamp == Self.newestPost)
    #expect(await harness.transport.watermarks == [Self.savedBookmark, 0, 0])
  }

  @Test
  func `a retired Link record cannot submit its reservation and replacement owns a new rewind`() async throws {
    let harness = try await makeHarness()
    defer {
      Task {
        await harness.service.stopAllKeepAlives()
        await harness.mesh.stop()
      }
    }
    let held = try await holdRequestSlot(harness)
    let recovery = try await reserveRecovery(harness)

    harness.lifetime.retire()
    await harness.service.stopSessionKeepAlive(sessionID: harness.room.id)
    await harness.transport.releaseRequest()
    #expect(try await held.value)
    await #expect(throws: CancellationError.self) { try await recovery.value }
    #expect(await harness.transport.watermarks.isEmpty)

    await harness.mesh.stop(disconnectTransport: false)
    let replacementMesh = MeshCoreSession(transport: harness.transport)
    try await replacementMesh.start()
    defer { Task { await replacementMesh.stop() } }

    let replacement = RemoteNodeService(
      session: replacementMesh,
      dataStore: harness.store,
      keychainService: KeychainService(),
      textRulesPreference: { await harness.policy.snapshot() }
    )
    try await replacement.sendScheduledKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    try await replacement.sendScheduledKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    #expect(await harness.transport.watermarks == [Self.savedBookmark, 0])
  }

  @Test
  func `a rejected rewind retries the bookmark before routine keep-alives send zero`() async throws {
    let harness = try await makeHarness()
    defer { Task { await harness.mesh.stop() } }
    let sessionID = harness.room.id
    let publicKey = harness.room.publicKey

    await harness.transport.rejectNextRequest()
    await #expect {
      try await harness.service.sendScheduledKeepAlive(sessionID: sessionID, publicKey: publicKey)
    } throws: { error in
      guard case let .sessionError(meshError) = error as? RemoteNodeError else { return false }
      return meshError.deviceErrorCode == .tableFull
    }
    #expect(await harness.transport.nextPost == nil)

    try await harness.service.sendScheduledKeepAlive(sessionID: sessionID, publicKey: publicKey)
    #expect(try await admitNextPost(harness)?.timestamp == Self.missingPost)
    try await harness.service.sendScheduledKeepAlive(sessionID: sessionID, publicKey: publicKey)
    #expect(await harness.transport.watermarks == [Self.savedBookmark, Self.savedBookmark, 0])
    #expect(await harness.transport.nextPost == Self.newestPost)
  }

  @Test
  func `a lost rewind response preserves the replay cursor`() async throws {
    let clock = TestClock()
    let harness = try await makeHarness(clock: clock)
    defer { Task { await harness.mesh.stop() } }
    let sessionID = harness.room.id
    let publicKey = harness.room.publicKey

    await harness.transport.loseNextResponse()
    let recovery = Task {
      try await harness.service.sendScheduledKeepAlive(sessionID: sessionID, publicKey: publicKey)
    }
    try await waitUntil("rewind was not sent or the timeout did not start") {
      await harness.transport.watermarks == [Self.savedBookmark] && clock.sleeperCount == 1
    }
    clock.advance(by: .seconds(SessionConfiguration.default.defaultTimeout))
    await #expect {
      try await recovery.value
    } throws: { error in
      guard case .sessionError(.timeout) = error as? RemoteNodeError else { return false }
      return true
    }

    #expect(try await admitNextPost(harness)?.timestamp == Self.missingPost)
    try await harness.service.sendScheduledKeepAlive(sessionID: sessionID, publicKey: publicKey)
    #expect(await harness.transport.watermarks == [Self.savedBookmark, 0])
    #expect(await harness.transport.nextPost == Self.newestPost)
  }

  @Test
  func `zero bookmark starts a zero-only schedule`() async throws {
    let harness = try await makeHarness(bookmark: 0)
    defer { Task { await harness.mesh.stop() } }
    try await harness.service.sendScheduledKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    try await harness.store.updateRoomActivity(harness.room.id, syncTimestamp: Self.missingPost)
    try await harness.service.sendScheduledKeepAlive(sessionID: harness.room.id, publicKey: harness.room.publicKey)
    #expect(await harness.transport.watermarks == [0, 0])
  }

  @Test
  func `distinct refreshes send the latest bookmark even after a lost request`() async throws {
    let harness = try await makeHarness()
    defer { Task { await harness.mesh.stop() } }
    await harness.transport.dropNextRequest()
    try await harness.service.refreshRoom(harness.room)
    #expect(await harness.transport.nextPost == nil)
    #expect(await harness.transport.watermarks == [Self.savedBookmark])

    let held = try await holdRequestSlot(harness)
    let key = try #require(harness.room.textRulesID)
    async let first: Void = harness.service.refreshRoom(harness.room)
    async let concurrent: Void = harness.service.refreshRoom(harness.room)
    try await waitUntil("no refresh reserved") { await harness.service.roomKeepAliveSchedules[key]?.refresh != nil }
    try await harness.store.updateRoomActivity(harness.room.id, syncTimestamp: Self.missingPost)
    await harness.transport.releaseRequest()
    #expect(try await held.value)
    _ = try await (first, concurrent)
    #expect(await harness.transport.watermarks == [Self.savedBookmark, Self.missingPost])
    #expect(await harness.transport.nextPost == Self.newestPost)
  }

  /// Direct keep-alive semantics from the room server: a positive watermark rewinds,
  /// zero preserves the cursor, and a post ACK advances it.
  private actor RoomReplayTransport: MeshTransport {
    private var stream = AsyncStream.makeStream(of: Data.self)
    private var serverCursor: UInt32
    private var dropRequest = false
    private var nextResponse: NextResponse?

    private enum NextResponse {
      case tableFull
      case lost
    }

    private(set) var isConnected = false
    private(set) var isRequestHeld = false
    private(set) var watermarks: [UInt32] = []

    var receivedData: AsyncStream<Data> {
      stream.stream
    }

    var nextPost: UInt32? {
      [RoomKeepAliveRecoveryTests.missingPost, RoomKeepAliveRecoveryTests.newestPost].first { $0 > serverCursor }
    }

    init(serverCursor: UInt32) {
      self.serverCursor = serverCursor
    }

    func connect() {
      stream = AsyncStream.makeStream(of: Data.self)
      isConnected = true
    }

    func disconnect() {
      isConnected = false
      stream.continuation.finish()
    }

    func acknowledgeNextPost() {
      if let nextPost { serverCursor = nextPost }
    }

    func dropNextRequest() {
      dropRequest = true
    }

    func rejectNextRequest() {
      nextResponse = .tableFull
    }

    func loseNextResponse() {
      nextResponse = .lost
    }

    func releaseRequest() {
      stream.continuation.yield(Data([ResponseCode.ok.rawValue]))
      isRequestHeld = false
    }

    func send(_ data: Data) throws {
      switch data.first {
      case CommandCode.appStart.rawValue:
        stream.continuation.yield(Self.selfInfoPacket())
      case CommandCode.setTime.rawValue:
        isRequestHeld = true
      case CommandCode.binaryRequest.rawValue:
        guard data.count == 38, data[33] == BinaryRequestType.keepAlive.rawValue else {
          throw MeshTransportError.sendFailed("Invalid keep-alive command")
        }
        let watermark = data.suffix(4).withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(as: UInt32.self)) }
        watermarks.append(watermark)
        let response = nextResponse
        nextResponse = nil
        if response == .tableFull {
          stream.continuation.yield(Data([ResponseCode.error.rawValue, ErrorCode.tableFull.rawValue]))
          return
        }
        if dropRequest {
          dropRequest = false
        } else if watermark > 0 {
          serverCursor = watermark
        }
        if response == .lost { return }
        stream.continuation.yield(Data([ResponseCode.messageSent.rawValue, 0, 1, 2, 3, 4, 1, 0, 0, 0]))
      default:
        throw MeshTransportError.sendFailed("Unexpected test command")
      }
    }

    private static func selfInfoPacket() -> Data {
      var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
      packet.append(Data(repeating: 0x01, count: 32))
      packet.append(Data(repeating: 0, count: 12))
      for value: UInt32 in [915_000, 125_000] {
        packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
      }
      packet.append(contentsOf: [7, 5])
      return packet
    }
  }
}
