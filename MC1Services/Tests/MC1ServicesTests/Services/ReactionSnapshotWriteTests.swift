import Foundation
@testable import MC1Services
import Testing

@Suite("Reaction snapshot writes", .timeLimit(.minutes(1)))
struct ReactionSnapshotWriteTests {
  @Test
  func `a missing parent is not admitted`() async throws {
    let (store, service) = try makeStack()
    let radioID = UUID()
    let reaction = makeReaction(messageID: UUID(), radioID: radioID, emoji: "👍", sender: "Ada")
    let result = await service.persistReactionAndUpdateSummary(reaction, using: store)
    #expect(result == nil)
    #expect(try await store.fetchReactions(for: reaction.messageID).isEmpty)
  }

  @Test
  func `the same sender and emoji is stored once`() async throws {
    let (store, service) = try makeStack()
    let message = makeMessage()
    try await store.saveMessage(message)
    let first = makeReaction(messageID: message.id, radioID: message.radioID, emoji: "👍", sender: "Ada")
    let second = ReactionDTO(
      messageID: message.id,
      emoji: "👍",
      senderName: "Ada",
      messageHash: first.messageHash,
      rawText: first.rawText,
      receivedAt: first.receivedAt.addingTimeInterval(5),
      radioID: message.radioID
    )

    let inserted = await service.persistReactionAndUpdateSummary(first, using: store)
    let duplicate = await service.persistReactionAndUpdateSummary(second, using: store)

    #expect(inserted != nil)
    #expect(duplicate == nil)
    #expect(try await store.fetchReactions(for: message.id).count == 1)
  }

  @Test
  func `the summary keeps the most recent rows`() async throws {
    let (store, _) = try makeStack()
    let message = makeMessage()
    try await store.saveMessage(message)
    let oldest = Date(timeIntervalSince1970: 1_700_000_000)
    for index in 0...ReactionParser.summaryRowLimit {
      _ = try await store.admitReaction(
        makeReaction(
          messageID: message.id,
          radioID: message.radioID,
          emoji: "e\(index)",
          sender: "Ada",
          receivedAt: oldest.addingTimeInterval(TimeInterval(index))
        )
      )
    }

    let stored = try #require(try await store.fetchMessage(id: message.id))
    let rows = try await store.fetchReactions(for: message.id, limit: ReactionParser.summaryRowLimit)
    #expect(rows.count == ReactionParser.summaryRowLimit)
    #expect(try await store.reactionExists(messageID: message.id, senderName: "Ada", emoji: "e0"))
    #expect(stored.reactionSummary == ReactionParser.buildSummary(from: rows))
    #expect(stored.reactionSummary?.contains("e0:") == false)
    #expect(stored.reactionSummary?.contains("e\(ReactionParser.summaryRowLimit):") == true)
  }

  @Test
  func `a held older admit aggregates the reaction committed while it waited`() async throws {
    let (store, service) = try makeStack()
    let message = makeMessage()
    try await store.saveMessage(message)
    let gate = SuspendGate()
    let once = OnceFlag()
    await service.setReactionAdmitHoldForTesting {
      if await once.claim() {
        await gate.enter()
        await gate.waitForRelease()
      }
    }

    let older = Task {
      await service.persistReactionAndUpdateSummary(
        makeReaction(
          messageID: message.id,
          radioID: message.radioID,
          emoji: "older",
          sender: "Ada",
          receivedAt: Date(timeIntervalSince1970: 1_700_000_000)
        ),
        using: store
      )
    }
    await gate.waitForEntry()
    let newer = await service.persistReactionAndUpdateSummary(
      makeReaction(
        messageID: message.id,
        radioID: message.radioID,
        emoji: "newer",
        sender: "Bea",
        receivedAt: Date(timeIntervalSince1970: 1_700_000_010)
      ),
      using: store
    )
    await gate.release()
    let olderResult = await older.value

    let rows = try await store.fetchReactions(for: message.id)
    let expected = ReactionParser.buildSummary(from: rows)
    let stored = try #require(try await store.fetchMessage(id: message.id))
    #expect(newer?.summary.contains("newer") == true)
    #expect(olderResult?.summary == expected)
    #expect(olderResult?.summary.contains("newer") == true)
    #expect(stored.reactionSummary == expected)
  }

  @Test
  @MainActor
  func `a timeline reload replaces a stale in-memory summary`() async throws {
    let coordinator = ChatCoordinator.makeForTesting()
    let store = coordinator.dataStore
    let message = makeMessage()
    try await store.saveMessage(message)
    let service = ReactionService()
    let persisted = await service.persistReactionAndUpdateSummary(
      makeReaction(messageID: message.id, radioID: message.radioID, emoji: "👍", sender: "Ada"),
      using: store
    )
    let storedSummary = try #require(persisted?.summary)
    var stale = try #require(try await store.fetchMessage(id: message.id))
    stale.reactionSummary = "stale"
    coordinator.replaceAllForTesting([stale])

    coordinator.enqueueReload(messageID: message.id)
    await coordinator.coalescedReloadTask?.value

    #expect(coordinator.messagesByID[message.id]?.reactionSummary == storedSummary)
    #expect(storedSummary.contains("👍"))
  }

  private func makeStack() throws -> (PersistenceStore, ReactionService) {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    return (store, ReactionService())
  }

  private func makeMessage() -> MessageDTO {
    MessageDTO.testChannelMessage(
      radioID: UUID(),
      text: "target",
      timestamp: 1_704_067_200,
      direction: .incoming,
      senderNodeName: "Ada"
    )
  }

  private func makeReaction(
    messageID: UUID,
    radioID: UUID,
    emoji: String,
    sender: String,
    receivedAt: Date = Date(timeIntervalSince1970: 1_700_000_000)
  ) -> ReactionDTO {
    ReactionDTO(
      messageID: messageID,
      emoji: emoji,
      senderName: sender,
      messageHash: "hash-\(emoji)-\(sender)",
      rawText: "\(emoji) \(sender)",
      receivedAt: receivedAt,
      radioID: radioID
    )
  }
}

private actor SuspendGate {
  private var hasEntered = false
  private var entryWaiters: [CheckedContinuation<Void, Never>] = []
  private var releaseWaiter: CheckedContinuation<Void, Never>?
  private var isReleased = false

  func enter() {
    hasEntered = true
    let waiters = entryWaiters
    entryWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  func waitForEntry() async {
    if hasEntered { return }
    await withCheckedContinuation { entryWaiters.append($0) }
  }

  func waitForRelease() async {
    if isReleased { return }
    await withCheckedContinuation { releaseWaiter = $0 }
  }

  func release() {
    isReleased = true
    releaseWaiter?.resume()
    releaseWaiter = nil
  }
}

private actor OnceFlag {
  private var claimed = false

  func claim() -> Bool {
    if claimed { return false }
    claimed = true
    return true
  }
}
