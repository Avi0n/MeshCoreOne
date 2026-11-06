import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Channel message flood scope")
struct ChannelMessageFloodScopeTests {
  @Test
  @MainActor
  func `Notification reply restores the originating channel region before sending`() async throws {
    let radioID = UUID()
    let transport = ChannelScopeTestTransport()
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    let channel = ChannelDTO.testChannel(radioID: radioID, floodScope: .region("Alpha"))
    try await services.dataStore.saveChannel(channel)
    services.notificationActionHandler.configure(
      isConnectionReady: { $0 == radioID },
      localNodeName: { nil }
    )
    try await session.setFloodScope(.region("Beta"))

    await services.notificationActionHandler.handleChannelQuickReply(
      radioID: radioID,
      channelIndex: channel.index,
      text: "region-test"
    )

    let scopePackets = await transport.scopePackets
    #expect(scopePackets == [scopePacket("Beta"), scopePacket("Alpha")])
    #expect(await transport.scopesAtChannelSend == [scopePacket("Alpha")])
  }

  @Test(arguments: SendPath.allCases)
  @MainActor
  func `Channel send paths apply the current stored region before sending`(path: SendPath) async throws {
    let radioID = UUID()
    let transport = ChannelScopeTestTransport()
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    let channel = ChannelDTO.testChannel(radioID: radioID, floodScope: .region("Alpha"))
    try await services.dataStore.saveChannel(channel)
    let pending = try await services.messageService.createPendingChannelMessage(
      text: "region-test", channelIndex: channel.index, radioID: radioID
    )
    try await services.dataStore.setChannelFloodScope(channel.id, floodScope: .region("Gamma"))
    try await session.setFloodScope(.region("Beta"))

    do {
      switch path {
      case .inline:
        _ = try await services.messageService.sendChannelMessage(
          text: "region-test", channelIndex: channel.index, radioID: radioID
        )
      case .pending:
        try await services.messageService.sendPendingChannelMessage(messageID: pending.id)
      case .resend:
        try await services.messageService.resendChannelMessage(messageID: pending.id)
      }
      Issue.record("The transport should fail after recording the channel send")
    } catch {}

    #expect(await transport.scopesAtChannelSend == [scopePacket("Gamma")])
  }

