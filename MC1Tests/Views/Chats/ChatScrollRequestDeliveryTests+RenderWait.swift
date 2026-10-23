import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension ChatScrollRequestDeliveryTests {
  private enum WaitTiming {
    static let readinessTimeout: Duration = .seconds(5)
  }

  /// A render write that must leave an installed scroll waiter suspended.
  private enum SuspendedWrite: String, CaseIterable, Sendable {
    case spinnerUpdate
    case spinnerSet
    case otherAdmission
    case staleSet
  }

  @MainActor
  private final class DeliveryProbe {
    var calls = 0
    var pageReturned = false
    var installed = false
    var completed = false
    var delivered: UUID?
    func record(_ value: UUID?) {
      delivered = value
      completed = true
    }
  }

  private struct FailedPageRun {
    let coordinator: ChatCoordinator
    let navigation: NavigationCoordinator
    let probe: DeliveryProbe
    let task: Task<UUID?, Never>
    let request: PendingScrollTarget?
    let initialCount: Int
    var token: UUID?
  }

  @Test
  func `admitting the target during a failed page delivers it without another write`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let target = try #require(fixture.messages.first)
    let run = try beginFailedPage(fixture, targetID: target.id) { _ = fixture.timeline.admit(target) }
    defer { clearDeliveryHooks(fixture, coordinator: run.coordinator) { run.task.cancel() } }
    try await awaitPage(run, fixture: fixture)
    try #require(run.coordinator.scrollRenderWaiter == nil, "the failed page installed a scroll waiter")
    try await awaitDelivery(run, "the admitted target was not delivered")
    try await expectConsumed(run, id: target.id, installed: false)
  }

  @Test
  func `a canonical-only target after a failed page is delivered by the bake branch`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let target = try #require(fixture.messages.first)
    let coordinator = try #require(fixture.timeline.coordinator)
    let writer = try #require(fixture.timeline.writer)
    let releaseBake = Signal()
    let run = try beginFailedPage(fixture, targetID: target.id) {
      _ = writer.append(target)
      let item = fixture.timeline.makeItem(for: target, previous: nil, next: nil)
      coordinator.buildItemsTask = Task {
        await releaseBake.wait()
        writer.updateRenderState { $0.appendingItem(item) }
      }
    }
    defer { clearDeliveryHooks(fixture, coordinator: coordinator, release: releaseBake) { run.task.cancel() } }
    try await awaitPage(run, fixture: fixture)
    #expect(fixture.timeline.messagesByID[target.id] != nil)
    #expect(fixture.timeline.itemIndexByID[target.id] == nil)
    #expect(run.navigation.pendingScrollTarget == run.request)
    try #require(run.coordinator.scrollRenderWaiter == nil, "the failed page installed a scroll waiter")
    releaseBake.open()
    try await awaitDelivery(run, "the bake did not deliver the canonical target")
    try await expectConsumed(run, id: target.id, installed: false)
  }

  @Test(arguments: [false, true])
  func `a ready scroll wait returns without installing a waiter`(historyExhausted: Bool) async throws {
    let fixture = try await makeFixture(isChannel: false)
    let coordinator = try #require(fixture.timeline.coordinator)
    let messageID: UUID
    if historyExhausted {
      let applied = coordinator.setRenderState(
        ChatRenderState.empty.with(hasMoreMessages: false, phase: .loaded),
        capturedID: coordinator.renderStateID
      )
      try #require(applied)
      messageID = UUID()
    } else {
      messageID = try #require(fixture.timeline.items.last?.id)
    }
    let before = coordinator.renderState
    let probe = DeliveryProbe()
    coordinator.scrollRenderWaitInstalledHook = { probe.installed = true }
    let task = Task {
      await coordinator.waitForHonorableScrollRender(messageID: messageID)
      probe.record(nil)
    }
    defer { clearDeliveryHooks(fixture, coordinator: coordinator) { task.cancel() } }
    try await waitUntil(timeout: WaitTiming.readinessTimeout, "a ready scroll wait did not return") {
      probe.completed
    }
    _ = await task.value
    #expect(!probe.installed)
    #expect(coordinator.scrollRenderWaiter == nil)
    #expect(coordinator.renderState == before)
  }

  @Test
  func `an immediate ready wait leaves another target's waiter installed`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let coordinator = try #require(fixture.timeline.coordinator)
    let missingID = try #require(fixture.messages.first?.id)
    let indexedID = try #require(fixture.timeline.items.last?.id)
    try #require(fixture.timeline.itemIndexByID[missingID] == nil)
    try #require(fixture.timeline.renderState.hasMoreMessages)
    let probe = DeliveryProbe()
    let parked = Task { await coordinator.waitForHonorableScrollRender(messageID: missingID) }
    defer {
      coordinator.scrollRenderWaitInstalledHook = nil
      parked.cancel()
    }
    try await waitUntil(timeout: WaitTiming.readinessTimeout, "the parked scroll wait was not installed") {
      coordinator.scrollRenderWaiter?.messageID == missingID
    }
    let token = try #require(coordinator.scrollRenderWaiter?.token)
    var readyHookFired = false
    coordinator.scrollRenderWaitInstalledHook = { readyHookFired = true }
    let ready = Task {
      await coordinator.waitForHonorableScrollRender(messageID: indexedID)
      probe.record(indexedID)
    }
    defer { ready.cancel() }
    try await waitUntil(timeout: WaitTiming.readinessTimeout, "the ready scroll wait did not return") {
      probe.completed
    }
    _ = await ready.value
    #expect(!readyHookFired)
    #expect(coordinator.scrollRenderWaiter?.token == token)
    #expect(coordinator.scrollRenderWaiter?.messageID == missingID)
    parked.cancel()
    _ = await parked.value
  }

  @Test
  func `canonical-only presence still installs a scroll waiter`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let coordinator = try #require(fixture.timeline.coordinator)
    let writer = try #require(fixture.timeline.writer)
    let target = try #require(fixture.timeline.items.last)
    writer.updateRenderState { $0.removingItem(id: target.id) }
    #expect(fixture.timeline.messagesByID[target.id] != nil)
    #expect(fixture.timeline.itemIndexByID[target.id] == nil)
    #expect(fixture.timeline.renderState.hasMoreMessages)
    let probe = DeliveryProbe()
    coordinator.scrollRenderWaitInstalledHook = { probe.installed = true }
    let task = Task { await coordinator.waitForHonorableScrollRender(messageID: target.id) }
    defer { clearDeliveryHooks(fixture, coordinator: coordinator) { task.cancel() } }
    try await waitUntil(timeout: WaitTiming.readinessTimeout, "canonical-only presence did not install a waiter") {
      probe.installed && coordinator.scrollRenderWaiter?.messageID == target.id
    }
    task.cancel()
    _ = await task.value
    #expect(coordinator.scrollRenderWaiter == nil)
  }

  @Test
  func `admitting the target after the waiter is installed delivers the original request`() async throws {
    let fixture = try await makeFixture(isChannel: false)
    let target = try #require(fixture.messages.first)
    var run = try beginFailedPage(fixture, targetID: target.id)
    defer { clearDeliveryHooks(fixture, coordinator: run.coordinator) { run.task.cancel() } }
    try await installWaiter(&run)
    #expect(fixture.timeline.admit(target).inserted)
    try await awaitDelivery(run, "the admitted target was not delivered")
    try await expectConsumed(run, id: target.id, installed: true)
  }

  @Test(arguments: [false, true])
  func `ending history after the waiter is installed retires the missing request`(
    useSetRenderState: Bool
  ) async throws {
    let fixture = try await makeFixture(isChannel: false)
    let target = try #require(fixture.messages.first)
    var run = try beginFailedPage(fixture, targetID: target.id)
    let writer = try #require(fixture.timeline.writer)
    defer { clearDeliveryHooks(fixture, coordinator: run.coordinator) { run.task.cancel() } }
    try await installWaiter(&run)
    #expect(publish(useSetRenderState: useSetRenderState, on: run, writer: writer) {
      $0.with(hasMoreMessages: false)
    })
    try await awaitDelivery(run, "ending history did not retire the request")
    #expect(await run.task.value == nil)
    #expect(run.navigation.pendingScrollTarget == nil)
    #expect(run.probe.calls == 1)
    #expect(fixture.timeline.renderState.totalFetchedCount == run.initialCount)
    #expect(!fixture.timeline.renderState.hasMoreMessages)
  }

  @Test(arguments: SuspendedWrite.allCases)
  private func `non-matching writes leave the installed waiter suspended`(_ write: SuspendedWrite) async throws {
    let fixture = try await makeFixture(isChannel: false)
    let target = try #require(fixture.messages.first)
    var run = try beginFailedPage(fixture, targetID: target.id)
    let writer = try #require(fixture.timeline.writer)
    defer { clearDeliveryHooks(fixture, coordinator: run.coordinator) { run.task.cancel() } }
    try await installWaiter(&run)
    let before = run.coordinator.renderState
    switch write {
    case .spinnerUpdate, .spinnerSet:
      let useSetRenderState = write == .spinnerSet
      #expect(publish(useSetRenderState: useSetRenderState, on: run, writer: writer) { $0.with(isLoadingOlder: true) })
      #expect(run.coordinator.scrollRenderWaiter?.token == run.token)
      #expect(publish(useSetRenderState: useSetRenderState, on: run, writer: writer) { $0.with(isLoadingOlder: false) })
    case .otherAdmission:
      let other = try #require(fixture.messages.dropFirst().first)
      try #require(fixture.timeline.itemIndexByID[other.id] == nil)
      #expect(fixture.timeline.admit(other).inserted)
      #expect(fixture.timeline.itemIndexByID[other.id] != nil)
    case .staleSet:
      let item = fixture.timeline.makeItem(for: target, previous: nil, next: nil)
      let proposed = before.appendingItem(item).with(
        hasMoreMessages: false,
        totalFetchedCount: before.totalFetchedCount + 1
      )
      #expect(!run.coordinator.setRenderState(proposed, capturedID: run.coordinator.renderStateID &- 1))
      #expect(run.coordinator.renderState == before)
    }
    #expect(run.coordinator.scrollRenderWaiter?.token == run.token)
    #expect(run.navigation.pendingScrollTarget == run.request)
    #expect(run.probe.calls == 1)
    #expect(!run.probe.completed)
    run.task.cancel()
    #expect(await run.task.value == nil)
    #expect(run.probe.calls == 1)
    #expect(run.navigation.pendingScrollTarget == run.request)
  }

  private func beginFailedPage(
    _ fixture: Fixture,
    targetID: UUID,
    hook: (@MainActor () async -> Void)? = nil
  ) throws -> FailedPageRun {
    let coordinator = try #require(fixture.timeline.coordinator)
    try #require(!fixture.timeline.items.contains { $0.id == targetID })
    try #require(fixture.timeline.renderState.hasMoreMessages)
    let navigation = NavigationCoordinator()
    navigate(navigation, to: fixture.conversation, messageID: targetID)
    let probe = DeliveryProbe()
    fixture.timeline.loadOlderTestError = FixtureError.failedToPopulate
    fixture.timeline.loadOlderInterleaveHook = hook
    coordinator.scrollRenderWaitInstalledHook = { probe.installed = true }
    return FailedPageRun(
      coordinator: coordinator,
      navigation: navigation,
      probe: probe,
      task: startDelivery(fixture, from: navigation, probe: probe),
      request: navigation.pendingScrollTarget,
      initialCount: fixture.timeline.renderState.totalFetchedCount
    )
  }

  private func installWaiter(_ run: inout FailedPageRun) async throws {
    let probe = run.probe
    let coordinator = run.coordinator
    try await waitUntil(timeout: WaitTiming.readinessTimeout, "scroll waiter was not installed") {
      probe.installed
    }
    run.token = try #require(coordinator.scrollRenderWaiter?.token)
  }

  private func awaitPage(_ run: FailedPageRun, fixture: Fixture) async throws {
    try await waitUntil(timeout: WaitTiming.readinessTimeout, "the failed page did not return") {
      run.probe.pageReturned
    }
    #expect(run.probe.calls == 1)
    #expect(fixture.timeline.renderState.totalFetchedCount == run.initialCount)
  }

  private func awaitDelivery(_ run: FailedPageRun, _ message: String) async throws {
    try await waitUntil(timeout: WaitTiming.readinessTimeout, message) { run.probe.completed }
  }

  private func expectConsumed(_ run: FailedPageRun, id: UUID, installed: Bool) async throws {
    #expect(await run.task.value == id)
    #expect(run.navigation.pendingScrollTarget == nil)
    #expect(run.probe.installed == installed)
    #expect(run.probe.calls == 1)
    #expect(run.coordinator.renderState.totalFetchedCount == run.initialCount)
  }

  private func startDelivery(
    _ fixture: Fixture,
    from navigation: NavigationCoordinator,
    probe: DeliveryProbe
  ) -> Task<UUID?, Never> {
    Task {
      let delivered = await ChatScrollRequestDelivery.takeIfHonorable(
        from: navigation,
        kind: fixture.conversation.chatRouteKind,
        conversationID: fixture.conversation.conversationID,
        canHonor: true,
        timeline: fixture.timeline,
        loadOlder: {
          probe.calls += 1
          _ = try? await fixture.timeline.loadOlder()
          probe.pageReturned = true
        }
      )
      probe.record(delivered)
      return delivered
    }
  }

  private func publish(
    useSetRenderState: Bool,
    on run: FailedPageRun,
    writer: ChatTimelineWriter,
    _ transform: (ChatRenderState) -> ChatRenderState
  ) -> Bool {
    if useSetRenderState {
      return run.coordinator.setRenderState(transform(run.coordinator.renderState), capturedID: run.coordinator.renderStateID)
    }
    writer.updateRenderState(transform)
    return true
  }

  private func clearDeliveryHooks(
    _ fixture: Fixture,
    coordinator: ChatCoordinator,
    release: Signal? = nil,
    cancelTask: (() -> Void)? = nil
  ) {
    fixture.timeline.loadOlderInterleaveHook = nil
    fixture.timeline.loadOlderTestError = nil
    coordinator.scrollRenderWaitInstalledHook = nil
    release?.open()
    coordinator.buildItemsTask?.cancel()
    coordinator.buildItemsTask = nil
    cancelTask?()
  }
}
