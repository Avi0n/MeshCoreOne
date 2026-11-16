import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Lifecycle diagnostic export", .serialized, .timeLimit(.minutes(1)))
@MainActor
struct LogExportLifecycleTests {
  @Test
  func `real disconnect export retains the retired Link and request without payload fields`() async throws {
    let run = try Harness()
    do {
      await run.manager.wireTransportHandlers()
      _ = try await run.manager.connect(to: run.deviceID, forceFullSync: true, forceReconnect: true)
      let link = try #require(run.manager.installedLink)
      let request = try #require(run.manager.desiredRequest)
      let attempt = try #require(request.attempt)
      let origin = try #require(request.physicalOrigin)
      run.trackBuffer(link.services.debugLogBuffer)
      #expect(run.manager.connectionState == .ready)
      #expect(try await link.resource.session.getBattery().level == 3700)
      let appWorkers = run.trackAppWorkers()
      run.reclaimLogs()

      _ = await run.manager.disconnect(reason: .userInitiated)
      for worker in appWorkers {
        await worker.value
      }

      let currentRequestID = try #require(run.manager.connectionRequestID)
      let fields = [
        "request=\(request.id)", "attempt=\(ObjectIdentifier(attempt))", "link=\(link.generation)",
        "binding=\(ObjectIdentifier(link.resource.binding))", "session=\(ObjectIdentifier(link.resource.session))",
        "physicalGeneration=\(origin.generation)", "device=\(link.device.id.uuidString.prefix(8))"
      ]
      let records = [
        ["event=disconnectAdmitted,", "request=\(currentRequestID)", "currentLink=\(link.generation)",
         "currentBinding=\(ObjectIdentifier(link.resource.binding))", "reason=\(DisconnectReason.userInitiated.rawValue)"],
        ["event=dispose,", "currentRequest=\(currentRequestID)"] + fields,
        ["event=state,", "request=\(currentRequestID)", "currentRequest=\(currentRequestID)",
         "state=disconnected", "link=none"]
      ]
      let exported = try await run.exportedLogs(until: records, containing: ["Intent: userDisconnected"])
      Attachment.record(exported, named: "connection-lifecycle-export.txt")
      let lines = exported.split(separator: "\n").map(String.init).filter { $0.contains("[ConnectionLifecycle]") }
      for fields in records {
        #expect(lines.contains { line in fields.allSatisfy(line.contains) },
                "Export must retain each exact owner and terminal record")
      }
      #expect(exported.contains("Intent: userDisconnected"))
      #expect(run.manager.currentConnection == nil)
      #expect(await run.radio.disconnectCount == 1)
      for line in lines {
        for forbidden in ["payload=", "password=", "pin=", "secret=", "publicKey=", "host=", "ssid="] {
          #expect(!line.localizedCaseInsensitiveContains(forbidden))
        }
      }
    } catch { await run.close(); throw error }
    await run.close()
  }

  @Test
  func `real same-radio recovery export retains the request and replacement stack`() async throws {
    let run = try Harness()
    do {
      await run.manager.wireTransportHandlers()
      _ = try await run.manager.connect(to: run.deviceID, forceFullSync: true, forceReconnect: true)
      let first = try #require(run.manager.installedLink)
      let request = try #require(run.manager.desiredRequest)
      let firstAttempt = try #require(request.attempt)
      run.trackBuffer(first.services.debugLogBuffer)
      let firstWorkers = run.trackAppWorkers()
      run.reclaimLogs()
      #expect(try await first.resource.session.getBattery().level == 3700)

      await run.radio.simulateAutoReconnecting(deviceID: run.deviceID)
      try await waitUntil("loss handling must join retirement") { run.handledEvents.count == 1 }
      guard case .autoReconnecting = run.handledEvents.first else {
        Issue.record("The first completed lifecycle event must be auto-reconnecting")
        await run.close()
        return
      }
      let cycle = try #require(run.manager.reconnectionCoordinator.currentCycle)
      let lossOrigin = try #require(request.physicalOrigin)
      #expect(run.manager.connectionState == .connecting)
      #expect(run.manager.installedLink == nil)
      #expect(run.manager.desiredRequest === request)
      #expect(!first.resource.binding.isActive)
      await #expect(throws: CancellationError.self) { try await first.resource.session.start() }
      #expect(run.manager.connectionTeardownCount == 0)
      #expect(await run.radio.disconnectCount == 0)

      await run.radio.simulateReconnected()
      try await waitUntil("reconnection handling must join the rebuilt stack") { run.handledEvents.count == 2 }
      guard case .reconnected = run.handledEvents.last else {
        Issue.record("The second completed lifecycle event must be reconnected")
        await run.close()
        return
      }
      let next = try #require(run.manager.installedLink)
      let nextAttempt = try #require(request.attempt)
      run.trackBuffer(next.services.debugLogBuffer)
      _ = run.trackAppWorkers()
      for worker in firstWorkers {
        await worker.value
      }
      run.reclaimLogs()
      #expect(run.manager.connectionState == .ready)
      #expect(run.manager.desiredRequest === request)
      #expect(run.manager.reconnectionCoordinator.currentCycle == nil)
      #expect(next.generation != first.generation)
      #expect(next.resource !== first.resource)
      #expect(next.resource.binding !== first.resource.binding)
      #expect(next.resource.session !== first.resource.session)
      #expect(next.resource.binding.isActive)
      #expect(next.device.id == first.device.id)
      #expect(next.device.radioID == first.device.radioID)
      #expect(try await next.resource.session.getBattery().level == 3700)
      #expect(await run.radio.connectCount == 1)
      #expect(await run.radio.disconnectCount == 0)

      let nextOwner = "request=\(request.id), attempt=\(ObjectIdentifier(nextAttempt)), link=\(next.generation)"
      let common = ["request=\(request.id)", "reconnectCycle=\(cycle.id)", "reconnectGeneration=\(cycle.generation)"]
      let retired = common + [
        "attempt=\(ObjectIdentifier(firstAttempt))", "link=\(first.generation)",
        "binding=\(ObjectIdentifier(first.resource.binding))", "session=\(ObjectIdentifier(first.resource.session))",
        "physicalGeneration=\(lossOrigin.generation)", "reason=preservePhysical"
      ]
      let successor = common + [
        "binding=\(ObjectIdentifier(next.resource.binding))", "session=\(ObjectIdentifier(next.resource.session))"
      ]
      let records = [
        ["event=dispose,"] + retired,
        ["event=retired,"] + retired,
        ["event=attemptStarted,", "attempt=\(ObjectIdentifier(nextAttempt))",
         "binding=\(ObjectIdentifier(next.resource.binding))"] + common,
        ["event=published, \(nextOwner)", "state=syncing"] + successor,
        ["event=state, \(nextOwner)", "state=ready"] + successor
      ]
      let diagnostic = try #require(run.manager.lastDisconnectDiagnostic)
      #expect(diagnostic.contains("source=ble.autoReconnecting, device=\(run.deviceID.uuidString.prefix(8)),"))
      let diagnosticHeader = "Last Disconnect Diagnostic: \(diagnostic)"
      let exported = try await run.exportedLogs(until: records, containing: [diagnosticHeader, "State: ready"])
      Attachment.record(exported, named: "recovery-lifecycle-export.txt")
      let lines = exported.split(separator: "\n").map(String.init).filter { $0.contains("[ConnectionLifecycle]") }
      for fields in records {
        #expect(lines.contains { line in fields.allSatisfy(line.contains) },
                "Export must retain each exact request, Cycle and stack boundary")
      }
      #expect(exported.contains(diagnosticHeader))
      #expect(exported.contains("State: ready"))
    } catch { await run.close(); throw error }
    await run.close()
  }

  @MainActor
  private final class Harness {
    let app: AppState
    let manager: ConnectionManager
    let radio = ConnectionTestRadioTransport()
    let deviceID = UUID()
    private let defaults: UserDefaults
    private let suite = "test.lifecycle-export.\(UUID().uuidString)"
    private let previousBuffer = DebugLogBuffer.shared
    private let previousCollectionValue = UserDefaults.standard.object(forKey: AppStorageKey.collectDebugLogs.rawValue)
    private let bootstrap: DebugLogBuffer
    private var ownedBuffers: [DebugLogBuffer]
    private var appWorkers: [Task<Void, Never>] = []
    var handledEvents: [BLELifecycleEvent] = []
    private static let settleTimeout: Duration = .seconds(2)
    private static let pollInterval: Duration = .milliseconds(10)

    init() throws {
      defaults = try #require(UserDefaults(suiteName: suite))
      let container = try PersistenceStore.createContainer(inMemory: true)
      let pairing = MockAccessorySetupKitService()
      pairing.isSessionActive = false
      manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(), transport: radio,
        pairing: AccessorySetupPairingService(accessorySetupKit: pairing)
      )
      app = AppState(modelContainer: container, isPlaceholder: true, defaults: defaults, injectedConnectionManager: manager)
      bootstrap = DebugLogBuffer(dataStore: manager.persistenceStore)
      ownedBuffers = [bootstrap]
      DebugLogBuffer.shared = bootstrap
      manager.testBLELifecycleEventHandled = { [weak self] event in self?.handledEvents.append(event) }
      UserDefaults.standard.set(true, forKey: AppStorageKey.collectDebugLogs.rawValue)
    }

    func trackAppWorkers() -> [Task<Void, Never>] {
      let workers = [app.settingsEventsTask, app.syncDataEventsTask,
                     app.advertisementEventsTask, app.rxLogEventsTask].compactMap(\.self)
      appWorkers.append(contentsOf: workers)
      return workers
    }

    func trackBuffer(_ buffer: DebugLogBuffer) {
      ownedBuffers.append(buffer)
    }

    func reclaimLogs() {
      DebugLogBuffer.shared = bootstrap
    }

    func exportedLogs(until records: [[String]], containing markers: [String]) async throws -> String {
      let deadline = ContinuousClock.now + Self.settleTimeout
      var exported = ""
      repeat {
        for buffer in ownedBuffers {
          await buffer.flush()
        }
        exported = await LogExportService.generateExport(appState: app, persistenceStore: manager.persistenceStore)
        let lines = exported.split(separator: "\n").map(String.init).filter { $0.contains("[ConnectionLifecycle]") }
        let hasRecords = records.allSatisfy { fields in
          lines.contains { line in fields.allSatisfy(line.contains) }
        }
        if hasRecords, markers.allSatisfy(exported.contains) { break }
        try await Task.sleep(for: Self.pollInterval)
      } while ContinuousClock.now < deadline
      return exported
    }

    func close() async {
      manager.testBLELifecycleEventHandled = nil
      await radio.releaseAllGates()
      _ = trackAppWorkers()
      _ = await manager.disconnect(reason: .userInitiated)
      await manager.retirementTask?.value
      await manager.metadataCommitTask?.value
      app.tearDownAppStateSessionState()
      for worker in appWorkers {
        await worker.value
      }
      app.shutdown()
      for buffer in ownedBuffers {
        await buffer.shutdown()
      }
      defaults.removePersistentDomain(forName: suite)
      if let current = DebugLogBuffer.shared, ownedBuffers.contains(where: { $0 === current }) {
        DebugLogBuffer.shared = previousBuffer
      }
      if let previousCollectionValue {
        UserDefaults.standard.set(previousCollectionValue, forKey: AppStorageKey.collectDebugLogs.rawValue)
      } else {
        UserDefaults.standard.removeObject(forKey: AppStorageKey.collectDebugLogs.rawValue)
      }
    }
  }
}
