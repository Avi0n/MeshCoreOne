import Foundation
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("Queued reaction cold-store recovery", .timeLimit(.minutes(1)))
@MainActor
struct ChatSendQueueReactionRecoveryTests {
  private static let channelSecret = Data(repeating: 0xC3, count: 16)
  private static let senderName = "Original Reactor"
  private static let timestampAdvance: UInt32 = 10
  private static let fixtureDirectory = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()
    .appendingPathComponent("../../../../tmp", isDirectory: true)
    .standardizedFileURL

  @Test
  func `a cold store replays the original reaction intent onto its exact target`() async throws {
    let directory = Self.fixtureDirectory.appendingPathComponent("reaction-recovery-\(UUID())", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    // SwiftData can retain contexts after this scope, so store files remain until the test process exits.
    let url = directory.appendingPathComponent("messages.store")
    let originalStore = try makeStore(at: url)
    let original = try await open(store: originalStore)
    let target = MessageDTO.testChannelMessage(
      radioID: original.radioID, text: "Persisted target", direction: .incoming, senderNodeName: "Ada"
    )
    let outgoing: MessageDTO
    do {
      try await originalStore.saveMessage(target)
      await original.radio.setSendError(MeshCoreError.notConnected, for: .sendChannelMessage)
      outgoing = try #require(try await original.services.chatSendQueueService.enqueueReaction(
        to: target, emoji: "👍", senderName: Self.senderName
      ))
      try await waitUntil("the persisted reaction must park before replacing its services") {
        await original.services.chatSendQueueService.parkedDrainCountForTesting > 0
      }
      #expect(try await originalStore.fetchMessage(id: target.id)?.reactionSummary == nil)
      #expect(try await originalStore.hasPendingSend(messageID: outgoing.id))
      try await originalStore.updateMessageTimestamp(id: target.id, timestamp: target.timestamp + Self.timestampAdvance)
    } catch {
      await original.close()
      throw error
    }
    await original.close()

    let restoredStore = try makeStore(at: url)
    let restored = try await open(store: restoredStore, radioID: original.radioID)
    do {
      await restored.services.chatSendQueueService.awaitDrainCompletion()
      let saved = try #require(try await restoredStore.fetchMessage(id: outgoing.id))
      #expect(saved.status == .sent)
      #expect(saved.text == outgoing.text, "Replay must preserve the hash chosen when the reaction was admitted")
      #expect(saved.reactionTargetMessageID == target.id)
      #expect(saved.reactionSenderName == Self.senderName)
      let reactions = try await restoredStore.fetchReactions(for: target.id)
      #expect(reactions.count == 1)
      #expect(reactions.first?.senderName == Self.senderName)
      #expect(reactions.first?.rawText == outgoing.text)
      #expect(try await restoredStore.fetchMessage(id: target.id)?.reactionSummary == "👍:1")
      #expect(try await !restoredStore.hasPendingSend(messageID: outgoing.id))
      #expect(await restored.sendCount == 1)
      let duplicate = try await restored.services.chatSendQueueService.enqueueReaction(
        to: target, emoji: "👍", senderName: Self.senderName
      )
      #expect(duplicate == nil)
      #expect(await restored.sendCount == 1)
    } catch {
      await restored.close()
      throw error
    }
    await restored.close()
  }

  private func makeStore(at url: URL) throws -> PersistenceStore {
    let configuration = ModelConfiguration(schema: PersistenceStore.schema, url: url)
    return try PersistenceStore(modelContainer: ModelContainer(for: PersistenceStore.schema, configurations: [configuration]))
  }

  private func open(store: PersistenceStore, radioID: UUID = UUID()) async throws -> Connection {
    let radio = ConnectionTestRadioTransport()
    await radio.setChannel(index: 0, name: "Original", secret: Self.channelSecret)
    try await radio.connect()
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    try await store.saveDevice(.testDevice(id: radioID, radioID: radioID, publicKey: radio.publicKey))
    if try await store.fetchChannel(radioID: radioID, index: 0) == nil {
      try await store.saveChannel(.testChannel(radioID: radioID, secret: Self.channelSecret))
    }
    let services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    try await services.warmUp()
    await services.chatSendQueueService.hydrate()
    return Connection(radioID: radioID, radio: radio, session: session, services: services)
  }

  private struct Connection {
    let radioID: UUID
    let radio: ConnectionTestRadioTransport
    let session: MeshCoreSession
    let services: ServiceContainer

    var sendCount: Int {
      get async { await radio.sentData.filter { $0.first == CommandCode.sendChannelMessage.rawValue }.count }
    }

    func close() async {
      await radio.releaseAllGates()
      await services.tearDown()
      await session.stop()
    }
  }
}
