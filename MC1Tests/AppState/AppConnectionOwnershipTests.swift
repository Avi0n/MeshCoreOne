import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("App connection callback ownership")
@MainActor
struct AppConnectionOwnershipTests {
  @Test(arguments: [false, true])
  func `a retired Settings consumer cannot update a replacement radio`(sameRadio: Bool) async throws {
    try await withHarness { harness in
      let old = try harness.install()
      await harness.appState.wireSettingsEventStream(snapshot: old)
      let oldTask = try #require(harness.appState.settingsEventsTask)
      let oldEvents = try #require(await old.services.settingsService.eventContinuation)
      let next = try harness.install(replacing: sameRadio ? old.device : nil)

      oldEvents.yield(.clientRepeatUpdated(true))
      oldEvents.finish()
      await oldTask.value
      #expect(harness.manager.isCurrent(next))
      #expect(harness.manager.connectedDevice?.clientRepeat == false)

      await harness.appState.wireSettingsEventStream(snapshot: next)
      let nextTask = try #require(harness.appState.settingsEventsTask)
      let nextEvents = try #require(await next.services.settingsService.eventContinuation)
      nextEvents.yield(.clientRepeatUpdated(true))
      nextEvents.finish()
      await nextTask.value
      #expect(harness.manager.connectedDevice?.clientRepeat == true)
    }
  }

  @Test
  func `old sync and storage events cannot mutate replacement presentation`() async throws {
    try await withHarness { harness in
      let old = try harness.install()
      await harness.appState.connectionUI.wireCallbacks(snapshot: old, connectionManager: harness.manager)
      harness.appState.wireSyncDataEvents(snapshot: old)
      let oldDataTask = try #require(harness.appState.syncDataEventsTask)
      let next = try harness.install(replacing: old.device)
      await harness.appState.connectionUI.wireCallbacks(snapshot: next, connectionManager: harness.manager)
      harness.appState.connectionUI.isNodeStorageFull = true
      let contactsVersion = harness.appState.contactsVersion

      await old.services.syncCoordinator.beginResyncActivity()
      old.services.syncCoordinator.notifyContactsChanged()
      old.services.syncCoordinator.finishDataEvents()
      old.services.advertisementService.eventBroadcaster.yield(.nodeStorageFullChanged(isFull: false))
      old.services.advertisementService.finishEvents()
      await oldDataTask.value
      #expect(harness.appState.connectionUI.syncActivityCount == 0)
      #expect(harness.appState.contactsVersion == contactsVersion)
      #expect(harness.appState.connectionUI.isNodeStorageFull)

      await next.services.syncCoordinator.beginResyncActivity()
      #expect(harness.appState.connectionUI.syncActivityCount == 1)
      await next.services.syncCoordinator.endResyncActivity(succeeded: false)
      #expect(harness.appState.connectionUI.syncActivityCount == 0)
    }
  }

  @Test
  func `a held battery result cannot publish through a replacement callback`() async throws {
    try await withHarness { harness in
      let oldRadio = ConnectionTestRadioTransport()
      harness.radios.append(oldRadio)
      let oldSession = MeshCoreSession(transport: oldRadio)
      try await oldSession.start()
      let old = try harness.install(session: oldSession)
      await oldRadio.holdReplies(for: .getBattery)
      let read = harness.run {
        await harness.appState.batteryMonitor.fetchDeviceBattery(services: old.services, device: old.device)
      }
      try await waitUntil("old battery read should be submitted") {
        await oldRadio.sentData.contains { $0.first == CommandCode.getBattery.rawValue }
      }

      let nextRadio = ConnectionTestRadioTransport()
      harness.radios.append(nextRadio)
      let nextSession = MeshCoreSession(transport: nextRadio)
      try await nextSession.start()
      let next = try harness.install(session: nextSession)
      let monitor = harness.appState.batteryMonitor
      monitor.deviceBattery = BatteryInfo(level: 4200)
      var publications = 0
      monitor.onBatteryChanged = { _ in publications += 1 }
      await oldRadio.releaseReplies(for: .getBattery)
      await read.value
      #expect(monitor.deviceBattery?.level == 4200)
      #expect(publications == 0)

      await monitor.fetchDeviceBattery(services: next.services, device: next.device)
      #expect(monitor.deviceBattery?.level == 3700)
      #expect(publications == 1)
    }
  }

  @Test
  func `a replacement connects while retired app wiring is held`() async throws {
    try await withHarness { harness in
      let gate = harness.makeGate()
      var heldGeneration: UUID?
      var oldWiringResumed = false
      harness.appState.connectionWiringAfterCallbacks = { snapshot in
        guard heldGeneration == nil else { return }
        heldGeneration = snapshot.generation
        await gate.wait()
        oldWiringResumed = true
      }
      harness.run {
        do {
          _ = try await harness.manager.connect(to: UUID())
        } catch {
          Issue.record(error)
        }
      }
      try await waitUntil("the old app wiring should reach the held await") { heldGeneration != nil }
      let old = try #require(harness.manager.currentConnection)
      let versionBeforeReplacement = harness.appState.servicesVersion
      var replacement: ConnectionSnapshot?
      harness.run {
        do {
          let result = try await harness.appState.connectViaWiFi(host: "next.local", port: ConnectionMethod.defaultWiFiPort)
          if case let .operational(snapshot) = result { replacement = snapshot }
        } catch { Issue.record(error) }
      }
      try await waitUntil("replacement should become usable before old native wiring resumes") { replacement != nil }
      let next = try #require(replacement)
      #expect(!oldWiringResumed)
      #expect(harness.manager.isCurrent(next))
      #expect(!harness.manager.isCurrent(old))
      #expect(harness.appState.servicesVersion == versionBeforeReplacement + 1)

      gate.release()
      try await waitUntil("the retired app wiring should finish after its hold is released") { oldWiringResumed }
      #expect(harness.manager.isCurrent(next))
      #expect(harness.appState.servicesVersion == versionBeforeReplacement + 1)
      #expect(harness.appState.syncCoordinator === next.services.syncCoordinator)
    }
  }

