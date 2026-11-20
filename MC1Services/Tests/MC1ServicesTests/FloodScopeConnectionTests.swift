import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Flood scope connection baseline")
@MainActor
struct FloodScopeConnectionTests {
  @Test(arguments: [TransportType.bluetooth, TransportType.wifi])
  func `services stay unavailable until the baseline reset is acknowledged`(type: TransportType) async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    let radio = radio(type, harness)
    do {
      await radio.holdReplies(for: .setFloodScope)
      let connect = Task { try await harness.connect(type) }
      try await waitUntil("baseline reset should be held") {
        await radio.heldReplyCount(for: .setFloodScope) == 1
      }
      #expect(harness.manager.services == nil)
      #expect(harness.manager.connectionState != .ready)
      let heldFrames = await radio.sentData
      #expect(heldFrames.contains { Self.contactCommand($0) } == false)
      await radio.releaseReplies(for: .setFloodScope)
      _ = try await connect.value
      #expect(harness.manager.connectionState == .ready)
      let scopeFrames = await radio.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }
      #expect(scopeFrames == [PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())])
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  @Test
  func `firmware before scope support omits the baseline reset`() async throws {
    let harness = try ConnectionManagerRadioTestHarness(firmwareVersion: 7)
    do {
      _ = try await harness.connect(.bluetooth)
      #expect(harness.manager.connectionState == .ready)
      #expect(await harness.ble.sentData.contains { $0.first == CommandCode.setFloodScope.rawValue } == false)
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  @Test(arguments: [TransportType.bluetooth, TransportType.wifi])
  func `baseline reset rejection fails the candidate`(type: TransportType) async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    let radio = radio(type, harness)
    await radio.rejectNext(.setFloodScope, count: ConnectionManager.defaultConnectAttempts)
    do {
      _ = try await harness.connect(type)
      Issue.record("Baseline reset rejection left the candidate connected")
    } catch {
      #expect(harness.manager.services == nil)
    }
    #expect(harness.manager.connectionState != .ready)
    await harness.close()
  }

  @Test
  func `baseline reset timeout fails the candidate`() async throws {
    let clock = TestClock()
    let harness = try ConnectionManagerRadioTestHarness(sessionClock: clock)
    await harness.ble.holdReplies(for: .setFloodScope)
    do {
      _ = try await harness.resultAdvancingClock { try await harness.connect(.bluetooth) }
      Issue.record("Baseline reset timeout left the candidate connected")
    } catch {
      #expect(harness.manager.services == nil)
    }
    await harness.close()
  }

  @Test
  func `revoking the attempt drops a late baseline acknowledgement`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      await harness.ble.holdReplies(for: .setFloodScope)
      let connect = Task { try await harness.connect(.bluetooth) }
      try await waitUntil("baseline reset should be held") {
        await harness.ble.heldReplyCount(for: .setFloodScope) == 1
      }
      _ = await harness.manager.disconnect(reason: .userInitiated)
      await harness.ble.releaseReplies(for: .setFloodScope)
      _ = await connect.result
      #expect(harness.manager.services == nil)
      #expect(harness.manager.connectionState == .disconnected)
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  @Test(arguments: QueueRecoveryCase.allCases)
  func `accepted channel queue sends survive cleanup failure`(recovery: QueueRecoveryCase) async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await harness.connect(.bluetooth)
      let radioID = try #require(harness.manager.connectedDevice?.radioID)
      let secret = Data(repeating: 0x5A, count: 16)
      let channel = ChannelDTO.testChannel(
        radioID: radioID, index: 0, secret: secret, floodScope: .region("Selected")
      )
      try await harness.manager.persistenceStore.saveChannel(channel)
      await harness.ble.setChannel(index: channel.index, name: channel.name, secret: secret)
      let services = try #require(harness.manager.services)
      let message = try await services.messageService.createPendingChannelMessage(
        text: "queued", channelIndex: channel.index, radioID: radioID
      )
      await harness.ble.enqueueResponsePackets(for: .setFloodScope, packets: [Data([ResponseCode.ok.rawValue])])
      await harness.ble.enqueueResponsePackets(
        for: .setFloodScope, packets: [Data([ResponseCode.error.rawValue, 1])]
      )
      try await services.chatSendQueueService.enqueueChannel(ChannelMessageEnvelope(
        messageID: message.id, channelIndex: channel.index, isResend: recovery == .resend,
        messageText: message.text, messageTimestamp: message.timestamp, localNodeName: nil
      ))
      try await waitUntil("the accepted send should commit and leave no pending row") {
        let stored = try await harness.manager.persistenceStore.fetchMessage(id: message.id)
        let pending = try await harness.manager.persistenceStore.fetchPendingSends(radioID: radioID)
        return stored?.status == .sent && pending.isEmpty
          && harness.manager.connectionState == .ready && harness.manager.services === services
      }
      let session = try #require(harness.manager.session)
      _ = try await session.getTime()
      let channelSends = await harness.ble.sentData.filter { $0.first == CommandCode.sendChannelMessage.rawValue }
      #expect(channelSends.count == 1)
      #expect(channelSends.first?.dropFirst(2).first == channel.index)
      #expect(await harness.ble.connectCount == 1)
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  enum QueueRecoveryCase: CaseIterable {
    case initialSend
    case resend
  }

  private func radio(_ type: TransportType, _ harness: ConnectionManagerRadioTestHarness) -> ConnectionTestRadioTransport {
    type == .bluetooth ? harness.ble : harness.wifi
  }

  private static func contactCommand(_ frame: Data) -> Bool {
    guard let code = frame.first else { return false }
    return code == CommandCode.sendChannelMessage.rawValue
      || code == CommandCode.sendMessage.rawValue
      || code == CommandCode.sendLogin.rawValue
      || code == CommandCode.getSelfTelemetry.rawValue
  }
}
