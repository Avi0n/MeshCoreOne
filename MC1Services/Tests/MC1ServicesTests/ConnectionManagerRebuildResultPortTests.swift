import Foundation
@testable import MC1Services
@testable import MeshCore
import os
import Testing

@Suite("Manager rebuild result ownership")
@MainActor
struct ConnectionManagerRebuildResultPortTests {
  private static let secondRebuildArrivalTimeout: Duration = .seconds(15)

  enum Outcome: CaseIterable {
    case success
    case failure
    case cancellation
  }

  @Test(.timeLimit(.minutes(1)), arguments: Outcome.allCases)
  func `obsolete second rebuild cannot invoke manager exhaustion against a same-radio successor`(outcome: Outcome) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let deliveries = OSAllocatedUnfairLock<[Task<Void, Never>]>(initialState: [])
    var exhausted: [UUID] = []
    var replacement: Task<ConnectionResult, Error>?
    do {
      _ = try await env.connect(.bluetooth)
      let initial = try #require(env.manager.currentConnection)
      await captureDeliveries(env, into: deliveries)
      env.manager.reconnectionCoordinator.onExhausted = { [weak manager = env.manager] cycle in
        exhausted.append(cycle.id)
        await manager?.finishBLERecoveryFailure(cycle)
      }
      await env.ble.rejectNext(.appStart)
      await env.ble.holdReplies(for: .deviceQuery)
      if outcome == .failure { await env.ble.rejectNext(.deviceQuery) }
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      let loss = try #require(deliveries.withLock { $0.last })
      await loss.value
      let cycle = try #require(env.manager.reconnectionCoordinator.currentCycle)
      await env.ble.simulateReconnected()
      let completion = try #require(deliveries.withLock { $0.last })
      try await waitUntil(timeout: Self.secondRebuildArrivalTimeout, "the failed first rebuild must reach its held second capability result") {
        await env.ble.heldReplyCount(for: .deviceQuery) == 1
      }
      #expect(await env.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count == 3)
      let oldResource = try #require(env.manager.desiredRequest?.attempt?.resource)
      replacement = Task { try await env.connect(.wifi) }
      try await waitUntil("the manual successor must replace the rebuilding request") {
        env.manager.connectionRequestID != cycle.requestID
      }
      #expect(env.manager.currentConnection == nil)
      #expect(!oldResource.binding.isActive)
      #expect(await env.wifi.connectCount == 0)
      if outcome == .cancellation { completion.cancel() }
      await env.ble.releaseReplies(for: .deviceQuery)
      let result = try await #require(replacement).value
      let current = try #require(env.manager.currentConnection)
      let closes = await env.ble.disconnectCount
      await completion.value
      #expect(env.manager.isCurrent(result))
      #expect(env.manager.isCurrent(current))
      #expect(current.device.radioID == initial.device.radioID)
      #expect(current.generation != initial.generation)
      #expect(exhausted.isEmpty)
      #expect(env.manager.consecutiveRebuildFailures == 0)
      #expect(env.manager.reconnectionCoordinator.currentCycle == nil)
      #expect(env.manager.connectionState == .ready)
      #expect(!env.manager.isReconnectionWatchdogRunning)
      #expect(await env.ble.disconnectCount == closes)
      #expect(await oldResource.session.subscriberCountForTest == 0)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
    } catch {
      replacement?.cancel()
      await releaseAndClose(env, deliveries: deliveries)
      _ = await replacement?.result
      throw error
    }
    await releaseAndClose(env, deliveries: deliveries)
  }

  @Test(.timeLimit(.minutes(1)))
  func `current retry exhaustion invokes manager cleanup and permits the next working connection`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let deliveries = OSAllocatedUnfairLock<[Task<Void, Never>]>(initialState: [])
    var exhausted: [UUID] = []
    do {
      _ = try await env.connect(.bluetooth)
      let initial = try #require(env.manager.currentConnection)
      await captureDeliveries(env, into: deliveries)
      env.manager.reconnectionCoordinator.onExhausted = { [weak manager = env.manager] cycle in
        exhausted.append(cycle.id)
        await manager?.finishBLERecoveryFailure(cycle)
      }
      await env.ble.rejectNext(.appStart, count: 2)
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      let loss = try #require(deliveries.withLock { $0.last })
      await loss.value
      let cycle = try #require(env.manager.reconnectionCoordinator.currentCycle)
      await env.ble.simulateReconnected()
      let completion = try #require(deliveries.withLock { $0.last })
      await completion.value
      #expect(exhausted == [cycle.id])
      #expect(env.manager.consecutiveRebuildFailures == 1)
      #expect(env.manager.reconnectionCoordinator.currentCycle == nil)
      #expect(env.manager.currentConnection == nil)
      #expect(env.manager.connectionState == .disconnected)
      #expect(env.manager.isReconnectionWatchdogRunning)
      #expect(await env.ble.isConnected == false)
      #expect(await initial.services.session.subscriberCountForTest == 0)
      let next = try await env.connect(.wifi)
      #expect(env.manager.isCurrent(next))
      let current = try #require(env.manager.currentConnection)
      #expect(current.device.radioID == initial.device.radioID)
      #expect(env.manager.connectionState == .ready)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
    } catch {
      await releaseAndClose(env, deliveries: deliveries)
      throw error
    }
    await releaseAndClose(env, deliveries: deliveries)
  }

  private func captureDeliveries(
    _ env: ConnectionManagerRadioTestHarness,
    into deliveries: OSAllocatedUnfairLock<[Task<Void, Never>]>
  ) async {
    await env.ble.setLifecycleHandler { [weak manager = env.manager] event in
      let worker = Task { @MainActor in
        guard let manager else { return }
        await manager.handleBLELifecycleEvent(event)
      }
      deliveries.withLock { $0.append(worker) }
    }
  }

  private func releaseAndClose(
    _ env: ConnectionManagerRadioTestHarness,
    deliveries: OSAllocatedUnfairLock<[Task<Void, Never>]>
  ) async {
    for task in deliveries.withLock({ $0 }) {
      task.cancel()
    }
    await env.ble.releaseAllGates()
    await env.wifi.releaseAllGates()
    await env.close()
    for task in deliveries.withLock({ $0 }) {
      await task.value
    }
    env.manager.reconnectionCoordinator.onExhausted = nil
  }
}
