import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Chat scroll request delivery")
@MainActor
struct ChatScrollRequestDeliveryTests {
  @Test(arguments: [false, true], [false, true])
  func `reaction notification loads an older target before delivering its scroll request`(
    isChannel: Bool,
    hiddenMiddlePage: Bool
  ) async throws {
    let fixture = try await makeFixture(isChannel: isChannel, hiddenMiddlePage: hiddenMiddlePage)
    let targetID = try #require(fixture.messages.first?.id)
    let timeline = fixture.timeline
    try #require(timeline.messages.count == ChatCoordinator.pageSize)
    try #require(!timeline.items.contains { $0.id == targetID })
    try #require(timeline.renderState.hasMoreMessages)

    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)

    let deliveredID = await deliver(fixture, from: navigation)

    #expect(deliveredID == targetID)
    #expect(timeline.items.contains { $0.id == targetID })
    #expect(navigation.pendingScrollTarget == nil)
  }

  @Test
  func `already loaded target does not page older history`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let targetID = try #require(fixture.messages.last?.id)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let initialCount = fixture.timeline.renderState.totalFetchedCount

    #expect(await deliver(fixture, from: navigation) == targetID)
    #expect(fixture.timeline.renderState.totalFetchedCount == initialCount)
    #expect(navigation.pendingScrollTarget == nil)
  }

  @Test
  func `unready or mismatched conversation preserves the request without paging`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let targetID = try #require(fixture.messages.first?.id)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let request = navigation.pendingScrollTarget

    #expect(await deliver(fixture, from: navigation, canHonor: false) == nil)
    #expect(navigation.pendingScrollTarget == request)
    let delivered = await ChatScrollRequestDelivery.takeIfHonorable(
      from: navigation, kind: .channel,
      conversationID: fixture.conversation.conversationID,
      canHonor: true, timeline: fixture.timeline,
      loadOlder: { Issue.record("A mismatched request must not page") }
    )
    #expect(delivered == nil)
    #expect(navigation.pendingScrollTarget == request)
    #expect(fixture.timeline.renderState.totalFetchedCount == ChatCoordinator.pageSize)

    #expect(await deliver(fixture, from: navigation) == targetID)
    #expect(navigation.pendingScrollTarget == nil)
  }

  @Test(arguments: [100, 103])
  func `deleted target retires after reaching the end without issuing a scroll`(messageCount: Int) async throws {
    let fixture = try await makeFixture(isChannel: false, messageCount: messageCount)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: UUID())

    #expect(await deliver(fixture, from: navigation) == nil)
    #expect(!fixture.timeline.renderState.hasMoreMessages)
    #expect(fixture.timeline.renderState.totalFetchedCount == fixture.messages.count)
    #expect(navigation.pendingScrollTarget == nil)
  }

  @Test
  func `an unavailable store waits and does not fetch again when cancelled`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let targetID = try #require(fixture.messages.first?.id)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let request = navigation.pendingScrollTarget
    let coordinator = try #require(fixture.timeline.coordinator)
    fixture.timeline.dataStoreProvider = { nil }
    var installed = false
    coordinator.scrollRenderWaitInstalledHook = { installed = true }

    var calls = 0
    let task = Task {
      await ChatScrollRequestDelivery.takeIfHonorable(
        from: navigation,
        kind: fixture.conversation.chatRouteKind,
        conversationID: fixture.conversation.conversationID,
        canHonor: true,
        timeline: fixture.timeline,
        loadOlder: {
          calls += 1
          _ = try? await fixture.timeline.loadOlder()
        }
      )
    }
    defer {
      coordinator.scrollRenderWaitInstalledHook = nil
      task.cancel()
    }
    try await waitUntil("an unavailable store did not install a scroll waiter") { installed }

    #expect(calls == 1)
    #expect(navigation.pendingScrollTarget == request)
    #expect(fixture.timeline.renderState.totalFetchedCount == ChatCoordinator.pageSize)
    task.cancel()
    #expect(await task.value == nil)
    #expect(calls == 1)
    #expect(navigation.pendingScrollTarget == request)
  }

  @Test
  func `a thrown page then a later loadOlder delivers the original request`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let targetID = try #require(fixture.messages.first?.id)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let coordinator = try #require(fixture.timeline.coordinator)
    fixture.timeline.loadOlderTestError = FixtureError.failedToPopulate
    var installed = false
    coordinator.scrollRenderWaitInstalledHook = { installed = true }
    let task = Task { await deliver(fixture, from: navigation) }
    defer {
      fixture.timeline.loadOlderTestError = nil
      coordinator.scrollRenderWaitInstalledHook = nil
      task.cancel()
    }
    try await waitUntil("the thrown page did not install a scroll waiter") { installed }
    fixture.timeline.loadOlderTestError = nil
    _ = try await fixture.timeline.loadOlder()

    #expect(await task.value == targetID)
    #expect(navigation.pendingScrollTarget == nil)
    #expect(fixture.timeline.messages.contains { $0.id == targetID })
  }

  @Test
  func `replacing the request during the scroll wait keeps the replacement pending`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let targetID = try #require(fixture.messages.first?.id)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let originalRequestID = try #require(navigation.pendingScrollTarget?.requestID)
    let coordinator = try #require(fixture.timeline.coordinator)
    fixture.timeline.loadOlderTestError = FixtureError.failedToPopulate
    let waiting = Signal()
    coordinator.scrollRenderWaitInstalledHook = { waiting.open() }
    defer { coordinator.scrollRenderWaitInstalledHook = nil }

    var calls = 0
    let task = Task {
      await ChatScrollRequestDelivery.takeIfHonorable(
        from: navigation,
        kind: fixture.conversation.chatRouteKind,
        conversationID: fixture.conversation.conversationID,
        canHonor: true,
        timeline: fixture.timeline,
        loadOlder: {
          calls += 1
          _ = try? await fixture.timeline.loadOlder()
        }
      )
    }
    await waiting.wait()
    let replacementID = UUID()
    navigate(navigation, to: fixture.conversation, messageID: replacementID)
    fixture.timeline.loadOlderTestError = nil
    _ = try await fixture.timeline.loadOlder()

    #expect(await task.value == nil)
    #expect(calls == 1)
    let pending = navigation.pendingScrollTarget
    #expect(pending?.messageID == replacementID)
    #expect(pending?.requestID != originalRequestID)
  }

  @Test
  func `a replacement bake that indexes the target delivers the original request`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let coordinator = try #require(fixture.timeline.coordinator)
    let writer = try #require(fixture.timeline.writer)
    let target = try #require(fixture.timeline.items.last)
    writer.updateRenderState { $0.removingItem(id: target.id) }
    #expect(fixture.timeline.messagesByID[target.id] != nil)
    #expect(fixture.timeline.itemIndexByID[target.id] == nil)

    let started = Gate()
    let release = Gate()
    coordinator.buildItemsTask = Task {
      await started.open()
      await release.wait()
    }
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: target.id)
    let task = Task { await deliver(fixture, from: navigation) }
    await started.wait()
    await Task.yield()

    coordinator.renderStateID &+= 1
    let indexed = Signal()
    coordinator.buildItemsTask = Task {
      writer.updateRenderState { $0.appendingItem(target) }
      indexed.open()
    }
    await indexed.wait()
    await release.open()

    #expect(await task.value == target.id)
    #expect(navigation.pendingScrollTarget == nil)
  }

  @Test
  func `a bake that misses the target without a generation change leaves the request pending`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let coordinator = try #require(fixture.timeline.coordinator)
    let writer = try #require(fixture.timeline.writer)
    let target = try #require(fixture.timeline.items.last)
    writer.updateRenderState { $0.removingItem(id: target.id) }
    let generation = coordinator.renderStateID

    let started = Gate()
    let release = Gate()
    coordinator.buildItemsTask = Task {
      await started.open()
      await release.wait()
    }
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: target.id)
    let request = navigation.pendingScrollTarget
    let task = Task { await deliver(fixture, from: navigation) }
    await started.wait()
    await release.open()

    #expect(await task.value == nil)
    #expect(navigation.pendingScrollTarget == request)
    #expect(coordinator.renderStateID == generation)
    #expect(fixture.timeline.itemIndexByID[target.id] == nil)
  }

  @Test(arguments: [false, true])
  func `cancellation or a repeated notification tap cannot consume the pending request`(
    cancel: Bool
  ) async throws {
    let fixture = try await makeFixture(isChannel: false)
    let targetID = try #require(fixture.messages.first?.id)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let arrived = Gate()
    let resume = Gate()
    fixture.timeline.loadOlderInterleaveHook = {
      await arrived.open()
      await resume.wait()
    }
    defer { fixture.timeline.loadOlderInterleaveHook = nil }
    let task = Task { await deliver(fixture, from: navigation) }
    await arrived.wait()
    if cancel {
      task.cancel()
    } else {
      navigate(navigation, to: fixture.conversation, messageID: targetID)
    }
    let remainingRequest = navigation.pendingScrollTarget
    await resume.open()

    #expect(await task.value == nil)
    #expect(navigation.pendingScrollTarget == remainingRequest)
    #expect(fixture.timeline.renderState.totalFetchedCount <= ChatCoordinator.pageSize * 2)
  }

  @Test
  func `a replaced timeline writer cannot deliver the request`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let coordinator = try #require(fixture.timeline.coordinator)
    let targetID = try #require(fixture.messages.first?.id)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let request = navigation.pendingScrollTarget
    let arrived = Gate()
    let resume = Gate()
    fixture.timeline.loadOlderInterleaveHook = {
      await arrived.open()
      await resume.wait()
    }
    defer { fixture.timeline.loadOlderInterleaveHook = nil }
    let task = Task { await deliver(fixture, from: navigation) }
    await arrived.wait()
    let replacement = ChatTimeline(role: .interactive)
    replacement.bind(coordinator, dataStore: { nil }, senderTables: { .empty }, postApply: nil)
    await resume.open()

    #expect(await task.value == nil)
    #expect(navigation.pendingScrollTarget == request)
    #expect(replacement.writer?.isCurrent == true)
  }
}
