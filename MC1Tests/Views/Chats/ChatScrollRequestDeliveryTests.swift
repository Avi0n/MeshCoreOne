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

  @Test(arguments: [false, true])
  func `paging error or unavailable store keeps the request for a later attempt`(
    storeUnavailable: Bool
  ) async throws {
    let fixture = try await makeFixture(isChannel: false)
    let targetID = try #require(fixture.messages.first?.id)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let request = navigation.pendingScrollTarget
    if storeUnavailable {
      fixture.timeline.dataStoreProvider = { nil }
    } else {
      fixture.timeline.loadOlderTestError = FixtureError.failedToPopulate
    }

    #expect(await deliver(fixture, from: navigation) == nil)
    #expect(navigation.pendingScrollTarget == request)
    #expect(fixture.timeline.renderState.totalFetchedCount == ChatCoordinator.pageSize)
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