  @Test
  @MainActor
  func `Inherited scope clears the override without writing the cached default`() async throws {
    let radioID = UUID()
    let transport = ChannelScopeTestTransport()
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    let channel = ChannelDTO.testChannel(radioID: radioID, floodScope: .inherit)
    try await services.dataStore.saveChannel(channel)
    try await services.dataStore.saveDevice(DeviceDTO.testDevice(radioID: radioID).copy {
      $0.defaultFloodScopeName = "Alpha"
    })
    try await session.setFloodScope(.region("Beta"))

    _ = try? await services.messageService.sendChannelMessage(
      text: "region-test", channelIndex: channel.index, radioID: radioID
    )

    let baseline = PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())
    #expect(await transport.scopesAtChannelSend == [baseline])
    #expect(await transport.scopePackets == [scopePacket("Beta"), baseline])
  }

  @Test(arguments: [UInt8(7), UInt8(8), UInt8(11), UInt8(12)])
  @MainActor
  func `Explicit Unscoped uses only supported firmware commands`(firmwareVersion: UInt8) async throws {
    let radioID = UUID()
    let transport = ChannelScopeTestTransport()
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    let channel = ChannelDTO.testChannel(radioID: radioID, floodScope: .allRegions)
    try await services.dataStore.saveChannel(channel)
    try await services.dataStore.saveDevice(DeviceDTO.testDevice(radioID: radioID, firmwareVersion: firmwareVersion).copy {
      $0.defaultFloodScopeName = "Alpha"
    })
    try await session.setFloodScope(.region("Beta"))

    _ = try? await services.messageService.sendChannelMessage(
      text: "region-test", channelIndex: channel.index, radioID: radioID
    )

    if firmwareVersion < 8 {
      #expect(await transport.scopePackets == [scopePacket("Beta")])
      #expect(await transport.scopesAtChannelSend == [scopePacket("Beta")])
      return
    }
    let expected = firmwareVersion >= 12
      ? PacketBuilder.setFloodScopeUnscoped()
      : PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())
    #expect(await transport.scopesAtChannelSend == [expected])
  }

  @Test
  @MainActor
  func `Ordinary sends on firmware without scope support omit scope commands`() async throws {
    let radioID = UUID()
    let transport = ChannelScopeTestTransport()
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    let channel = ChannelDTO.testChannel(radioID: radioID)
    try await services.dataStore.saveChannel(channel)
    try await services.dataStore.saveDevice(DeviceDTO.testDevice(radioID: radioID, firmwareVersion: 7))

    _ = try? await services.messageService.sendChannelMessage(
      text: "region-test", channelIndex: channel.index, radioID: radioID
    )

    #expect(await transport.scopePackets.isEmpty)
    #expect(await transport.scopesAtChannelSend == [Data()])
  }

  @Test
  @MainActor
  func `Rejected scope prevents transmission and marks the outgoing message failed`() async throws {
    let radioID = UUID()
    let transport = ChannelScopeTestTransport(scopeError: ErrorCode.unsupportedCommand.rawValue)
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    let channel = ChannelDTO.testChannel(radioID: radioID, floodScope: .region("Alpha"))
    try await services.dataStore.saveChannel(channel)

    do {
      _ = try await services.messageService.sendChannelMessage(
        text: "region-test", channelIndex: channel.index, radioID: radioID
      )
      Issue.record("A rejected scope command must fail the send")
    } catch let MessageServiceError.sessionError(.deviceError(code)) {
      #expect(code == ErrorCode.unsupportedCommand.rawValue)
    }

    #expect(await transport.scopePackets == [scopePacket("Alpha")])
    #expect(await transport.scopesAtChannelSend.isEmpty)
    let messages = try await services.dataStore.fetchMessages(
      radioID: radioID, channelIndex: channel.index, limit: 10, offset: 0
    )
    #expect(messages.count == 1)
    #expect(messages.first?.status == .failed)
  }

  @Test
  @MainActor
  func `Pool retries reapply the current stored region before every attempt`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let channel = ChannelDTO.testChannel(radioID: radioID, floodScope: .region("Alpha"))
    try await store.saveChannel(channel)
    let transport = ChannelScopeTestTransport {
      try await store.setChannelFloodScope(channel.id, floodScope: .region("Gamma"))
    }
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }
    let service = MessageService(
      session: session, dataStore: store, contactService: nil,
      config: MessageServiceConfig(poolBackoff: PoolBackoffConfig(attemptCap: 1, baseDelay: 0))
    )

    _ = try? await service.sendChannelMessage(text: "region-test", channelIndex: channel.index, radioID: radioID)

    #expect(await transport.scopesAtChannelSend == [scopePacket("Alpha"), scopePacket("Gamma")])
  }

  @Test
  @MainActor
  func `Explicit region send restores the baseline before the next contact command`() async throws {
    let radioID = UUID()
    let transport = ChannelScopeTestTransport(channelOutcome: .accept)
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    let secret = Data(repeating: 0x5A, count: 16)
    let channel = ChannelDTO.testChannel(radioID: radioID, index: 3, secret: secret, floodScope: .region("Selected"))
    try await services.dataStore.saveChannel(channel)
    try await services.dataStore.saveDevice(DeviceDTO.testDevice(radioID: radioID, firmwareVersion: 8).copy {
      $0.defaultFloodScopeName = "Persisted"
    })

    _ = try await services.messageService.sendChannelMessage(
      text: "region-test", channelIndex: channel.index, radioID: radioID
    )
    _ = try await session.sendMessage(to: Data(repeating: 0x22, count: 32), text: "after")

    let selected = scopePacket("Selected")
    let baseline = PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())
    let frames = await transport.sentFrames
    let channelFrame = try #require(frames.first { $0.first == CommandCode.sendChannelMessage.rawValue })
    #expect(channelFrame.dropFirst(2).first == channel.index)
    #expect(frames.compactMap(\.first) == [
      CommandCode.appStart, .setFloodScope, .sendChannelMessage, .setFloodScope, .sendMessage
    ].map(\.rawValue))
    #expect(await transport.scopePackets == [selected, baseline])
    let stored = try #require(try await services.dataStore.fetchMessages(
      radioID: radioID, channelIndex: channel.index, limit: 10, offset: 0
    ).first)
    #expect(stored.status == .sent)
  }

  enum SendPath: CaseIterable {
    case inline, pending, resend
  }

  private func scopePacket(_ region: String) -> Data {
    PacketBuilder.setFloodScope(FloodScope.region(region).scopeKey())
  }
}