  @Test
  func `cancelling a wiring waiter leaves another caller joined to the shared job`() async throws {
    try await withHarness { harness in
      let gate = harness.makeGate()
      var operationStarted = false
      var operationFinished = false
      var cancelledWaiterFinished = false
      var joinedWaiterFinished = false
      let job = AppConnectionWiringJob(id: UUID(), generation: UUID()) {
        operationStarted = true
        await gate.wait()
        operationFinished = true
      }
      let cancelled = harness.run {
        await job.wait()
        cancelledWaiterFinished = true
      }
      let joined = harness.run {
        await job.wait()
        joinedWaiterFinished = true
      }
      try await waitUntil("both callers should join the shared wiring operation") {
        operationStarted && job.waitingCallerCount == 2
      }
      cancelled.cancel()
      try await waitUntil("the cancelled waiter should be released") { cancelledWaiterFinished }
      #expect(!operationFinished)
      #expect(!joinedWaiterFinished)
      gate.release()
      await joined.value
      #expect(operationFinished)
      #expect(joinedWaiterFinished)
    }
  }

  @Test
  func `finishing an older presentation job cannot clear a newer busy state`() {
    let state = ConnectionUIState()
    let old = state.beginConnectionAction()
    let next = state.beginConnectionAction()
    #expect(!state.finishConnectionAction(old))
    #expect(state.isBusy)
    #expect(state.isCurrentConnectionAction(next))
    #expect(state.finishConnectionAction(next))
    #expect(!state.isBusy)
  }

  @Test
  func `the same radio can open a new failure episode`() {
    let state = ConnectionUIState()
    let deviceID = UUID()
    state.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.authenticationFailed))
    let first = state.pairingFailureEpisodeID
    state.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.authenticationFailed))
    #expect(state.failedPairingDeviceID == deviceID)
    #expect(state.pairingFailureEpisodeID != first)
  }

  @MainActor
  private final class Harness {
    let appState: AppState
    let suiteName: String
    var services: [ServiceContainer] = []
    var radios: [ConnectionTestRadioTransport] = []
    private var jobs: [Task<Void, Never>] = []
    private var gates: [Gate] = []
    var manager: ConnectionManager {
      appState.connectionManager
    }

    init() throws {
      suiteName = "test.app-connection-ownership.\(UUID())"
      let defaults = try #require(UserDefaults(suiteName: suiteName))
      let container = try PersistenceStore.createContainer(inMemory: true)
      let bluetooth = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize))
      let wifi = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize))
      let pairing = MockAccessorySetupKitService()
      pairing.isSessionActive = false
      let manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(), transport: bluetooth,
        pairing: AccessorySetupPairingService(accessorySetupKit: pairing), makeWiFiTransport: { wifi }
      )
      radios = [bluetooth, wifi]
      appState = AppState(modelContainer: container, isPlaceholder: true, defaults: defaults, injectedConnectionManager: manager)
    }

    func install(replacing old: DeviceDTO? = nil, session: MeshCoreSession? = nil) throws -> ConnectionSnapshot {
      let device = old ?? DeviceDTO(from: Device(
        radioID: UUID(), publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize), nodeName: "Fixture"
      ))
      let session = session ?? MeshCoreSession(transport: MockTransport())
      let container = ServiceContainer(session: session, dataStore: manager.persistenceStore, radioID: device.radioID)
      services.append(container)
      manager.setTestState(
        connectionState: .connected,
        services: container,
        session: session,
        connectedDevice: device,
        currentTransportType: .bluetooth,
        connectionIntent: .wantsConnection()
      )
      return try #require(manager.currentConnection)
    }

    @discardableResult
    func run(_ work: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
      let task = Task { await work() }
      jobs.append(task)
      return task
    }

    func makeGate() -> Gate {
      let gate = Gate()
      gates.append(gate)
      return gate
    }

    func cleanup() async {
      gates.forEach { $0.release() }
      for radio in radios {
        await radio.releaseAllGates()
      }
      jobs.forEach { $0.cancel() }
      for job in jobs {
        await job.value
      }
      await manager.disconnect(reason: .userInitiated)
      let events = appState.settingsEventsTask
      let sync = appState.syncDataEventsTask
      appState.tearDownAppStateSessionState()
      await events?.value
      await sync?.value
      appState.shutdown()
      for container in services {
        await container.tearDown()
        await container.session.stop()
      }
      UserDefaults().removePersistentDomain(forName: suiteName)
    }

    @MainActor
    final class Gate {
      private var continuation: CheckedContinuation<Void, Never>?
      private var released = false

      func wait() async {
        guard !released else { return }
        await withCheckedContinuation { continuation = $0 }
      }

      func release() {
        released = true
        continuation?.resume()
        continuation = nil
      }
    }
  }

  private func withHarness(_ body: @MainActor (Harness) async throws -> Void) async throws {
    let harness = try Harness()
    do {
      try await body(harness)
    } catch {
      await harness.cleanup()
      throw error
    }
    await harness.cleanup()
  }
}
