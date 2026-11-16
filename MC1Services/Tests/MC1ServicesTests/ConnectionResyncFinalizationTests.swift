import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Resync terminal finalization ownership")
@MainActor
struct ConnectionResyncFinalizationTests {
  private static let initialEvents: [DeviceConnectionState] = [.connecting, .connecting, .connected, .syncing, .syncing]
  private static let successorEvents: [DeviceConnectionState] = [.connecting, .connecting, .connected, .syncing, .ready]

  enum NextAction: CaseIterable {
    case sameRadio
    case differentRadio
    case disconnect
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true], NextAction.allCases)
  func `terminal resync result cannot cross a connection change during its activity callback`(succeeds: Bool, action: NextAction) async throws {
    let clock = TestClock()
    let env = try ConnectionManagerRadioTestHarness(sameRadio: action != .differentRadio, clock: clock)
    let events = ValueTracker<DeviceConnectionState>()
    let stream = env.manager.connectionStateEvents.subscribe()
    let observer = Task { for await state in stream {
      events.record(state)
    } }
    let ended = Gate()
    let expectedAttempts = succeeds ? 1 : ConnectionManager.maxResyncAttempts
    let activity = Activity()
    var failureGenerations: [UUID] = []
    var syncedGenerations: [UUID] = []
    var resync: Task<Void, Never>?
    var replacement: Task<ConnectionResult, Error>?
    var terminal: Task<ConnectionLossReceipt, Never>?
    env.manager.onDeviceSynced = { snapshot, _ in syncedGenerations.append(snapshot.generation) }
    env.manager.onResyncFailed = { snapshot in failureGenerations.append(snapshot.generation) }
    env.manager.onConnectionReady = { snapshot in
      await snapshot.services.syncCoordinator.setPerformResyncOverride { _, _ in
        await activity.attempted()
        return succeeds ? FullSyncResult(contacts: .clean, channels: .clean, messages: .clean) : nil
      }
      await snapshot.services.syncCoordinator.setSyncActivityCallbacks(
        onStarted: {},
        onEnded: { succeeded in
          if await activity.didEnd(succeeded: succeeded, expectedAttempts: expectedAttempts, expectedSuccess: succeeds) {
            await ended.wait()
          }
        },
        onPhaseChanged: { _ in }
      )
    }
    await env.ble.rejectNext(.getContacts)
    do {
      _ = try await env.connect(.bluetooth)
      let old = try #require(env.manager.currentConnection)
      #expect(env.manager.connectionState == .syncing)
      try await waitUntilClockAdvances(clock, timeout: .seconds(10), "terminal resync should hold its activity-end callback") { ended.isWaiting }
      let oldResync = try #require(env.manager.resyncTask)
      resync = oldResync
      switch action {
      case .sameRadio, .differentRadio:
        await env.wifi.holdReplies(for: .appStart)
        replacement = Task { try await env.connect(.wifi) }
      case .disconnect:
        terminal = Task { await env.manager.disconnect(reason: .userInitiated) }
      }
      try await waitUntilClockAdvances(clock, "the new request must retire the old resync owner") {
        env.manager.connectionRequestID != old.requestID
      }
      #expect(env.manager.currentConnection == nil)
      #expect(await env.wifi.connectCount == 0)
      #expect(failureGenerations.isEmpty)
      ended.open()
      await oldResync.value
      #expect(activity.terminalEnds == 1)
      #expect(failureGenerations.isEmpty)
      switch action {
      case .sameRadio, .differentRadio:
        try await waitUntilClockAdvances(clock, "successor handshake must wait until old resync drains") {
          await env.wifi.heldReplyCount(for: .appStart) == 1
        }
        #expect(env.manager.currentConnection == nil)
        #expect(syncedGenerations.isEmpty)
        await env.wifi.releaseReplies(for: .appStart)
        let result = try await #require(replacement).value
        #expect(env.manager.isCurrent(result))
        let current = try #require(env.manager.currentConnection)
        #expect(env.manager.connectionState == .ready)
        #expect(current.generation != old.generation)
        #expect((current.device.radioID == old.device.radioID) == (action == .sameRadio))
        #expect(syncedGenerations == [current.generation])
        #expect(env.manager.resyncAttemptCount == 0)
        #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
      case .disconnect:
        let receipt = try await #require(terminal).value
        #expect(env.manager.isCurrent(receipt))
        #expect(env.manager.connectionState == .disconnected)
        #expect(env.manager.connectionIntent.isUserDisconnected)
        #expect(syncedGenerations.isEmpty)
      }
      #expect(await old.services.session.subscriberCountForTest == 0)
      env.manager.connectionStateEvents.finish()
      await observer.value
      let expectedEvents = Self.initialEvents + (action == .disconnect ? [.disconnected] : Self.successorEvents)
      #expect(events.values == expectedEvents)
    } catch {
      ended.open()
      replacement?.cancel()
      await env.ble.releaseAllGates()
      await env.wifi.releaseAllGates()
      await resync?.value
      _ = await replacement?.result
      _ = await terminal?.value
      observer.cancel()
      env.manager.connectionStateEvents.finish()
      await observer.value
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true])
  func `cancelling terminal resync keeps its current stack`(succeeds: Bool) async throws {
    let clock = TestClock()
    let env = try ConnectionManagerRadioTestHarness(clock: clock)
    let events = ValueTracker<DeviceConnectionState>()
    let stream = env.manager.connectionStateEvents.subscribe()
    let observer = Task { for await state in stream {
      events.record(state)
    } }
    let ended = Gate()
    let expectedAttempts = succeeds ? 1 : ConnectionManager.maxResyncAttempts
    let activity = Activity()
    var synced = 0
    var failed = 0
    var resync: Task<Void, Never>?
    env.manager.onDeviceSynced = { _, _ in synced += 1 }
    env.manager.onResyncFailed = { _ in failed += 1 }
    env.manager.onConnectionReady = { snapshot in
      await snapshot.services.syncCoordinator.setPerformResyncOverride { _, _ in
        await activity.attempted()
        return succeeds ? FullSyncResult(contacts: .clean, channels: .clean, messages: .clean) : nil
      }
      await snapshot.services.syncCoordinator.setSyncActivityCallbacks(
        onStarted: {},
        onEnded: { succeeded in
          if await activity.didEnd(succeeded: succeeded, expectedAttempts: expectedAttempts, expectedSuccess: succeeds) {
            await ended.wait()
          }
        },
        onPhaseChanged: { _ in }
      )
    }
    await env.ble.rejectNext(.getContacts)
    do {
      _ = try await env.connect(.bluetooth)
      let current = try #require(env.manager.currentConnection)
      try await waitUntilClockAdvances(clock, timeout: .seconds(10), "terminal resync must reach its held activity callback") { ended.isWaiting }
      let oldResync = try #require(env.manager.resyncTask)
      resync = oldResync
      env.manager.cancelResyncLoop()
      ended.open()
      await oldResync.value
      #expect(activity.terminalEnds == 1)
      #expect(synced == 0)
      #expect(failed == 0)
      #expect(env.manager.isCurrent(current))
      #expect(env.manager.connectionState == .syncing)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(env.manager.resyncAttemptCount == 0)
      #expect(current.services.isAcceptingRuntimeWork)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
      env.manager.connectionStateEvents.finish()
      await observer.value
      #expect(events.values == Self.initialEvents)
    } catch {
      ended.open()
      env.manager.cancelResyncLoop()
      await resync?.value
      observer.cancel()
      env.manager.connectionStateEvents.finish()
      await observer.value
      await env.close()
      throw error
    }
    await env.close()
  }

  @MainActor
  private final class Activity {
    private var attempts = 0
    private(set) var terminalEnds = 0

    func attempted() {
      attempts += 1
    }

    func didEnd(succeeded: Bool, expectedAttempts: Int, expectedSuccess: Bool) -> Bool {
      guard attempts == expectedAttempts, succeeded == expectedSuccess else { return false }
      terminalEnds += 1
      return true
    }
  }

  @MainActor
  private final class Gate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false
    var isWaiting: Bool {
      continuation != nil
    }

    func wait() async {
      guard !isOpen else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      isOpen = true
      continuation?.resume()
      continuation = nil
    }
  }
}
