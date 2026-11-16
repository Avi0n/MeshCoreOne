import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Queued reaction lifecycle", .timeLimit(.minutes(1)))
@MainActor
struct ChatSendQueueReactionTests {
  private static let channelSecret = Data(repeating: 0xA1, count: 16)
  private static let replacementSecret = Data(repeating: 0xB2, count: 16)
  private static let senderName = "Local Radio"
  private static let acknowledgementTimeoutMilliseconds: UInt32 = 30000

  @Test
  func `channel acceptance persists its target badge without an open conversation`() async throws {
    let (env, target) = try await makeChannelEnvironment()
    do {
      let queue = try #require(env.manager.currentConnection?.services.chatSendQueueService)
      let outgoing = try #require(try await queue.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName))
      await queue.awaitDrainCompletion()

      #expect(try await env.manager.persistenceStore.fetchMessage(id: outgoing.id)?.status == .sent)
      #expect(try await env.manager.persistenceStore.fetchMessage(id: target.id)?.reactionSummary == "👍:1")
      #expect(try await env.manager.persistenceStore.fetchReactions(for: target.id).count == 1)
      #expect(try await !env.manager.persistenceStore.hasPendingSend(messageID: outgoing.id))
      #expect(await sendCount(env, command: .sendChannelMessage) == 1)
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `an accepted channel reaction retries a failed local save without another radio command`() async throws {
    let (env, target) = try await makeChannelEnvironment()
    do {
      let store = env.manager.persistenceStore
      let queue = try #require(env.manager.currentConnection?.services.chatSendQueueService)
      await store.setUpdateMessageStatusFaultInjection { _, status in
        if status == .sent { throw CocoaError(.fileWriteUnknown) }
      }
      let outgoing = try #require(try await queue.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName))
      try await waitUntil("the accepted reaction should park until its local save can commit") {
        await queue.parkedDrainCountForTesting > 0
      }

      #expect(await sendCount(env, command: .sendChannelMessage) == 1)
      #expect(try await store.fetchMessage(id: outgoing.id)?.status == .pending)
      #expect(try await store.hasPendingSend(messageID: outgoing.id))
      #expect(try await store.fetchReactions(for: target.id).isEmpty)
      #expect(try await store.fetchMessage(id: target.id)?.reactionSummary == nil)

      await store.setUpdateMessageStatusFaultInjection(nil)
      queue.transportDidOpen()
      try await waitUntil("the accepted reaction should finish its local bookkeeping") {
        try await store.fetchMessage(id: target.id)?.reactionSummary == "👍:1"
      }
      await queue.awaitDrainCompletion()

      #expect(await sendCount(env, command: .sendChannelMessage) == 1)
      #expect(try await store.fetchMessage(id: outgoing.id)?.status == .sent)
      #expect(try await store.fetchReactions(for: target.id).count == 1)
      #expect(try await !store.hasPendingSend(messageID: outgoing.id))
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `concurrent taps admit one outstanding reaction and send one packet`() async throws {
    let (env, target) = try await makeChannelEnvironment()
    do {
      let queue = try #require(env.manager.currentConnection?.services.chatSendQueueService)
      await env.ble.holdSubmissions(for: .sendChannelMessage)
      async let first = queue.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName)
      async let second = queue.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName)
      let admitted = try await [first, second].compactMap(\.self)
      #expect(admitted.count == 1)
      #expect(try await env.manager.persistenceStore.fetchPendingSends(radioID: target.radioID).count == 1)
      #expect(try await env.manager.persistenceStore.fetchReactions(for: target.id).isEmpty)

      await env.ble.releaseSubmissions(for: .sendChannelMessage)
      await queue.awaitDrainCompletion()
      let duplicate = try await queue.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName)

      #expect(duplicate == nil)
      #expect(await sendCount(env, command: .sendChannelMessage) == 1)
      #expect(try await env.manager.persistenceStore.fetchMessage(id: target.id)?.reactionSummary == "👍:1")
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `a parked channel reaction resumes after ConnectionManager rebuilds services`() async throws {
    let (env, target) = try await makeChannelEnvironment()
    do {
      let original = try #require(env.manager.currentConnection?.services.chatSendQueueService)
      await env.ble.setSendError(MeshCoreError.notConnected, for: .sendChannelMessage)
      let outgoing = try #require(try await original.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName))
      try await waitUntil("the reaction should park after its transport failure") {
        await original.parkedDrainCountForTesting > 0
      }
      #expect(try await env.manager.persistenceStore.fetchMessage(id: outgoing.id)?.status == .pending)
      #expect(try await env.manager.persistenceStore.hasPendingSend(messageID: outgoing.id))
      #expect(try await env.manager.persistenceStore.fetchReactions(for: target.id).isEmpty)

      _ = await env.manager.disconnect(reason: .userInitiated)
      await env.manager.retirementTask?.value
      await env.ble.setSendError(nil, for: .sendChannelMessage)
      _ = try await env.connect(.bluetooth)
      let replacement = try #require(env.manager.currentConnection?.services.chatSendQueueService)
      await replacement.awaitDrainCompletion()

      #expect(replacement !== original)
      #expect(try await env.manager.persistenceStore.fetchMessage(id: outgoing.id)?.status == .sent)
      #expect(try await env.manager.persistenceStore.fetchMessage(id: target.id)?.reactionSummary == "👍:1")
      #expect(try await !env.manager.persistenceStore.hasPendingSend(messageID: outgoing.id))
      #expect(await sendCount(env, command: .sendChannelMessage) == 1)
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `an external slot change still sends the reaction`() async throws {
    let (env, target) = try await makeChannelEnvironment()
    do {
      await env.ble.setChannel(index: 0, name: "Replacement", secret: Self.replacementSecret)
      let queue = try #require(env.manager.currentConnection?.services.chatSendQueueService)
      let outgoing = try #require(try await queue.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName))
      await queue.awaitDrainCompletion()

      #expect(try await env.manager.persistenceStore.fetchMessage(id: outgoing.id)?.status == .sent)
      #expect(try await env.manager.persistenceStore.fetchMessage(id: target.id)?.reactionSummary == "👍:1")
      #expect(try await !env.manager.persistenceStore.hasPendingSend(messageID: outgoing.id))
      #expect(await sendCount(env, command: .sendChannelMessage) == 1)
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `a removed target cannot admit reaction work`() async throws {
    let (env, target) = try await makeChannelEnvironment()
    do {
      let queue = try #require(env.manager.currentConnection?.services.chatSendQueueService)
      try await env.manager.persistenceStore.deleteMessage(id: target.id)
      let outgoing = try await queue.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName)
      await queue.awaitDrainCompletion()

      #expect(outgoing == nil)
      #expect(try await env.manager.persistenceStore.fetchPendingSends(radioID: target.radioID).isEmpty)
      #expect(await sendCount(env, command: .sendChannelMessage) == 0)
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `a DM reaction keeps one submission and the badge through acceptance or a fast ACK`(fastACK: Bool) async throws {
    let (env, target) = try await makeDMEnvironment()
    do {
      await env.ble.holdSubmissions(for: .sendMessage)
      let queue = try #require(env.manager.currentConnection?.services.chatSendQueueService)
      let outgoing = try #require(try await queue.enqueueReaction(to: target, emoji: "👍", senderName: Self.senderName))
      try await waitUntil("the reaction should reach command submission") {
        await env.ble.submissionIsHeld(for: .sendMessage)
      }
      let submitted = try #require(try await env.manager.persistenceStore.fetchMessage(id: outgoing.id))
      let acknowledgement = AckCodeBuilder.expectedAck(
        timestamp: submitted.timestamp, attempt: 0, text: submitted.text, senderPublicKey: env.ble.publicKey
      )
      var responses = [messageSentPacket(acknowledgement: acknowledgement)]
      if fastACK { responses.append(Data([ResponseCode.ack.rawValue]) + acknowledgement) }
      await env.ble.setResponsePackets(for: .sendMessage, packets: responses)
      await env.ble.releaseSubmissions(for: .sendMessage)
      try await waitUntil("radio acceptance should persist the reaction badge") {
        try await env.manager.persistenceStore.fetchMessage(id: target.id)?.reactionSummary == "👍:1"
      }
      await queue.awaitDrainCompletion()

      #expect(try await env.manager.persistenceStore.fetchMessage(id: outgoing.id)?.status == (fastACK ? .delivered : .sent))
      #expect(try await env.manager.persistenceStore.fetchReactions(for: target.id).count == 1)
      #expect(await sendCount(env, command: .sendMessage) == 1)
      let owners = try await env.manager.persistenceStore.fetchPendingSends(radioID: target.radioID)
      if fastACK {
        #expect(owners.isEmpty)
      } else {
        #expect(owners.count == 1)
        #expect(owners.first?.ackDeadline != nil)
        #expect(owners.first?.isSingleAttempt == true)
      }
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  private func makeChannelEnvironment() async throws -> (ConnectionManagerRadioTestHarness, MessageDTO) {
    let env = try ConnectionManagerRadioTestHarness()
    configureNotifications(env)
    await env.ble.setChannel(index: 0, name: "Original", secret: Self.channelSecret)
    do {
      _ = try await env.connect(.bluetooth)
      let connection = try #require(env.manager.currentConnection)
      let target = MessageDTO.testChannelMessage(
        radioID: connection.device.radioID, text: "Channel reaction target", direction: .incoming, senderNodeName: "Ada"
      )
      try await env.manager.persistenceStore.saveMessage(target)
      return (env, target)
    } catch {
      await env.close()
      throw error
    }
  }

  private func makeDMEnvironment() async throws -> (ConnectionManagerRadioTestHarness, MessageDTO) {
    let env = try ConnectionManagerRadioTestHarness()
    configureNotifications(env)
    do {
      _ = try await env.connect(.bluetooth)
      let connection = try #require(env.manager.currentConnection)
      let contact = ContactDTO.testContact(radioID: connection.device.radioID)
      try await env.manager.persistenceStore.saveContact(contact)
      await env.ble.setContact(contact.toContactFrame().toMeshContact())
      let target = MessageDTO.testDirectMessage(
        radioID: connection.device.radioID, contactID: contact.id, text: "DM reaction target", direction: .incoming, status: .delivered
      )
      try await env.manager.persistenceStore.saveMessage(target)
      return (env, target)
    } catch {
      await env.close()
      throw error
    }
  }

  private func configureNotifications(_ env: ConnectionManagerRadioTestHarness) {
    env.manager.testNotificationSystemOperations = .init(
      setBadgeCount: { _ in }, deliveredRequests: { [] }, removeDeliveredNotifications: { _ in }
    )
  }

  private func sendCount(_ env: ConnectionManagerRadioTestHarness, command: CommandCode) async -> Int {
    await env.ble.sentData.filter { $0.first == command.rawValue }.count
  }

  private func messageSentPacket(acknowledgement: Data) -> Data {
    var packet = Data([ResponseCode.messageSent.rawValue, 0]) + acknowledgement
    withUnsafeBytes(of: Self.acknowledgementTimeoutMilliseconds.littleEndian) { packet.append(contentsOf: $0) }
    return packet
  }
}
