import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Committed channel slot cleanup", .serialized)
@MainActor
struct CommittedChannelSlotCleanupTests {
  @Test(arguments: [false, true])
  func `a committed channel reassignment retires cached state after its link retires`(replacementRadio: Bool) async throws {
    let harness = try Harness()
    do {
      try await checkCommittedCleanup(in: harness, replacementRadio: replacementRadio)
    } catch {
      await harness.close()
      throw error
    }
    await harness.close()
  }

  private func checkCommittedCleanup(in harness: Harness, replacementRadio: Bool) async throws {
    let app = harness.app
    let manager = app.connectionManager
    let store = manager.persistenceStore
    await harness.radio.setChannel(index: 0, name: "Prior", secret: harness.priorSecret)
    _ = try await manager.connect(to: harness.deviceID)
    let old = try #require(manager.currentConnection)
    harness.trackLogBuffer(old.services)
    let channel = try #require(try await store.fetchChannel(radioID: old.device.radioID, index: 0))
    let id = ChatConversationID.channel(radioID: old.device.radioID, channelIndex: 0)
    let editor = app.chatDraftEditor(for: id)
    editor.setText("private draft")
    editor.flush()
    let registry = try #require(app.ensureChatCoordinatorRegistry())
    let coordinator = registry.coordinator(for: id)
    app.navigation.navigateToChannel(with: channel, scrollToMessageID: UUID())

    let other = try await harness.prepareReplacement()
    let otherID = ChatConversationID.channel(radioID: other.radioID, channelIndex: 0)
    let otherChannel = try #require(try await store.fetchChannel(radioID: other.radioID, index: 0))
    let otherEditor = app.chatDraftEditor(for: otherID)
    otherEditor.setText("other draft")
    otherEditor.flush()
    let otherCoordinator = registry.coordinator(for: otherID)

    await harness.radio.setChannel(index: 0, name: "Replacement", secret: harness.replacementSecret)
    await store.setBatchSaveChannelsFaultInjection {
      DispatchQueue.main.sync {
        MainActor.assumeIsolated {
          harness.retireLink(replacingWith: replacementRadio ? other : nil)
          if replacementRadio {
            app.navigation.navigateToChannel(with: otherChannel, scrollToMessageID: harness.otherScrollTarget)
          }
        }
      }
    }
    let result = try await old.services.channelService.syncChannels(
      radioID: old.device.radioID, maxChannels: 1, usePipelinedRead: false
    )
    await store.setBatchSaveChannelsFaultInjection(nil)
    await harness.retirement?.value

    #expect(result.channelsSynced == 1)
    #expect(result.errors.isEmpty)
    #expect(!manager.isCurrent(old))
    #expect(try await store.fetchChannel(radioID: old.device.radioID, index: 0)?.secret == harness.replacementSecret)
    #expect(DraftStore(defaults: harness.defaults).draft(for: id) == nil)
    #expect(registry.existingCoordinator(for: id) == nil)
    #expect(app.channelSlotGenerations[id] == 1)
    let nextEditor = app.chatDraftEditor(for: id)
    #expect(nextEditor !== editor)
    #expect(nextEditor.text.isEmpty)
    #expect(registry.coordinator(for: id) !== coordinator)
    nextEditor.setText("replacement draft")
    nextEditor.flush()
    editor.setText("stale write")
    editor.flush()
    #expect(editor.text == "private draft")
    #expect(DraftStore(defaults: harness.defaults).draft(for: id) == "replacement draft")

    #expect(app.chatDraftEditor(for: otherID) === otherEditor)
    #expect(otherEditor.text == "other draft")
    #expect(DraftStore(defaults: harness.defaults).draft(for: otherID) == "other draft")
    #expect(registry.existingCoordinator(for: otherID) === otherCoordinator)
    #expect(app.channelSlotGenerations[otherID] == nil)
    if replacementRadio {
      #expect(manager.currentConnection?.device.radioID == other.radioID)
      #expect(app.navigation.chatsSelectedRoute == .channel(otherChannel))
      #expect(app.navigation.pendingScrollTarget?.messageID == harness.otherScrollTarget)
    } else {
      #expect(app.navigation.chatsSelectedRoute == nil)
      #expect(app.navigation.pendingScrollTarget == nil)
      _ = try await manager.connect(to: harness.deviceID)
      try harness.trackLogBuffer(#require(manager.services))
      #expect(manager.currentConnection?.device.radioID == old.device.radioID)
      #expect(app.channelSlotGenerations[id] == 1)
      #expect(app.chatDraftEditor(for: id) === nextEditor)
      #expect(DraftStore(defaults: harness.defaults).draft(for: id) == "replacement draft")
    }
  }

  @MainActor
  private final class Harness {
    let app: AppState
    let defaults: UserDefaults
    let radio = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize))
    let otherRadio = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize))
    let deviceID = UUID()
    let otherScrollTarget = UUID()
    let priorSecret = Data(repeating: 0x11, count: ProtocolLimits.channelSecretSize)
    let replacementSecret = Data(repeating: 0x22, count: ProtocolLimits.channelSecretSize)
    var retirement: ConnectionRetirement?
    private let suiteName = "test.committed-channel-cleanup.\(UUID())"
    private var otherSession: MeshCoreSession?
    private var otherServices: ServiceContainer?
    private let previousDebugLogBuffer = DebugLogBuffer.shared
    private var ownedDebugLogBuffers: [DebugLogBuffer] = []

    init() throws {
      defaults = try #require(UserDefaults(suiteName: suiteName))
      let container = try PersistenceStore.createContainer(inMemory: true)
      let pairing = MockAccessorySetupKitService()
      pairing.isSessionActive = false
      let otherRadio = otherRadio
      let manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(), transport: radio,
        pairing: AccessorySetupPairingService(accessorySetupKit: pairing), makeWiFiTransport: { otherRadio }
      )
      manager.otherAppWaitStrategyOverride = { _ in false }
      app = AppState(modelContainer: container, isPlaceholder: true, defaults: defaults, injectedConnectionManager: manager)
    }

    func prepareReplacement() async throws -> DeviceDTO {
      try await otherRadio.connect()
      let session = MeshCoreSession(transport: otherRadio)
      try await session.start()
      otherSession = session
      let device = DeviceDTO(from: Device(
        radioID: UUID(), publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize), nodeName: "Other"
      ))
      let services = ServiceContainer(session: session, dataStore: app.connectionManager.persistenceStore, radioID: device.radioID)
      otherServices = services
      trackLogBuffer(services)
      try await app.connectionManager.persistenceStore.saveDevice(device)
      _ = try await app.connectionManager.persistenceStore.saveChannel(
        radioID: device.radioID, from: ChannelInfo(index: 0, name: "Other", secret: priorSecret)
      )
      return device
    }

    func trackLogBuffer(_ services: ServiceContainer) {
      ownedDebugLogBuffers.append(services.debugLogBuffer)
    }

    func retireLink(replacingWith replacement: DeviceDTO?) {
      retirement = app.connectionManager.beginDisconnect(reason: .userInitiated).barrier
      guard let replacement, let services = otherServices else { return }
      app.connectionManager.setTestState(
        connectionState: .ready, services: services, session: services.session,
        connectedDevice: replacement, currentTransportType: .wifi, wifiTransport: otherRadio,
        connectionIntent: .wantsConnection()
      )
    }

    func close() async {
      await app.connectionManager.persistenceStore.setBatchSaveChannelsFaultInjection(nil)
      await radio.releaseAllGates()
      await otherRadio.releaseAllGates()
      await retirement?.value
      await app.connectionManager.disconnect(reason: .userInitiated)
      let settings = app.settingsEventsTask
      let sync = app.syncDataEventsTask
      app.tearDownAppStateSessionState()
      await settings?.value
      await sync?.value
      app.shutdown()
      await otherServices?.tearDown()
      await otherSession?.stop()
      await otherRadio.disconnect()
      defaults.removePersistentDomain(forName: suiteName)
      if let current = DebugLogBuffer.shared, ownedDebugLogBuffers.contains(where: { $0 === current }) {
        DebugLogBuffer.shared = previousDebugLogBuffer
      }
    }
  }
}
