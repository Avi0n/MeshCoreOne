import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("AppState Lifecycle Transition Tests")
@MainActor
struct LifecycleTransitionTests {
  @Test
  func `BLE foreground waits for queued background transition`() async {
    let appState = AppState()
    let recorder = TransitionRecorder()

    appState.setBLELifecycleOverridesForTesting(
      enterBackground: {
        await recorder.record("background-start")
        try? await Task.sleep(for: .milliseconds(150))
        await recorder.record("background-end")
      },
      becomeActive: {
        await recorder.record("foreground-start")
        await recorder.record("foreground-end")
      }
    )

    appState.handleEnterBackground()
    await appState.handleReturnToForeground()

    let events = await recorder.events
    #expect(events == [
      "background-start",
      "background-end",
      "foreground-start",
      "foreground-end"
    ])
  }

  @Test
  func `Explicit disconnect runs the per-session teardown the loss path performs`() async {
    let appState = AppState()
    appState.settingsEventsTask = Task {
      while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(60))
      }
    }
    appState.navigation.nodesShowingDiscovery = true

    await appState.disconnect()

    #expect(appState.settingsEventsTask == nil)
    #expect(appState.navigation.nodesShowingDiscovery == false)
  }

  @Test
  func `rapid background-active bounces keep BLE transitions ordered`() async {
    let appState = AppState()
    let recorder = TransitionRecorder()

    appState.setBLELifecycleOverridesForTesting(
      enterBackground: {
        try? await Task.sleep(for: .milliseconds(20))
        await recorder.record("background")
      },
      becomeActive: {
        await recorder.record("foreground")
      }
    )

    for _ in 0..<5 {
      appState.handleEnterBackground()
      await appState.handleReturnToForeground()
    }

    let events = await recorder.events
    #expect(events.count == 10)

    for index in stride(from: 0, to: events.count, by: 2) {
      #expect(events[index] == "background")
      #expect(events[index + 1] == "foreground")
    }
  }

  @Test
  func `toolbar resume probe names chrome without contact identity`() {
    let appState = AppState()
    let secretName = "PII-SHOULD-NOT-LEAK"
    appState.navigation.selectedTab = AppTab.map.rawValue
    appState.navigation.chatsSelectedRoute = .direct(Self.makeContact(name: secretName))
    appState.navigation.selectedTool = .lineOfSight
    appState.navigation.selectedSetting = .maps
    appState.navigation.nodesShowingDiscovery = true

    let message = appState.toolbarResumeProbeMessage(
      source: "returnToForeground",
      scene: "background->active"
    )

    #expect(message.contains("[DBG-toolbar-resume]"))
    #expect(message.contains("source=returnToForeground"))
    #expect(message.contains("scene=background->active"))
    #expect(message.contains("tab=map"))
    #expect(message.contains("chatRoute=direct"))
    #expect(message.contains("nodesDetail=discovery"))
    #expect(message.contains("tool=lineOfSight"))
    #expect(message.contains("setting=maps"))
    #expect(message.contains("connection=disconnected"))
    #expect(message.contains("hasDevice=false"))
    #expect(!message.contains(secretName))
  }

  @Test
  func `disconnect resets trace discovery and remote admin`() async {
    let appState = AppState()
    defer { appState.shutdown() }
    let trace = TracePathViewModel()
    trace.isRunning = true
    appState.tracePathViewModel = trace
    let discovery = NodeDiscoveryViewModel()
    discovery.beginScanForTesting(deadline: Date().addingTimeInterval(15))
    appState.nodeDiscoveryViewModel = discovery
    let session = Self.makeRepeaterSession()
    let settings = appState.remoteAdminWorkspaces.repeaterSettings(for: session)
    settings.advertIntervalMinutes = 20

    await appState.disconnect()

    #expect(trace.isRunning == false)
    #expect(discovery.isScanning == false)
    #expect(appState.remoteAdminWorkspaces.repeaterSettings(for: session) !== settings)
  }

  @Test
  func `device id change resets tool workspaces and the same id does not`() async throws {
    let appState = AppState()
    defer { appState.shutdown() }
    let deviceA = Self.makeDevice(id: UUID())
    let servicesA = try await ServiceContainer.forTesting(session: MeshCoreSession(transport: MockTransport()))
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: servicesA,
      connectedDevice: deviceA
    )
    await appState.wireServicesIfConnected()

    let trace = TracePathViewModel()
    trace.isRunning = true
    appState.tracePathViewModel = trace
    let discovery = NodeDiscoveryViewModel()
    discovery.beginScanForTesting(deadline: Date().addingTimeInterval(15))
    appState.nodeDiscoveryViewModel = discovery
    let session = Self.makeRepeaterSession()
    let settings = appState.remoteAdminWorkspaces.repeaterSettings(for: session)
    settings.advertIntervalMinutes = 20

    await appState.wireServicesIfConnected()
    #expect(trace.isRunning)
    #expect(discovery.isScanning)
    #expect(appState.remoteAdminWorkspaces.repeaterSettings(for: session) === settings)

    let deviceB = Self.makeDevice(id: UUID())
    let servicesB = try await ServiceContainer.forTesting(session: MeshCoreSession(transport: MockTransport()))
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: servicesB,
      connectedDevice: deviceB
    )
    await appState.wireServicesIfConnected()

    #expect(trace.isRunning == false)
    #expect(discovery.isScanning == false)
    #expect(appState.remoteAdminWorkspaces.repeaterSettings(for: session) !== settings)
  }

  @Test
  func `foreground return expires a passed trace and scan`() async throws {
    let appState = AppState()
    defer { appState.shutdown() }
    appState.setBLELifecycleOverridesForTesting(
      enterBackground: {},
      becomeActive: {}
    )

    let trace = TracePathViewModel()
    trace.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { nil },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))
    trace.sendTraceForTesting = { _, _, _ in
      MessageSentInfo(route: 1, expectedAck: Data(), suggestedTimeoutMs: 60000)
    }
    trace.addNode(Self.makeContact(name: "Relay"))
    trace.startTrace()
    try await waitUntil(timeout: .seconds(1), "timeout wait should be armed") {
      trace.hasActiveTimeoutTaskForTesting && trace.pendingTagForTesting != nil
    }
    trace.setTraceDeadlineForTesting(.distantPast)
    appState.tracePathViewModel = trace

    let discovery = NodeDiscoveryViewModel()
    discovery.beginScanForTesting(deadline: .distantPast)
    appState.nodeDiscoveryViewModel = discovery

    await appState.handleReturnToForeground()

    #expect(trace.isRunning == false)
    #expect(discovery.isScanning == false)
  }

  @Test
  func `foreground return keeps a future or nil-deadline scan scanning`() async {
    let appState = AppState()
    defer { appState.shutdown() }
    appState.setBLELifecycleOverridesForTesting(
      enterBackground: {},
      becomeActive: {}
    )

    let future = NodeDiscoveryViewModel()
    future.beginScanForTesting(deadline: Date().addingTimeInterval(15))
    appState.nodeDiscoveryViewModel = future
    await appState.handleReturnToForeground()
    #expect(future.isScanning)

    let openEnded = NodeDiscoveryViewModel()
    openEnded.beginScanForTesting(deadline: nil)
    appState.nodeDiscoveryViewModel = openEnded
    await appState.handleReturnToForeground()
    #expect(openEnded.isScanning)
  }

  @Test
  func `running trace subscribes when a new container is wired`() async throws {
    let appState = AppState()
    defer { appState.shutdown() }
    let device = Self.makeDevice(id: UUID())
    let servicesA = try await ServiceContainer.forTesting(session: MeshCoreSession(transport: MockTransport()))
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: servicesA,
      connectedDevice: device
    )
    await appState.wireServicesIfConnected()

    let trace = TracePathViewModel()
    trace.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { [weak appState] in appState?.services?.dataStore },
      session: { [weak appState] in appState?.services?.session },
      advertisementService: { [weak appState] in appState?.services?.advertisementService },
      connectedDevice: { [weak appState] in appState?.connectedDevice },
      bestAvailableLocation: { nil }
    ))
    trace.isRunning = true
    appState.tracePathViewModel = trace
    let subscribed = trace.subscribeCountForTesting

    let servicesB = try await ServiceContainer.forTesting(session: MeshCoreSession(transport: MockTransport()))
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: servicesB,
      connectedDevice: device
    )
    await appState.wireServicesIfConnected()

    #expect(trace.isRunning)
    #expect(trace.subscribeCountForTesting == subscribed + 1)

    await appState.wireServicesIfConnected()
    #expect(trace.subscribeCountForTesting == subscribed + 1)

    trace.isRunning = false
    let servicesC = try await ServiceContainer.forTesting(session: MeshCoreSession(transport: MockTransport()))
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: servicesC,
      connectedDevice: device
    )
    await appState.wireServicesIfConnected()

    #expect(trace.subscribeCountForTesting == subscribed + 1)
  }

  private static func makeRepeaterSession() -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Node",
      role: .repeater,
      isConnected: true,
      permissionLevel: .admin
    )
  }

  private static func makeDevice(id: UUID) -> DeviceDTO {
    DeviceDTO(
      id: id,
      radioID: UUID(),
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "Radio",
      firmwareVersion: 8,
      firmwareVersionString: "1.10",
      manufacturerName: "Test",
      buildDate: "",
      maxContacts: 100,
      maxChannels: 16,
      frequency: 0,
      bandwidth: 0,
      spreadingFactor: 0,
      codingRate: 0,
      txPower: 0,
      maxTxPower: 0,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      clientRepeat: false,
      pathHashMode: 0,
      manualAddContacts: false,
      autoAddConfig: 0,
      autoAddMaxHops: 0,
      multiAcks: 0,
      telemetryModeBase: 0,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: []
    )
  }

  private static func makeContact(name: String) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xAA, count: 32),
      name: name,
      typeRawValue: 0x01,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }
}

private actor TransitionRecorder {
  private(set) var events: [String] = []

  func record(_ event: String) {
    events.append(event)
  }
}
