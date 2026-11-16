import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Hydrated channel sends")
@MainActor
struct ChatSendQueueHydrationOccupancyTests {
  enum Scenario {
    case reassignedBeforeSync, reassignedInBackground, unchangedWithPartialSync

    var isReassigned: Bool {
      self != .unchangedWithPartialSync
    }
  }

  struct HydrationSample: Sendable {
    var type: TransportType
    var scenario: Scenario
  }

  /// Bluetooth covers a send before sync, a skipped sync, and a partial sync.
  /// One WiFi case covers the ESP32 pipelined channel read.
  nonisolated static let hydrationSamples: [HydrationSample] = [
    HydrationSample(type: .bluetooth, scenario: .reassignedBeforeSync),
    HydrationSample(type: .bluetooth, scenario: .reassignedInBackground),
    HydrationSample(type: .bluetooth, scenario: .unchangedWithPartialSync),
    HydrationSample(type: .wifi, scenario: .unchangedWithPartialSync),
  ]

  @Test(.timeLimit(.minutes(1)), arguments: hydrationSamples)
  func `hydration sends without reading the slot`(_ sample: HydrationSample) async throws {
    let type = sample.type
    let scenario = sample.scenario
    let env = try ConnectionManagerRadioTestHarness()
    let radio = type == .bluetooth ? env.ble : env.wifi
    do {
      let saved = DeviceDTO.testDevice(id: env.deviceID, publicKey: radio.publicKey)
      try await env.manager.persistenceStore.saveDevice(saved)
      let message = try await HydratedChannelSendHarness.seed(store: env.manager.persistenceStore, radioID: saved.radioID)
      let actualSecret = scenario.isReassigned ? HydratedChannelSendHarness.secretB : HydratedChannelSendHarness.secretA
      await radio.setChannel(index: 0, name: "Current", secret: actualSecret)
      let model = Data("T-Deck".utf8)
      let capabilities = Data([ResponseCode.deviceInfo.rawValue, 14, 50, 1])
        + Data(repeating: 0, count: 16) + model + Data(repeating: 0, count: 40 - model.count + 20)
      await radio.setResponsePackets(for: .deviceQuery, packets: [capabilities])
      switch scenario {
      case .reassignedInBackground:
        env.manager.appStateProvider = MockAppStateProvider(isInForeground: false)
      case .unchangedWithPartialSync:
        await radio.setResponsePackets(
          for: .getChannel,
          packets: [Data([ResponseCode.error.rawValue, ErrorCode.fileIOError.rawValue])]
        )
      case .reassignedBeforeSync:
        break
      }
      env.manager.onConnectionReady = { snapshot in
        await snapshot.services.chatSendQueueService.awaitDrainCompletion()
      }

      if scenario == .unchangedWithPartialSync {
        let clock = TestClock()
        env.manager.sessionClockForTesting = clock
        _ = try await resultAdvancingMatchedSessionClock(clock, observation: {
          await sessionClockObservation(radio: radio, session: env.manager.session)
        }) {
          if type == .bluetooth {
            return try await env.manager.connect(to: env.deviceID, forceFullSync: false, forceReconnect: true)
          }
          return try await env.manager.connectViaWiFi(host: "radio.test", port: 5123)
        }
      } else if type == .bluetooth {
        _ = try await env.resultAdvancingClock {
          try await env.manager.connect(to: env.deviceID, forceFullSync: false, forceReconnect: true)
        }
      } else {
        _ = try await env.resultAdvancingClock {
          try await env.manager.connectViaWiFi(host: "radio.test", port: 5123)
        }
      }

      let current = try #require(env.manager.currentConnection)
      await current.services.chatSendQueueService.awaitDrainCompletion()
      let frames = await radio.sentData
      let sends = frames.filter { $0.first == CommandCode.sendChannelMessage.rawValue }
      #expect(sends.count == 1)
      if scenario == .reassignedBeforeSync {
        // Sync adopts the radio's new secret and deletes the old slot's history, including this send.
        #expect(try await env.manager.persistenceStore.fetchMessage(id: message.id) == nil)
      } else {
        #expect(try await env.manager.persistenceStore.fetchMessage(id: message.id)?.status == .sent)
      }
      #expect(try await !env.manager.persistenceStore.hasPendingSend(messageID: message.id))
      if scenario == .reassignedInBackground {
        #expect(env.manager.installedLink?.usableSyncResult?.channels == .skipped)
        #expect(frames.filter { $0.first == CommandCode.getChannel.rawValue }.isEmpty)
      } else if scenario == .unchangedWithPartialSync {
        #expect(env.manager.installedLink?.usableSyncResult?.channels == .partial)
      }
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `a local slot rewrite drops a queued send without a radio read`() async throws {
    let harness = try await HydratedChannelSendHarness.make()
    do {
      try await harness.channelService.setChannelWithSecret(
        radioID: harness.radioID, index: 0, name: "Replacement", secret: HydratedChannelSendHarness.secretB
      )
      await harness.queue.hydrate()
      await harness.queue.awaitDrainCompletion()

      let frames = await harness.radio.sentData
      #expect(frames.filter { $0.first == CommandCode.sendChannelMessage.rawValue }.isEmpty)
      #expect(frames.filter { $0.first == CommandCode.getChannel.rawValue }.isEmpty)
      #expect(try await harness.store.fetchMessage(id: harness.message.id) == nil)
      #expect(try await !harness.store.hasPendingSend(messageID: harness.message.id))

      let service = MessageService(session: harness.session, dataStore: harness.store, contactService: nil)
      let message = try await service.createPendingChannelMessage(
        text: "New message for the replacement", channelIndex: 0, radioID: harness.radioID
      )
      let envelope = ChannelMessageEnvelope(
        messageID: message.id, channelIndex: 0, isResend: false,
        messageText: message.text, messageTimestamp: message.timestamp, localNodeName: nil
      )
      try await harness.queue.enqueueChannel(envelope)
      await harness.queue.awaitDrainCompletion()
      #expect(await harness.sendFrames.count == 1)
      #expect(try await harness.store.fetchMessage(id: message.id)?.status == .sent)
      #expect(try await !harness.store.hasPendingSend(messageID: message.id))
    } catch {
      await harness.close()
      throw error
    }
    await harness.close()
  }

  @Test
  func `retirement while a slot write owns the channel preserves the unsent message`() async throws {
    let harness = try await HydratedChannelSendHarness.make()
    do {
      await harness.radio.holdReplies(for: .setChannel)
      let write = Task {
        try await harness.channelService.setChannelWithSecret(
          radioID: harness.radioID, index: 0, name: "Original", secret: HydratedChannelSendHarness.secretA
        )
      }
      try await waitUntil("the slot write must reach the radio") {
        await harness.radio.sentData.contains { $0.first == CommandCode.setChannel.rawValue }
      }
      await harness.queue.hydrate()
      try await waitUntil("the drain must wait for the slot write") {
        await harness.channelService.channelOperationWaiterCountForTesting() >= 1
      }
      let retirement = Task { await harness.queue.shutdown() }
      try await waitUntil("retirement must revoke queue admission") { harness.queue.isRetiredForTesting }
      await harness.radio.releaseReplies(for: .setChannel)
      try await write.value
      await retirement.value
      #expect(await harness.sendFrames.isEmpty)
      let pending = try #require(try await harness.store.fetchPendingSendsForMessage(messageID: harness.message.id).first)
      #expect(pending.attemptCount == 0)
      #expect(try await harness.store.fetchMessage(id: harness.message.id)?.status == .pending)
    } catch {
      await harness.close()
      throw error
    }
    await harness.close()
  }

  @Test
  func `deleting the pending row while the drain waits prevents the send`() async throws {
    let harness = try await HydratedChannelSendHarness.make()
    do {
      await harness.radio.holdReplies(for: .setChannel)
      let write = Task {
        try await harness.channelService.setChannelWithSecret(
          radioID: harness.radioID, index: 0, name: "Original", secret: HydratedChannelSendHarness.secretA
        )
      }
      try await waitUntil("the slot write must reach the radio") {
        await harness.radio.sentData.contains { $0.first == CommandCode.setChannel.rawValue }
      }
      await harness.queue.hydrate()
      try await waitUntil("the drain must wait for the slot write") {
        await harness.channelService.channelOperationWaiterCountForTesting() >= 1
      }
      try await harness.store.deleteMessagesForChannel(radioID: harness.radioID, channelIndex: 0)
      await harness.radio.releaseReplies(for: .setChannel)
      try await write.value
      await harness.queue.awaitDrainCompletion()
      #expect(await harness.sendFrames.isEmpty)
      #expect(try await !harness.store.hasPendingSend(messageID: harness.message.id))
      #expect(try await harness.store.fetchMessage(id: harness.message.id) == nil)
    } catch {
      await harness.close()
      throw error
    }
    await harness.close()
  }
}
