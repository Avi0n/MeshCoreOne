import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("Shortcut recipient wiring", .serialized, .timeLimit(.minutes(1)))
@MainActor
struct RecipientPublicationWiringTests {
  private let preferences = IsolatedUserDefaults()

  private var defaults: UserDefaults {
    preferences.defaults
  }

  @MainActor
  final class PublishCounter {
    var count = 0
  }

  private static let watchdogDelay: Duration = .seconds(60)
  private static let radioID = UUID(uuidString: "CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")!
  private static let deviceKey = Data(repeating: 0x11, count: ProtocolLimits.publicKeySize)
  private static let staleContactKey = Data(repeating: 0x22, count: ProtocolLimits.publicKeySize)
  private static let secret = Data(repeating: 0x5A, count: ProtocolLimits.channelSecretSize)
  private static let otherSecret = Data(repeating: 0x5B, count: ProtocolLimits.channelSecretSize)

  @Test func `wireServicesIfConnected does not publish`() async throws {
    let appState = try makeAppState()
    defer { appState.shutdown() }
    let counter = installCounter(on: appState)
    let session = MeshCoreSession(transport: MockTransport())
    let services = ServiceContainer(
      session: session,
      dataStore: appState.connectionManager.persistenceStore,
      radioID: Self.radioID
    )
    defer {
      Task {
        await services.tearDown()
        await session.stop()
      }
    }
    appState.connectionManager.setTestState(connectionState: .connected, services: services)

    await appState.wireServicesIfConnected()

    #expect(counter.count == 0)
    #expect(appState.servicesVersion == 1)
    #expect(appState.lastPublishedRecipientIDs == nil)
  }

  @Test func `one channel publishes once`() async throws {
    let run = try await prepareRadio()
    defer { run.finish() }
    try await saveChannel(store: run.store, index: 0, name: "Ops", secret: Self.secret)
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)

    await callback(true)
    await callback(true)

