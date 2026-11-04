import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Hydrated channel occupancy")
@MainActor
struct ChatSendQueueHydrationOccupancyTests {
  enum Scenario {
    case reassignedBeforeSync, reassignedInBackground, reassignedAfterRecentClean, reassignedAfterRecentAttempt
    case unchangedWithPartialSync

    var isReassigned: Bool {
      self != .unchangedWithPartialSync
    }
  }

  struct HydrationSample: Sendable {
    var type: TransportType
    var scenario: Scenario
  }

  /// Bluetooth covers each sync gate. One WiFi case covers the ESP32 pipelined channel read.
  nonisolated static let hydrationSamples: [HydrationSample] = [
    HydrationSample(type: .bluetooth, scenario: .reassignedBeforeSync),
    HydrationSample(type: .bluetooth, scenario: .reassignedInBackground),
    HydrationSample(type: .bluetooth, scenario: .reassignedAfterRecentClean),
    HydrationSample(type: .bluetooth, scenario: .reassignedAfterRecentAttempt),
    HydrationSample(type: .bluetooth, scenario: .unchangedWithPartialSync),
    HydrationSample(type: .wifi, scenario: .unchangedWithPartialSync),
  ]

  @Test(.timeLimit(.minutes(1)), arguments: hydrationSamples)
  func `hydration validates its slot independently of full sync`(_ sample: HydrationSample) async throws {
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
      case .reassignedAfterRecentClean:
        env.manager.lastCleanChannelSync = (saved.radioID, Date())
      case .reassignedAfterRecentAttempt:
        env.manager.lastAttemptedChannelSync = (saved.radioID, Date())
      case .unchangedWithPartialSync:
        await radio.enqueueResponsePackets(for: .getChannel, packets: [HydratedChannelSendHarness.channelPacket(secret: actualSecret)])
        await radio.enqueueResponsePackets(for: .getChannel, packets: [HydratedChannelSendHarness.channelPacket(secret: actualSecret)])
        await radio.setResponsePackets(for: .getChannel, packets: [Data([ResponseCode.error.rawValue, ErrorCode.fileIOError.rawValue])])
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
      if scenario.isReassigned {
        #expect(sends.isEmpty, "A persisted message for secret A must never be submitted to slot B")
        #expect(try await !env.manager.persistenceStore.hasPendingSend(messageID: message.id))
      } else {
        #expect(sends.count == 1)
        let readIndex = try #require(frames.firstIndex { $0.first == CommandCode.getChannel.rawValue })
        let sendIndex = try #require(frames.firstIndex { $0.first == CommandCode.sendChannelMessage.rawValue })
        #expect(readIndex < sendIndex, "Even an unchanged slot needs a current occupancy read before replay")
        #expect(try await env.manager.persistenceStore.fetchMessage(id: message.id)?.status == .sent)
      }
      if scenario == .reassignedInBackground || scenario == .reassignedAfterRecentClean
        || scenario == .reassignedAfterRecentAttempt {
        #expect(env.manager.installedLink?.usableSyncResult?.channels == .skipped)
        #expect(frames.filter { $0.first == CommandCode.getChannel.rawValue }.count == 1,
                "Skipped global sync must still validate and reject a reassigned slot")
      } else if scenario == .unchangedWithPartialSync {
        #expect(env.manager.installedLink?.usableSyncResult?.channels == .partial)
      }
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true], [false, true])
  func `queued sends revalidate a slot after hydration rejected its prior occupant`(isResend: Bool, slotMatches: Bool) async throws {
    let harness = try await HydratedChannelSendHarness.make()
    do {
      await harness.radio.setChannel(index: 0, name: "Replacement", secret: HydratedChannelSendHarness.secretB)
      await harness.queue.hydrate()
      await harness.queue.awaitDrainCompletion()
      #expect(await harness.sendFrames.isEmpty)
      #expect(try await harness.store.fetchMessage(id: harness.message.id)?.status == .failed)
      #expect(try await !harness.store.hasPendingSend(messageID: harness.message.id))
      let retained = try #require(try await harness.store.fetchChannel(radioID: harness.radioID, index: 0))
      #expect(retained.secret == HydratedChannelSendHarness.secretA,
              "A skipped global sync leaves the original Channel available for Retry and Send Again")

      if slotMatches {
        await harness.radio.setChannel(index: 0, name: "Original", secret: HydratedChannelSendHarness.secretA)
      }
      let message: MessageDTO
      if isResend {
        message = harness.message
      } else {
        let service = MessageService(session: harness.session, dataStore: harness.store, contactService: nil)
        message = try await service.createPendingChannelMessage(
          text: "New message for channel A", channelIndex: 0, radioID: harness.radioID
        )
      }
      let envelope = ChannelMessageEnvelope(
        messageID: message.id, channelIndex: 0, isResend: isResend,
        messageText: message.text, messageTimestamp: message.timestamp, localNodeName: nil
      )
      try await harness.queue.enqueueChannel(envelope)
      await harness.queue.awaitDrainCompletion()

      #expect(await harness.sendFrames.count == (slotMatches ? 1 : 0))
      #expect(try await harness.store.fetchMessage(id: message.id)?.status == (slotMatches ? .sent : .failed))
      #expect(try await !harness.store.hasPendingSend(messageID: message.id))
    } catch {
      await harness.close()
      throw error
    }
    await harness.close()
  }

  @Test
  func `unreadable occupancy preserves the pending owner and wire attempt count until retry`() async throws {
    let clock = TestClock()
    let harness = try await HydratedChannelSendHarness.make(clock: clock)
    do {
      await harness.radio.setResponsePackets(for: .getChannel, packets: [Data([ResponseCode.error.rawValue, ErrorCode.fileIOError.rawValue])])
      try await resultAdvancingMatchedSessionClock(clock, observation: {
        await sessionClockObservation(radio: harness.radio, session: harness.session)
      }) {
        await harness.queue.hydrate()
        try await waitUntil("validation must either park or finish") {
          if await harness.queue.parkedDrainCountForTesting > 0 { return true }
          return await (try? harness.store.hasPendingSend(messageID: harness.message.id)) == false
        }
      }
      #expect(await harness.queue.parkedDrainCountForTesting > 0)
      let pending = try #require(try await harness.store.fetchPendingSendsForMessage(messageID: harness.message.id).first)
      #expect(pending.attemptCount == 0, "An occupancy read failure is not a wire-send attempt")
      #expect(try await harness.store.fetchMessage(id: harness.message.id)?.status == .pending)
      #expect(await harness.sendFrames.isEmpty)

      await harness.radio.setResponsePackets(for: .getChannel, packets: [HydratedChannelSendHarness.channelPacket(secret: HydratedChannelSendHarness.secretA)])
      harness.queue.transportDidOpen()
      try await waitUntil("a readable matching slot must release the parked send") {
        await (try? harness.store.fetchMessage(id: harness.message.id))?.status == .sent
      }
      await harness.queue.awaitDrainCompletion()
      #expect(await harness.sendFrames.count == 1)
      #expect(try await !harness.store.hasPendingSend(messageID: harness.message.id))
      #expect(try await harness.store.fetchMessage(id: harness.message.id)?.status == .sent)
    } catch {
      await harness.close()
      throw error
    }
    await harness.close()
  }

  @Test
  func `retirement during occupancy read preserves unsent work`() async throws {
    let harness = try await HydratedChannelSendHarness.make()
    do {
      await harness.radio.holdReplies(for: .getChannel)
      await harness.queue.hydrate()
      try await harness.waitForValidation()
      let retirement = Task { await harness.queue.shutdown() }
      try await waitUntil("retirement must revoke queue admission") { harness.queue.isRetiredForTesting }
      await harness.radio.releaseReplies(for: .getChannel)
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
  func `occupancy cleanup during validation prevents a stale envelope from sending`() async throws {
    let harness = try await HydratedChannelSendHarness.make()
    do {
      await harness.radio.holdReplies(for: .getChannel)
      await harness.queue.hydrate()
      try await harness.waitForValidation()
      try await harness.store.deleteMessagesForChannel(radioID: harness.radioID, channelIndex: 0)
      await harness.radio.releaseReplies(for: .getChannel)
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
