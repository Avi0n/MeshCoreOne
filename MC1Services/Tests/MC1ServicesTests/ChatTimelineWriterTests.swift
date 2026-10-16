import Foundation
@testable import MC1Services
import Testing

/// Owner stand-in for `bindWriter`; the coordinator holds it weakly, so
/// tests can drop it to simulate a deallocated view model.
@MainActor
private final class WriterOwner {}

@Suite("ChatTimelineWriter Tests")
@MainActor
struct ChatTimelineWriterTests {
  private func makeMessage(text: String = "hello") -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: UUID(),
      channelIndex: nil,
      text: text,
      timestamp: 1,
      createdAt: Date(),
      sortDate: nil,
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  @Test
  func `interactive bind always succeeds and revokes the prior writer`() throws {
    let coordinator = ChatCoordinator.makeForTesting()
    let ownerA = WriterOwner()
    let ownerB = WriterOwner()

    let writerA = try #require(coordinator.bindWriter(owner: ownerA, role: .interactive))
    #expect(writerA.isCurrent)

    let writerB = try #require(coordinator.bindWriter(owner: ownerB, role: .interactive))
    #expect(writerB.isCurrent)
    #expect(!writerA.isCurrent)
  }

  @Test
  func `releaseWriter vacates the slot for a later prime bind`() throws {
    let coordinator = ChatCoordinator.makeForTesting()
    let owner = WriterOwner()
    let primeOwner = WriterOwner()

    let writer = try #require(coordinator.bindWriter(owner: owner, role: .interactive))
    #expect(coordinator.bindWriter(owner: primeOwner, role: .prime) == nil)

    coordinator.releaseWriter(owner: owner)

    let primeWriter = try #require(coordinator.bindWriter(owner: primeOwner, role: .prime))
    #expect(primeWriter.isCurrent)
    #expect(!writer.isCurrent)
  }

  @Test
  func `releaseWriter from a superseded owner does not evict the successor`() throws {
    let coordinator = ChatCoordinator.makeForTesting()
    let ownerA = WriterOwner()
    let ownerB = WriterOwner()
    let primeOwner = WriterOwner()

    _ = try #require(coordinator.bindWriter(owner: ownerA, role: .interactive))
    let writerB = try #require(coordinator.bindWriter(owner: ownerB, role: .interactive))

    coordinator.releaseWriter(owner: ownerA)

    #expect(coordinator.bindWriter(owner: primeOwner, role: .prime) == nil)
    #expect(writerB.isCurrent)
  }

  @Test
  func `stale writer mutations all no-op`() throws {
    let coordinator = ChatCoordinator.makeForTesting()
    let ownerA = WriterOwner()
    let ownerB = WriterOwner()
    let seeded = makeMessage()

    let writerA = try #require(coordinator.bindWriter(owner: ownerA, role: .interactive))
    writerA.replaceAll([seeded])
    #expect(coordinator.messages.count == 1)

    let writerB = try #require(coordinator.bindWriter(owner: ownerB, role: .interactive))
    _ = writerB

    let renderStateIDBefore = coordinator.renderStateID
    let messagesBefore = coordinator.messages
    let renderStateBefore = coordinator.renderState

    let extra = makeMessage(text: "stale append")
    writerA.replaceAll([])
    #expect(writerA.append(extra) == false)
    writerA.prepend([extra])
    writerA.update(messageID: seeded.id) { $0.text = "stale edit" }
    writerA.remove(messageID: seeded.id)
    writerA.replaceMessagesPreservingByID([])
    writerA.beginLoading()
    writerA.markLoaded()
    writerA.updateRenderState { $0.with(isLoadingOlder: true) }
    writerA.updateRenderItem(id: seeded.id) { $0 }
    writerA.removeRenderItem(id: seeded.id)
    writerA.applyStatusUpdate(messageID: seeded.id, status: .failed)
    writerA.rebuildItems(inputs: [], envInputs: .default)
    writerA.enqueueReload(messageID: seeded.id)

    #expect(coordinator.renderStateID == renderStateIDBefore)
    #expect(coordinator.messages == messagesBefore)
    #expect(coordinator.renderState == renderStateBefore)
    #expect(coordinator.messagesByID[seeded.id]?.text == "hello")
  }