    #expect(run.counter.count == 1)
    let published = try #require(run.appState.lastPublishedRecipientIDs)
    #expect(published == Set([channelID(secret: Self.secret)]))
  }

  @Test func `an empty read publishes once and records the empty set`() async throws {
    let run = try await prepareRadio()
    defer { run.finish() }
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)

    await callback(true)
    await callback(true)

    #expect(run.counter.count == 1)
    #expect(run.appState.lastPublishedRecipientIDs == Set<String>())
  }

  /// The old slot stays until a later primary read reports it unconfigured.
  /// An ineligible callback and a channel-only retry leave the previous set in place.
  @Test func `a duplicate secret keeps the previous publication`() async throws {
    let run = try await prepareRadio()
    defer { run.finish() }
    try await saveChannel(store: run.store, index: 0, name: "Ops", secret: Self.secret)
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)
    await callback(true)
    #expect(run.counter.count == 1)
    let published = try #require(run.appState.lastPublishedRecipientIDs)

    try await saveChannel(store: run.store, index: 1, name: "Copy", secret: Self.secret)
    let services = attachIdleServices(appState: run.appState, store: run.store)
    defer {
      Task {
        await services.services.tearDown()
        await services.session.stop()
      }
    }
    run.appState.connectionManager.scheduleChannelOnlyRetry(
      radioID: Self.radioID,
      services: services.services,
      indices: [1]
    )
    #expect(run.appState.connectionManager.channelRetryTask != nil)

    await callback(false)
    #expect(run.counter.count == 1)
    #expect(run.appState.lastPublishedRecipientIDs == published)
    run.appState.connectionManager.cancelChannelRetry()
    #expect(run.appState.connectionManager.channelRetryTask == nil)

    let channelResult = try await readPrimaryChannels(
      store: run.store,
      slot0: ("", Data(repeating: 0, count: ProtocolLimits.channelSecretSize)),
      slot1: ("Ops", Self.secret)
    )
    let stored = try await run.store.fetchChannels(radioID: Self.radioID)
    #expect(stored.map(\.secret) == [Self.secret])
    let status: SyncPhaseStatus = channelResult.isComplete ? .clean : .partial
    let full = FullSyncResult(contacts: .clean, channels: status, messages: .failed("catch-up"))
    #expect(full.hasCleanRecipientData)

    await callback(full.hasCleanRecipientData)

    #expect(run.counter.count == 1)
    #expect(run.appState.lastPublishedRecipientIDs == published)
  }

  @Test func `an ineligible sync cleans up and does not publish`() async throws {
    let run = try await prepareCleanup(channels: [
      (0, "Old", Self.secret),
      (1, "Copy", Self.secret)
    ])
    defer { run.finish() }
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)

    await callback(false)
    let task = try #require(run.appState.staleNodeCleanupTask)
    try await waitForCommands(1, transport: run.transport)
    _ = try await run.store.batchSaveChannels(
      radioID: Self.radioID,
      configured: [ChannelInfo(index: 1, name: "Ops", secret: Self.secret)],
      unconfiguredIndices: [0],
      pruneBeyond: 2
    )
    let offered = try await offeredIDs(store: run.store)
    let storedSecrets = try await run.store.fetchChannels(radioID: Self.radioID).map(\.secret)
    #expect(storedSecrets == [Self.secret])
    #expect(offered.contains(channelID(secret: Self.secret)))
    #expect(run.counter.count == 0)

    await run.transport.simulateOK()
    await task.value

    #expect(try await run.store.fetchContacts(radioID: Self.radioID).isEmpty)
    #expect(run.counter.count == 0)
    #expect(run.appState.lastPublishedRecipientIDs == nil)
  }

  @Test func `an eligible sync publishes after cleanup removes the stale contact`() async throws {
    let run = try await prepareCleanup(channels: [(0, "Ops", Self.secret)])
    defer { run.finish() }
    let before = try await offeredIDs(store: run.store)
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)

    await callback(true)
    let task = try #require(run.appState.staleNodeCleanupTask)
    try await waitForCommands(1, transport: run.transport)
    #expect(run.counter.count == 0)

    await run.transport.simulateOK()
    await task.value

    let after = try await offeredIDs(store: run.store)
    #expect(after != before)
    #expect(after == Set([channelID(secret: Self.secret)]))
    #expect(run.counter.count == 1)
    #expect(run.appState.lastPublishedRecipientIDs == after)
  }

  @Test func `a cancelled cleanup does not publish`() async throws {
    let run = try await prepareCleanup(channels: [(0, "Ops", Self.secret)])
    defer { run.finish() }
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)

    await callback(true)
    let task = try #require(run.appState.staleNodeCleanupTask)
    try await waitForCommands(1, transport: run.transport)
    task.cancel()
    await run.transport.simulateOK()
    await task.value

    #expect(run.counter.count == 0)
    #expect(run.appState.lastPublishedRecipientIDs == nil)
  }

  @Test func `replaced services do not publish after cleanup`() async throws {
    let run = try await prepareCleanup(channels: [(0, "Ops", Self.secret)])
    defer { run.finish() }
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)

    await callback(true)
    let task = try #require(run.appState.staleNodeCleanupTask)
    try await waitForCommands(1, transport: run.transport)
    let replacement = attachIdleServices(appState: run.appState, store: run.store)
    defer {
      Task {
        await replacement.services.tearDown()
        await replacement.session.stop()
      }
    }
    #expect(run.counter.count == 0)

    await run.transport.simulateOK()
    await task.value

    #expect(run.counter.count == 0)
    #expect(run.appState.lastPublishedRecipientIDs == nil)
  }

  @Test func `a second callback publishes once after the same cleanup`() async throws {
    let run = try await prepareCleanup(channels: [(0, "Ops", Self.secret)])
    defer { run.finish() }
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)

    await callback(true)
    let task = try #require(run.appState.staleNodeCleanupTask)
    try await waitForCommands(1, transport: run.transport)
    var joinedCleanup = false
    var secondCompleted = false
    run.appState.beforeShortcutRecipientCleanupAwaitForTesting = { joinedCleanup = true }
    let second = Task {
      await callback(true)
      secondCompleted = true
    }
    defer { second.cancel() }
    try await waitUntil("second sync callback should join the pending cleanup") { joinedCleanup }
    #expect(!secondCompleted)
    #expect(run.counter.count == 0)

    await run.transport.simulateOK()
    await second.value
    await task.value

    #expect(run.counter.count == 1)
    #expect(run.appState.lastPublishedRecipientIDs == Set([channelID(secret: Self.secret)]))
  }

  @Test func `notifyDataRestored publishes only when the set changes`() async throws {
    let run = try await prepareRadio()
    defer { run.finish() }
    try await saveChannel(store: run.store, index: 0, name: "Ops", secret: Self.secret)
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)
    await callback(true)
    #expect(run.counter.count == 1)

    await run.appState.notifyDataRestored().value
    #expect(run.counter.count == 1)

    try await saveChannel(store: run.store, index: 1, name: "Extra", secret: Self.otherSecret)
    await run.appState.notifyDataRestored().value

    #expect(run.counter.count == 2)
    #expect(run.appState.lastPublishedRecipientIDs == Set([
      channelID(secret: Self.secret),
      channelID(secret: Self.otherSecret)
    ]))
  }

  @Test func `a radio cleared after capturing the reread scope does not publish`() async throws {
    let run = try await prepareRadio()
    defer { run.finish() }
    try await saveChannel(store: run.store, index: 0, name: "Ops", secret: Self.secret)
    run.appState.beforeShortcutRecipientRereadForTesting = { [weak appState = run.appState] in
      appState?.connectionManager.setTestState(connectedDevice: .some(nil))
    }
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)

    await callback(true)

    #expect(run.counter.count == 0)
    #expect(run.appState.lastPublishedRecipientIDs == nil)
  }

  @Test(arguments: [false, true])
  func `retired cleanup cannot publish after cancellation or services replacement`(replaceServices: Bool) async throws {
    let run = try await prepareCleanup(channels: [(0, "Ops", Self.secret)])
    defer { run.finish() }
    let replacementSession = MeshCoreSession(transport: MockTransport())
    let replacement = ServiceContainer(
      session: replacementSession,
      dataStore: run.store,
      radioID: Self.radioID
    )
    defer {
      Task {
        await replacement.tearDown()
        await replacementSession.stop()
      }
    }
    run.appState.beforeShortcutRecipientRereadForTesting = { [weak appState = run.appState] in
      guard let appState else { return }
      if replaceServices {
        appState.connectionManager.setTestState(services: replacement)
      } else {
        appState.cancelStaleNodeCleanup()
      }
    }
    let callback = try #require(run.appState.connectionManager.onDeviceSynced)
    await callback(true)
    let task = try #require(run.appState.staleNodeCleanupTask)
    try await waitForCommands(1, transport: run.transport)
    await run.transport.simulateOK()
    await task.value
    #expect(run.appState.currentRadioID == Self.radioID)
    #expect(run.counter.count == 0)
    #expect(run.appState.lastPublishedRecipientIDs == nil)
  }

  @MainActor
  private final class CleanupGate {
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      guard !opened else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      guard !opened else { return }
      opened = true
      continuation?.resume()
      continuation = nil
    }
  }

  @Test func `promotion rejects connection loss during the sync callback`() async throws {
    let run = try await prepareRadio()
    defer { run.finish() }
    let appState = run.appState
    let manager = appState.connectionManager
    let attached = attachIdleServices(appState: appState, store: run.store)
    let deviceID = try #require(manager.connectedDevice?.id)
    let originalCallback = try #require(manager.onDeviceSynced)
    defaults.set(7, forKey: AppStorageKey.autoDeleteStaleNodesDays.rawValue)
    manager.setTestState(
      connectionState: .connected,
      services: attached.services,
      session: attached.session,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    manager.testWatchdogInitialDelay = Self.watchdogDelay
    let cleanupRelease = CleanupGate()
    let callbackSignal = CleanupGate()
    var callbackEntered = false
    var promotionReturned = false
    let cleanup = Task { await cleanupRelease.wait() }
    appState.staleNodeCleanupTask = cleanup
    appState.staleNodeCleanupServiceID = ObjectIdentifier(attached.services)
    manager.onDeviceSynced = { eligible in
      callbackEntered = true
      callbackSignal.open()
      await originalCallback(eligible)
    }
    defer {
      cleanupRelease.open()
      manager.stopReconnectionWatchdog()
      Task {
        await cleanup.value
        await attached.services.tearDown()
        await attached.session.stop()
      }
    }
    let promotion = Task {
      defer { callbackSignal.open() }
      let result = await manager.promoteToReady(
        syncResult: FullSyncResult(contacts: .clean, channels: .clean, messages: .clean),
        expectedServices: attached.services,
        transportType: .bluetooth
      )
      promotionReturned = true
      return result
    }
    await callbackSignal.wait()
    try #require(callbackEntered)
    #expect(!promotionReturned)
    #expect(manager.connectionState == .ready)
    await manager.handleConnectionLoss(deviceID: deviceID, error: nil)
    #expect(manager.connectionState == .disconnected)
    #expect(manager.services == nil)
    #expect(manager.isReconnectionWatchdogRunning)
    #expect(!promotionReturned)
    cleanupRelease.open()
    let promoted = await promotion.value
    #expect(!promoted)
    #expect(manager.connectionState == .disconnected)
    #expect(manager.services == nil)
    #expect(manager.isReconnectionWatchdogRunning)
  }
}

