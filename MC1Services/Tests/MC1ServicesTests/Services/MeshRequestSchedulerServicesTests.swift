import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Mesh request scheduler service guarantees", .timeLimit(.minutes(1)))
struct MeshRequestSchedulerServicesTests {
  @Test(arguments: [false, true])
  func `keepAliveRetryPolicy handles preemption without counting a failure`(afterHandOff: Bool) {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.sessionError(.preempted(afterHandOff: afterHandOff)),
      consecutiveFailures: &failures
    )
    #expect(action == .retryNextInterval)
    #expect(failures == 0)
  }

  @Test
  func `keychain freeze skips store and delete across repeater, sensor, and room when mayAnswerEarlierLogin is true`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let session = MockMeshCoreSession()
    let keychain = KeychainService()
    let remoteService = RemoteNodeService(
      session: session,
      dataStore: dataStore,
      keychainService: keychain
    )
    let repeaterAdmin = RepeaterAdminService(
      session: session,
      remoteNodeService: remoteService,
      dataStore: dataStore,
      radioID: radioID
    )
    let roomServer = RoomServerService(
      session: session,
      remoteNodeService: remoteService,
      dataStore: dataStore,
      radioID: radioID
    )

    let repeaterKey = Data(repeating: 0xA1, count: 32)
    let sensorKey = Data(repeating: 0xA2, count: 32)
    let roomKey = Data(repeating: 0xA3, count: 32)
    let preservedKeys = [repeaterKey, sensorKey, roomKey]
    for key in preservedKeys {
      try await keychain.storePassword("saved-secret", forNodeKey: key)
    }
    defer {
      Task {
        for key in preservedKeys {
          try? await keychain.deletePassword(forNodeKey: key)
        }
      }
    }

    let repeaterContact = ContactDTO.testContact(radioID: radioID, publicKey: repeaterKey, typeRawValue: ContactType.repeater.rawValue)
    let sensorContact = ContactDTO.testContact(radioID: radioID, publicKey: sensorKey, typeRawValue: ContactType.sensor.rawValue)
    let roomContact = ContactDTO.testContact(radioID: radioID, publicKey: roomKey, typeRawValue: ContactType.room.rawValue)
    try await dataStore.saveContact(repeaterContact)
    try await dataStore.saveContact(sensorContact)
    try await dataStore.saveContact(roomContact)

    func ambiguousLogin(for key: Data) -> Result<LoginExchangeResult, Error> {
      .success(LoginExchangeResult(
        info: LoginInfo(permissions: 0, isAdmin: true, publicKeyPrefix: Data(key.prefix(6))),
        mayAnswerEarlierLogin: true
      ))
    }

    await session.setStubbedLoginResults([
      ambiguousLogin(for: repeaterKey),
      ambiguousLogin(for: repeaterKey),
      ambiguousLogin(for: sensorKey),
      ambiguousLogin(for: sensorKey),
      ambiguousLogin(for: roomKey),
      ambiguousLogin(for: roomKey),
    ])

    _ = try await repeaterAdmin.connectAsAdmin(
      radioID: radioID, contact: repeaterContact, password: "typed-new", rememberPassword: true
    )
    _ = try await repeaterAdmin.connectAsAdmin(
      radioID: radioID, contact: repeaterContact, password: "typed-new", rememberPassword: false
    )
    #expect(try await keychain.retrievePassword(forNodeKey: repeaterKey) == "saved-secret")

    _ = try await remoteService.connectSensor(
      radioID: radioID, contact: sensorContact, password: "typed-new",
      rememberPassword: true, requireAdmin: false, pathLength: 0, onTimeoutKnown: nil
    )
    _ = try await remoteService.connectSensor(
      radioID: radioID, contact: sensorContact, password: "typed-new",
      rememberPassword: false, requireAdmin: false, pathLength: 0, onTimeoutKnown: nil
    )
    #expect(try await keychain.retrievePassword(forNodeKey: sensorKey) == "saved-secret")

    _ = try await roomServer.joinRoom(
      radioID: radioID, contact: roomContact, password: "typed-new", rememberPassword: true
    )
    _ = try await roomServer.joinRoom(
      radioID: radioID, contact: roomContact, password: "typed-new", rememberPassword: false
    )
    #expect(try await keychain.retrievePassword(forNodeKey: roomKey) == "saved-secret")
  }

  @Test
  func `getConnectedSession scopes by radioID and handleLinkDisconnection disconnects only rooms`() async throws {
    let radioA = UUID()
    let radioB = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioA)
    let session = MockMeshCoreSession()
    let remoteService = RemoteNodeService(
      session: session,
      dataStore: dataStore,
      keychainService: KeychainService(),
      radioID: radioA
    )

    let sharedRepeaterKey = Data(repeating: 0xB1, count: 32)
    let sharedRoomKey = Data(repeating: 0xB2, count: 32)
    let sensorKey = Data(repeating: 0xB3, count: 32)

    let repeaterOnA = RemoteNodeSessionDTO.testSession(
      radioID: radioA, publicKey: sharedRepeaterKey, role: .repeater, isConnected: true
    )
    let roomOnA = RemoteNodeSessionDTO.testSession(
      radioID: radioA, publicKey: sharedRoomKey, role: .roomServer, isConnected: true
    )
    let sensorOnA = RemoteNodeSessionDTO.testSession(
      radioID: radioA, publicKey: sensorKey, role: .sensor, isConnected: true
    )
    try await dataStore.saveRemoteNodeSessionDTO(repeaterOnA)
    try await dataStore.saveRemoteNodeSessionDTO(roomOnA)
    try await dataStore.saveRemoteNodeSessionDTO(sensorOnA)

    let repeaterAdminOnA = RepeaterAdminService(
      session: session, remoteNodeService: remoteService, dataStore: dataStore, radioID: radioA
    )
    let repeaterAdminOnB = RepeaterAdminService(
      session: session, remoteNodeService: remoteService, dataStore: dataStore, radioID: radioB
    )
    let roomAdminOnA = RoomAdminService(
      remoteNodeService: remoteService, dataStore: dataStore, radioID: radioA
    )
    let roomAdminOnB = RoomAdminService(
      remoteNodeService: remoteService, dataStore: dataStore, radioID: radioB
    )

    #expect(try await repeaterAdminOnA.getConnectedSession(publicKeyPrefix: Data(sharedRepeaterKey.prefix(6)))?.id == repeaterOnA.id)
    #expect(try await repeaterAdminOnB.getConnectedSession(publicKeyPrefix: Data(sharedRepeaterKey.prefix(6))) == nil)
    #expect(try await roomAdminOnA.getConnectedSession(publicKeyPrefix: Data(sharedRoomKey.prefix(6)))?.id == roomOnA.id)
    #expect(try await roomAdminOnB.getConnectedSession(publicKeyPrefix: Data(sharedRoomKey.prefix(6))) == nil)

    let disconnectedIDs = await remoteService.handleLinkDisconnection(radioID: radioA)
    #expect(disconnectedIDs == [roomOnA.id])
    #expect(try await dataStore.fetchRemoteNodeSession(id: roomOnA.id)?.isConnected == false)
    #expect(try await dataStore.fetchRemoteNodeSession(id: repeaterOnA.id)?.isConnected == true)
    #expect(try await dataStore.fetchRemoteNodeSession(id: sensorOnA.id)?.isConnected == true)
  }

  @Test
  func `CLIResponse read-only query predicate allows queries and excludes mutations`() {
    for query in [
      "ver", "get name", "get owner.info", "clock",
      "get radio", "get tx", "get repeat", "get advert.interval",
      "get flood.advert.interval", "get flood.max", "get lat", "get lon",
      "sensor list", "sensor list 12", "sensor get gps",
    ] {
      #expect(CLIResponse.isReadOnlyQuery(query), "Expected \(query) to be read-only")
    }
    for mutation in ["set name Foo", "sensor set gps 1", "reboot", "password secret", "board"] {
      #expect(!CLIResponse.isReadOnlyQuery(mutation), "Expected \(mutation) not to be read-only")
    }
  }

  @Test
  func `sensor CLI timeout probes via telemetry, waits settle, retries read-only, and skips mutating`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let key = Data(repeating: 0xC4, count: 32)
    try await dataStore.saveContact(ContactDTO.testContact(
      radioID: radioID, publicKey: key, typeRawValue: ContactType.sensor.rawValue,
      outPathLength: 1, outPath: Data([0x07])
    ))
    let remote = RemoteNodeSessionDTO.testSession(
      radioID: radioID, publicKey: key, role: .sensor, isConnected: true, permissionLevel: .admin
    )
    try await dataStore.saveRemoteNodeSessionDTO(remote)

    let session = MockMeshCoreSession()
    await session.seedRadioContact(MeshContact(
      id: key.hexString, publicKey: key, type: .sensor, flags: ContactFlags(rawValue: 0),
      outPathLength: 1, outPath: Data([0x07]), advertisedName: "Sensor",
      lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
      latitude: 0, longitude: 0, lastModified: Date(timeIntervalSince1970: 1_700_000_100)
    ))
    let clock = TestClock()
    let suiteName = "SensorCLIRecovery.\(UUID().uuidString)"
    UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
    defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }

    let contacts = try ContactService(
      session: session, dataStore: dataStore, syncCoordinator: nil, cleanupCoordinator: nil,
      clock: clock, defaults: #require(UserDefaults(suiteName: suiteName))
    )
    let owner = ContactRadioOwner()
    owner.bind(contacts)
    let service = try RemoteNodeService(
      session: session, clock: clock, dataStore: dataStore,
      keychainService: KeychainService(), contactRadioOwner: owner,
      defaults: #require(UserDefaults(suiteName: suiteName))
    )
    await service.startEventMonitoring()

    await session.setRequestTelemetryResults([
      .success(TelemetryResponse(publicKeyPrefix: Data(key.prefix(6)), tag: nil, rawData: Data([0x01]))),
      .success(TelemetryResponse(publicKeyPrefix: Data(key.prefix(6)), tag: nil, rawData: Data([0x01]))),
    ])

    let readTask = Task {
      try await service.sendRawCLICommand(sessionID: remote.id, command: "sensor list", timeout: .milliseconds(1))
    }
    try await waitUntil("sensor recovery sleeps on reciprocalPathSettle") {
      clock.sleeperCount == 1 && clock.nextSleepDuration == RemoteOperationTimeoutPolicy.reciprocalPathSettle
    }
    clock.advance(by: RemoteOperationTimeoutPolicy.reciprocalPathSettle)
    try await waitUntil("sensor CLI retries once") { await session.sendCommandInvocations.count == 2 }
    let retryCmd = await session.sendCommandInvocations[1].command
    let prefix = try #require(CLIResponse.splitEchoedPrefix(retryCmd)?.prefix)
    await session.yieldEvent(.contactMessageReceived(ContactMessage(
      senderPublicKeyPrefix: Data(key.prefix(6)), pathLength: 0, textType: 1,
      senderTimestamp: Date(), signature: nil, text: prefix + "1 vars\na=1", snr: nil
    )))
    #expect(try await readTask.value == "1 vars\na=1")
    #expect(await session.requestTelemetryPriorities == [.interactive])

    try await contacts.setPath(radioID: radioID, publicKey: key, path: Data([0x08]), pathLength: 1)
    let writeTask = Task {
      try await service.sendRawCLICommand(sessionID: remote.id, command: "sensor set gps 1", timeout: .milliseconds(1))
    }
    try await waitUntil("mutating recovery sleeps on reciprocalPathSettle") {
      clock.sleeperCount == 1 && clock.nextSleepDuration == RemoteOperationTimeoutPolicy.reciprocalPathSettle
    }
    clock.advance(by: RemoteOperationTimeoutPolicy.reciprocalPathSettle)
    await #expect(throws: RemoteNodeError.self) { _ = try await writeTask.value }
    #expect(await session.sendCommandInvocations.count == 3)
    await service.retire()
  }

  @Test
  func `attemptFloodFallback snapshots live radio contact, respects newer path T, and honors failedRepairWindow`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let key = Data(repeating: 0xD5, count: 32)
    try await dataStore.saveContact(ContactDTO.testContact(
      radioID: radioID, publicKey: key, outPathLength: 1, outPath: Data([0xAA])
    ))
    let liveContact = MeshContact(
      id: key.hexString, publicKey: key, type: .repeater, flags: ContactFlags(rawValue: 0),
      outPathLength: 2, outPath: Data([0xBB, 0xCC]), advertisedName: "Repeater",
      lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
      latitude: 0, longitude: 0, lastModified: Date(timeIntervalSince1970: 1_700_000_100)
    )
    let session = MockMeshCoreSession()
    await session.seedRadioContact(liveContact)

    let clock = TestClock()
    let suiteName = "AttemptFallbackWindow.\(UUID().uuidString)"
    UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
    defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }

    let contacts = try ContactService(
      session: session, dataStore: dataStore, syncCoordinator: nil, cleanupCoordinator: nil,
      clock: clock, defaults: #require(UserDefaults(suiteName: suiteName))
    )

    let newerPath = MeshContact(
      id: key.hexString, publicKey: key, type: .repeater, flags: ContactFlags(rawValue: 0),
      outPathLength: 1, outPath: Data([0xEE]), advertisedName: "Repeater",
      lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
      latitude: 0, longitude: 0, lastModified: Date(timeIntervalSince1970: 1_700_000_200)
    )
    await #expect(throws: SampleProbeError.self) {
      try await contacts.attemptFloodFallback(
        radioID: radioID, publicKey: key, rethrowIfUnavailable: SampleProbeError.caller
      ) {
        await session.seedRadioContact(newerPath)
        throw SampleProbeError.inner
      }
    }
    #expect(await session.addContactInvocations.isEmpty)
    #expect(try await dataStore.fetchContact(radioID: radioID, publicKey: key)?.outPath == Data([0xEE]))

    await session.seedRadioContact(liveContact)
    await #expect(throws: SampleProbeError.self) {
      try await contacts.attemptFloodFallback(
        radioID: radioID, publicKey: key, rethrowIfUnavailable: SampleProbeError.caller
      ) {
        throw SampleProbeError.inner
      }
    }
    #expect(await session.addContactInvocations.last?.contact.outPath == Data([0xBB, 0xCC]))
    #expect(await session.addContactInvocations.last?.contact.outPathLength == 2)

    let callerThrown = await #expect(throws: SampleProbeError.self) {
      _ = try await contacts.attemptFloodFallback(
        radioID: radioID, publicKey: key, rethrowIfUnavailable: SampleProbeError.caller
      ) {
        Issue.record("Should not run during failedRepairWindow")
        return 1
      }
    }
    #expect(callerThrown == .caller)

    clock.advance(by: RemoteOperationTimeoutPolicy.failedRepairWindow + .seconds(1))
    let recovered = try await contacts.attemptFloodFallback(
      radioID: radioID, publicKey: key, rethrowIfUnavailable: SampleProbeError.caller
    ) {
      42
    }
    #expect(recovered == 42)
  }

  @Test
  func `discoverPath writes setPath and stamps lastHeard, while ownedRegionQuery releases lock during RF wait`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let key = Data(repeating: 0xE6, count: 32)
    try await dataStore.saveContact(ContactDTO.testContact(
      radioID: radioID, publicKey: key, outPathLength: PacketBuilder.floodPathSentinel, outPath: Data()
    ))
    let meshContact = MeshContact(
      id: key.hexString, publicKey: key, type: .repeater, flags: ContactFlags(rawValue: 0),
      outPathLength: PacketBuilder.floodPathSentinel, outPath: Data(), advertisedName: "R",
      lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
      latitude: 0, longitude: 0, lastModified: Date(timeIntervalSince1970: 1_700_000_100)
    )
    let session = MockMeshCoreSession()
    await session.seedRadioContact(meshContact)
    let contacts = ContactService(
      session: session, dataStore: dataStore, syncCoordinator: nil, cleanupCoordinator: nil
    )

    await session.setStubbedDiscoverPathResults([
      .success(PathInfo(
        publicKeyPrefix: Data(key.prefix(6)),
        outPathLength: 2,
        outPath: Data([0x11, 0x22]),
        inPathLength: 1,
        inPath: Data([0x33])
      )),
    ])
    let pathInfo = try await contacts.discoverPath(
      radioID: radioID, publicKey: key, priority: .interactive, budget: { _ in 10 }
    )
    #expect(pathInfo.outHopCount == 2)
    #expect(await session.discoverPathInvocations.first?.priority == .interactive)
    let saved = try #require(await dataStore.fetchContact(radioID: radioID, publicKey: key))
    #expect(saved.outPath == Data([0x11, 0x22]))
    #expect(saved.outPathLength == 2)
    #expect((saved.lastHeardTimestamp ?? 0) > 0)

    let queryGate = AsyncGate()
    let queryTask = Task {
      try await contacts.ownedRegionQuery(target: meshContact, radioID: radioID) { _ in
        await queryGate.signalEntered()
        await queryGate.wait()
        return ["us-west"]
      }
    }
    await queryGate.waitEntered()
    try await contacts.setPath(radioID: radioID, publicKey: key, path: Data([0x44]), pathLength: 1)
    await queryGate.release()
    #expect(try await queryTask.value == ["us-west"])
    let radioAfterConcurrentSetPath = try #require(await session.getContact(publicKey: key))
    #expect(radioAfterConcurrentSetPath.outPath == Data([0x44]))
    #expect(radioAfterConcurrentSetPath.outPathLength == 1)

    try await contacts.resetPath(radioID: radioID, publicKey: key)
    let floodResult = try await contacts.ownedRegionQuery(target: meshContact, radioID: radioID) { resolved in
      #expect(resolved.outPathLength == 0)
      return ["us-east"]
    }
    #expect(floodResult == ["us-east"])
    let radioAfterUncontestedFlood = try #require(await session.getContact(publicKey: key))
    #expect(radioAfterUncontestedFlood.outPathLength == PacketBuilder.floodPathSentinel)
    #expect(radioAfterUncontestedFlood.outPath.isEmpty)
  }

  @Test
  func `repeaterAdmin fetchAllNeighbors waits at MeshRequestGate without pre-admission timeout`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let repeaterKey = Data(repeating: 0xF1, count: 32)
    let otherKey = Data(repeating: 0xF2, count: 32)
    let remote = RemoteNodeSessionDTO.testSession(
      radioID: radioID, publicKey: repeaterKey, role: .repeater, isConnected: true, permissionLevel: .admin
    )
    try await dataStore.saveRemoteNodeSessionDTO(remote)

    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(
      transport: transport,
      configuration: .init(binaryRequestOverallTimeout: 40, binaryRequestRetransmitInterval: nil),
      clock: clock
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("appStart is written") {
      await transport.sentData.contains { $0.first == CommandCode.appStart.rawValue }
    }
    await transport.simulateReceive(
      Data([ResponseCode.selfInfo.rawValue, 1, 22, 22]) + Data(repeating: 0, count: 54)
    )
    try await startTask.value
    await transport.clearSentData()

    let remoteService = RemoteNodeService(
      session: session, dataStore: dataStore, keychainService: KeychainService(), radioID: radioID
    )
    let repeaterAdmin = RepeaterAdminService(
      session: session, remoteNodeService: remoteService, dataStore: dataStore, radioID: radioID
    )

    let statusTask = Task {
      try await session.requestStatus(from: otherKey, priority: .interactive)
    }
    try await waitUntil("status request is written") {
      await transport.sentData.contains { $0.first == CommandCode.sendStatusRequest.rawValue }
    }
    await transport.simulateReceive(
      Data([ResponseCode.messageSent.rawValue, 0, 0x01, 0x00, 0x00, 0x00, 0, 0, 0, 0])
    )
    try await waitUntil("status parked on RF wait") {
      clock.sleeperCount == 1 && clock.nextSleepDuration == .seconds(40)
    }

    let neighborsTask = Task {
      try await repeaterAdmin.fetchAllNeighbors(sessionID: remote.id, priority: .interactive)
    }
    try await waitUntil("neighbors queues at MeshRequestGate") {
      await session.meshRequestGate.queuedWaiterCount == 1
    }

    clock.advance(by: .seconds(30))
    await transport.simulateReceive(
      Data([ResponseCode.statusResponse.rawValue, 0]) + Data(otherKey.prefix(6)) + Data(repeating: 0, count: 51)
    )
    _ = try await statusTask.value

    try await waitUntil("neighbors binary request is written") {
      await transport.sentData.contains { $0.first == CommandCode.binaryRequest.rawValue }
    }
    let tag = Data([0x22, 0x00, 0x00, 0x00])
    await transport.simulateReceive(
      Data([ResponseCode.messageSent.rawValue, 0]) + tag + Data([0, 0, 0, 0])
    )
    try await waitUntil("neighbors parked on full 40s post-admission budget") {
      clock.sleeperCount == 1 && clock.nextSleepDuration == .seconds(40)
    }

    clock.advance(by: .seconds(20))
    await transport.simulateReceive(
      Data([ResponseCode.binaryResponse.rawValue, 0]) + tag + Data([0, 0, 0, 0])
    )
    let response = try await neighborsTask.value
    #expect(response.totalCount == 0)
    #expect(response.neighbours.isEmpty)
    await session.stop()
  }
}

private enum SampleProbeError: Error, Equatable {
  case caller
  case inner
}

private actor AsyncGate {
  private var entered = false
  private var released = false
  private var enteredWaiters: [CheckedContinuation<Void, Never>] = []
  private var releaseWaiters: [CheckedContinuation<Void, Never>] = []

  func signalEntered() {
    entered = true
    let w = enteredWaiters
    enteredWaiters.removeAll()
    w.forEach { $0.resume() }
  }

  func waitEntered() async {
    if entered { return }
    await withCheckedContinuation { enteredWaiters.append($0) }
  }

  func wait() async {
    if released { return }
    await withCheckedContinuation { releaseWaiters.append($0) }
  }

  func release() {
    released = true
    let w = releaseWaiters
    releaseWaiters.removeAll()
    w.forEach { $0.resume() }
  }
}
