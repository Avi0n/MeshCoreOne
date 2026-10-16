import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Notification quick reply wiring")
@MainActor
struct NotificationQuickReplyWiringTests {
  private static let channelIndex: UInt8 = 1
  private static let replyText = "notification reply"

  @Test
  func `ready radio rejects a notification from another radio`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let notificationRadioID = UUID()
    let callback = try #require(harness.services.notificationService.onChannelQuickReply)

    await callback(notificationRadioID, Self.channelIndex, Self.replyText)

    #expect(try await messages(in: harness.services, radioID: notificationRadioID).isEmpty)
    #expect(try await messages(in: harness.services, radioID: harness.radioID).isEmpty)
  }

  @Test
  func `retained callback rejects a replaced service container on the same radio`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let callback = try #require(harness.services.notificationService.onChannelQuickReply)
    let replacement = ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      dataStore: harness.services.dataStore,
      radioID: harness.radioID
    )
    defer { Task { await replacement.tearDown() } }
    harness.appState.connectionManager.setTestState(services: replacement)

    await callback(harness.radioID, Self.channelIndex, Self.replyText)

    #expect(try await messages(in: harness.services, radioID: harness.radioID).isEmpty)
  }

  @Test
  func `matching radio sends the reply and clears channel unread count`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    try await verifyAcceptedReply(harness, radioID: harness.radioID)
  }

  @Test
  func `identity reconciliation uses the live radio partition without reconfiguring callbacks`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let reconciledRadioID = UUID()
    harness.appState.connectionManager.setTestState(
      connectedDevice: makeDevice(radioID: reconciledRadioID)
    )

    try await verifyAcceptedReply(harness, radioID: reconciledRadioID)

    #expect(try await messages(in: harness.services, radioID: harness.radioID).isEmpty)
  }

  private func verifyAcceptedReply(_ harness: Harness, radioID: UUID) async throws {
    let channel = ChannelDTO(
      id: UUID(), radioID: radioID, index: Self.channelIndex, name: "Ops",
      secret: Data(repeating: 0x5A, count: 16), isEnabled: true,
      lastMessageDate: nil, unreadCount: 3
    )
    try await harness.services.dataStore.saveChannel(channel)
    let startTask = Task { try await harness.session.start() }
    try await waitUntil(timeout: .seconds(2), "app start should send") {
      await harness.transport.sentData.count == 1
    }
    await harness.transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    let callback = try #require(harness.services.notificationService.onChannelQuickReply)

    let replyTask = Task { await callback(radioID, Self.channelIndex, Self.replyText) }
    try await waitUntil(timeout: .seconds(2), "channel flood scope should send") {
      await harness.transport.sentData.count == 2
    }
    let scopeCommand = await harness.transport.sentData.last
    #expect(scopeCommand == PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey()))
    await harness.transport.simulateOK()
    try await waitUntil(timeout: .seconds(2), "channel reply should send") {
      await harness.transport.sentData.count == 3
    }
    await harness.transport.simulateOK()
    await replyTask.value

    let storedMessages = try await messages(in: harness.services, radioID: radioID)
    #expect(storedMessages.count == 1)
    let stored = try #require(storedMessages.first)
    #expect(stored.text == Self.replyText)
    #expect(stored.status == .sent)
    let updatedChannel = try await harness.services.dataStore.fetchChannel(
      radioID: radioID, index: Self.channelIndex
    )
    #expect(updatedChannel?.unreadCount == 0)
    let sent = await harness.transport.sentData
    #expect(sent.last?.first == CommandCode.sendChannelMessage.rawValue)
  }

  private func messages(in services: ServiceContainer, radioID: UUID) async throws -> [MessageDTO] {
    try await services.dataStore.fetchMessages(
      radioID: radioID, channelIndex: Self.channelIndex, limit: 50, offset: 0
    )
  }

  private struct Harness {
    let appState: AppState
    let services: ServiceContainer
    let session: MeshCoreSession
    let transport: MockTransport
    let radioID: UUID
    let cleanup: () -> Void
  }

  private func makeHarness() throws -> Harness {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let suite = "test.notification-reply.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    let appState = AppState(modelContainer: container, isPlaceholder: true, defaults: defaults)
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let radioID = UUID()
    let services = ServiceContainer(
      session: session, dataStore: appState.connectionManager.persistenceStore, radioID: radioID
    )
    appState.connectionManager.setTestState(
      connectionState: .ready, services: services, session: session,
      connectedDevice: makeDevice(radioID: radioID)
    )
    appState.configureNotificationHandlers()
    return Harness(
      appState: appState, services: services, session: session,
      transport: transport, radioID: radioID,
      cleanup: {
        UserDefaults().removePersistentDomain(forName: suite)
        Task {
          await services.tearDown()
          await session.stop()
        }
      }
    )
  }

  private func makeDevice(radioID: UUID) -> DeviceDTO {
    DeviceDTO(
      id: UUID(), radioID: radioID, publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "Base Camp", firmwareVersion: 8, firmwareVersionString: "1.10",
      manufacturerName: "Test", buildDate: "", maxContacts: 100, maxChannels: 16,
      frequency: 0, bandwidth: 0, spreadingFactor: 0, codingRate: 0, txPower: 0,
      maxTxPower: 0, latitude: 0, longitude: 0, blePin: 0, clientRepeat: false,
      pathHashMode: 0, manualAddContacts: false, autoAddConfig: 0, autoAddMaxHops: 0,
      multiAcks: 0, telemetryModeBase: 0, telemetryModeLoc: 0, telemetryModeEnv: 0,
      advertLocationPolicy: 0, lastConnected: Date(), lastContactSync: 0, isActive: true,
      ocvPreset: nil, customOCVArrayString: nil, connectionMethods: []
    )
  }

  private func makeSelfInfoPacket() -> Data {
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 0x01, count: 32))
    packet.append(Data(repeating: 0, count: 12))
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    return packet
  }
}