extension RecipientPublicationWiringTests {
  fileprivate struct RadioRun {
    let appState: AppState
    let store: PersistenceStore
    let counter: PublishCounter

    @MainActor
    func finish() {
      appState.shutdown()
    }
  }

  fileprivate struct CleanupRun {
    let appState: AppState
    let store: PersistenceStore
    let transport: MockTransport
    let services: ServiceContainer
    let session: MeshCoreSession
    let counter: PublishCounter

    @MainActor
    func finish() {
      appState.shutdown()
      let services = services
      let session = session
      Task {
        await services.tearDown()
        await session.stop()
      }
    }
  }

  fileprivate struct IdleServices {
    let services: ServiceContainer
    let session: MeshCoreSession
  }

  private func makeAppState() throws -> AppState {
    let config = ModelConfiguration(isStoredInMemoryOnly: true)
    let container = try ModelContainer(for: PersistenceStore.schema, configurations: [config])
    return AppState(modelContainer: container, isPlaceholder: true, defaults: defaults)
  }

  private func installCounter(on appState: AppState) -> PublishCounter {
    let counter = PublishCounter()
    appState.updateShortcutParametersForTesting = { counter.count += 1 }
    return counter
  }

  private func prepareRadio() async throws -> RadioRun {
    defaults.set(0, forKey: AppStorageKey.autoDeleteStaleNodesDays.rawValue)
    let appState = try makeAppState()
    let store = appState.connectionManager.persistenceStore
    try await installDevice(store: store, appState: appState)
    let counter = installCounter(on: appState)
    return RadioRun(appState: appState, store: store, counter: counter)
  }