  @Test
  func `current writer mutations apply`() throws {
    let coordinator = ChatCoordinator.makeForTesting()
    let owner = WriterOwner()
    let writer = try #require(coordinator.bindWriter(owner: owner, role: .interactive))
    let message = makeMessage()

    #expect(writer.append(message))
    #expect(coordinator.messages.count == 1)

    writer.update(messageID: message.id) { $0.text = "edited" }
    #expect(coordinator.messagesByID[message.id]?.text == "edited")

    writer.remove(messageID: message.id)
    #expect(coordinator.messages.isEmpty)
  }

  @Test
  func `prime bind is denied while an interactive owner is alive`() throws {
    let coordinator = ChatCoordinator.makeForTesting()
    let interactiveOwner = WriterOwner()
    let primeOwner = WriterOwner()

    let interactive = try #require(coordinator.bindWriter(owner: interactiveOwner, role: .interactive))
    #expect(coordinator.bindWriter(owner: primeOwner, role: .prime) == nil)
    #expect(interactive.isCurrent)
  }

  @Test
  func `prime bind succeeds over a vacant slot, a prior prime, and a deallocated owner`() throws {
    let coordinator = ChatCoordinator.makeForTesting()

    // Vacant slot.
    let primeOwnerA = WriterOwner()
    let primeA = try #require(coordinator.bindWriter(owner: primeOwnerA, role: .prime))

    // Prior prime.
    let primeOwnerB = WriterOwner()
    let primeB = try #require(coordinator.bindWriter(owner: primeOwnerB, role: .prime))
    #expect(!primeA.isCurrent)
    #expect(primeB.isCurrent)

    func bindTransientInteractiveOwner() {
      let owner = WriterOwner()
      #expect(coordinator.bindWriter(owner: owner, role: .interactive) != nil)
    }
    // Deallocated interactive owner frees the slot: the owner's only
    // strong reference dies with this scope (the coordinator holds it
    // weakly), so the follow-up prime bind sees a vacant slot.
    bindTransientInteractiveOwner()
    let primeOwnerC = WriterOwner()
    #expect(coordinator.bindWriter(owner: primeOwnerC, role: .prime) != nil)
  }

  @Test
  func `interactive bind swaps hooks atomically with write ownership`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let ownerA = WriterOwner()
    let ownerB = WriterOwner()
    var rebuilderHits: [String] = []

    let primeWriter = coordinator.bindWriter(
      owner: ownerA,
      role: .prime,
      renderItemRebuilder: { _ in rebuilderHits.append("prime") }
    )
    let interactiveWriter = coordinator.bindWriter(
      owner: ownerB,
      role: .interactive,
      renderItemRebuilder: { _ in rebuilderHits.append("interactive") }
    )
    #expect(primeWriter != nil)
    #expect(interactiveWriter != nil)

    coordinator.renderItemRebuilder?(UUID())
    #expect(rebuilderHits == ["interactive"])
  }

  @Test
  func `stale writer cannot schedule a full rebuild`() throws {
    let coordinator = ChatCoordinator.makeForTesting()
    let ownerA = WriterOwner()
    let ownerB = WriterOwner()
    let message = makeMessage()

    let writerA = try #require(coordinator.bindWriter(owner: ownerA, role: .prime))
    writerA.replaceAll([message])

    _ = try #require(coordinator.bindWriter(owner: ownerB, role: .interactive))
    let renderStateIDBefore = coordinator.renderStateID

    // `rebuildItems` bumps the generation counter before capturing it
    // (last-scheduled-wins), which is exactly why a stale writer must not
    // reach it: this call must leave the counter untouched.
    writerA.rebuildItems(inputs: [], envInputs: .default)
    #expect(coordinator.renderStateID == renderStateIDBefore)
    #expect(coordinator.buildItemsTask == nil)
  }
}