  private func prepareCleanup(
    channels: [(index: UInt8, name: String, secret: Data)]
  ) async throws -> CleanupRun {
    defaults.set(7, forKey: AppStorageKey.autoDeleteStaleNodesDays.rawValue)
    defaults.removeObject(forKey: AppStorageKey.lastStaleCleanupDate.rawValue)
    let appState = try makeAppState()
    let store = appState.connectionManager.persistenceStore
    try await installDevice(store: store, appState: appState)
    try await store.saveContact(radioID: Self.radioID, from: ContactFrame(
      publicKey: Self.staleContactKey,
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Stale",
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    ))
    for channel in channels {
      try await saveChannel(store: store, index: channel.index, name: channel.name, secret: channel.secret)
    }
    let (session, transport) = try await makeSession()
    let services = ServiceContainer(session: session, dataStore: store, radioID: Self.radioID)
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: services,
      session: session
    )
    let counter = installCounter(on: appState)
    return CleanupRun(
      appState: appState,
      store: store,
      transport: transport,
      services: services,
      session: session,
      counter: counter
    )
  }

  private func installDevice(store: PersistenceStore, appState: AppState) async throws {
    try await store.saveDevice(DeviceDTO(from: Device(
      radioID: Self.radioID,
      publicKey: Self.deviceKey,
      nodeName: "Radio"
    )))
    let device = try #require(try await store.fetchDevice(radioID: Self.radioID))
    appState.connectionManager.setTestState(connectionState: .ready, connectedDevice: device)
  }

  private func saveChannel(store: PersistenceStore, index: UInt8, name: String, secret: Data) async throws {
    try await store.saveChannel(ChannelDTO(
      id: UUID(),
      radioID: Self.radioID,
      index: index,
      name: name,
      secret: secret,
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0
    ))
  }

  private func channelID(secret: Data) -> String {
    formatCompositeID(
      radioID: Self.radioID,
      kind: .channel,
      keyHex: channelSecretDigestHex(radioID: Self.radioID, secret: secret)
    )
  }

  private func offeredIDs(store: PersistenceStore) async throws -> Set<String> {
    let contacts = try await store.fetchContacts(radioID: Self.radioID)
    let channels = try await store.fetchChannels(radioID: Self.radioID)
    return offeredRecipientIDs(contacts: contacts, channels: channels)
  }

  private func attachIdleServices(appState: AppState, store: PersistenceStore) -> IdleServices {
    let session = MeshCoreSession(transport: MockTransport())
    let services = ServiceContainer(session: session, dataStore: store, radioID: Self.radioID)
    appState.connectionManager.setTestState(connectionState: .ready, services: services, session: session)
    return IdleServices(services: services, session: session)
  }

  private func makeSession() async throws -> (MeshCoreSession, MockTransport) {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let start = Task { try await session.start() }
    try await waitForCommands(1, transport: transport)
    await transport.simulateReceive(selfInfoPacket())
    try await start.value
    await transport.clearSentData()
    return (session, transport)
  }

  private func waitForCommands(_ count: Int, transport: MockTransport) async throws {
    let deadline = ContinuousClock.now + .seconds(3)
    while await transport.sentData.count < count {
      try #require(ContinuousClock.now < deadline, "Radio command did not arrive")
      try await Task.sleep(for: .milliseconds(5))
    }
  }

  private func readPrimaryChannels(
    store: PersistenceStore,
    slot0: (name: String, secret: Data),
    slot1: (name: String, secret: Data)
  ) async throws -> ChannelSyncResult {
    let (session, transport) = try await makeSession()
    defer {
      Task { await session.stop() }
    }
    let service = ChannelService(session: session, dataStore: store, rxLogService: nil)
    let sync = Task {
      try await service.syncChannels(radioID: Self.radioID, maxChannels: 2, usePipelinedRead: false)
    }
    try await waitForCommands(1, transport: transport)
    await transport.simulateReceive(channelInfoPacket(index: 0, name: slot0.name, secret: slot0.secret))
    try await waitForCommands(2, transport: transport)
    await transport.simulateReceive(channelInfoPacket(index: 1, name: slot1.name, secret: slot1.secret))
    return try await sync.value
  }

  private func selfInfoPacket() -> Data {
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 1, count: ProtocolLimits.publicKeySize))
    packet.append(Data(repeating: 0, count: 11))
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    return packet
  }

  private func channelInfoPacket(index: UInt8, name: String, secret: Data) -> Data {
    var packet = Data([ResponseCode.channelInfo.rawValue, index])
    let nameBytes = Array(name.utf8.prefix(31))
    packet.append(contentsOf: nameBytes)
    packet.append(0)
    if nameBytes.count < 31 {
      packet.append(Data(repeating: 0, count: 31 - nameBytes.count))
    }
    packet.append(secret)
    return packet
  }
}
