import Foundation
@testable import MC1Services
@testable import MeshCore
import os
import Testing

@Suite("BLE attempt retirement ownership")
@MainActor
struct ConnectionManagerBLEAttemptOwnershipTests {
  @Test(.timeLimit(.minutes(1)))
  func `replacement joins a failed unpublished candidate until its actual send settles`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    await env.ble.holdNextSendCompletion()
    let first = Task { try await env.connect(.bluetooth) }
    var replacement: Task<ConnectionResult, Error>?
    do {
      try await waitUntil("candidate appStart should remain physically in flight") { await env.ble.sendCompletionIsHeld }
      let request = try #require(env.manager.desiredRequest)
      let attempt = try #require(request.attempt)
      let oldSession = try #require(attempt.resource?.session)
      var cleanupEntered = false
      env.manager.testCandidateRetirementHandler = { binding in
        if binding === attempt.binding { cleanupEntered = true }
      }
      first.cancel()
      try await waitUntil("cancelled setup should enter captured candidate retirement") { cleanupEntered }
      #expect(request.attempt === attempt)
      #expect(!attempt.binding.isActive)
      #expect(env.manager.currentConnection == nil)
      let target = UUID()
      let next = Task { try await env.manager.connect(to: target, forceReconnect: true) }
      replacement = next
      try await waitUntil("replacement should be admitted behind actual candidate drain") {
        env.manager.desiredRequest?.target.deviceID == target
      }
      #expect(await env.ble.connectCount == 1)
      #expect(await env.ble.sendCompletionIsHeld)
      await env.ble.releaseSendCompletion()
      _ = await first.result
      guard case let .operational(current) = try await next.value else { throw ConnectionError.notConnected }
      #expect(current.device.id == target)
      #expect(env.manager.isCurrent(current))
      #expect(await oldSession.subscriberCountForTest == 0)
      _ = try await current.services.session.getTime()
    } catch {
      first.cancel()
      replacement?.cancel()
      env.manager.testCandidateRetirementHandler = nil
      await env.ble.releaseAllGates()
      await env.close()
      _ = await first.result
      _ = await replacement?.result
      throw error
    }
    env.manager.testCandidateRetirementHandler = nil
    await env.close()
  }

  @Test(.timeLimit(.minutes(1)))
  func `late cleanup from an old rebuild cannot retire the next attempt in the same request`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let metadata = Gate()
    let cleanup = Gate()
    let deliveries = OSAllocatedUnfairLock<[Task<Void, Never>]>(initialState: [])
    do {
      _ = try await env.connect(.bluetooth)
      let requestID = env.manager.connectionRequestID
      await env.ble.setLifecycleHandler { [weak manager = env.manager] event in
        let worker = Task<Void, Never> { @MainActor in
          guard let manager else { return }
          await manager.handleBLELifecycleEvent(event)
        }
        deliveries.withLock { $0.append(worker) }
      }
      var selections = 0
      env.manager.testConnectionSelectionHandler = { _ in
        selections += 1
        if selections == 1 { await metadata.wait() }
      }

      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      let firstLoss = try #require(deliveries.withLock { $0.last })
      await firstLoss.value
      await env.ble.simulateReconnected()
      let firstCompletion = try #require(deliveries.withLock { $0.last })
      try await waitUntil("first rebuild should hold accepted metadata") { metadata.isWaiting }
      let oldAttempt = try #require(env.manager.desiredRequest?.attempt)
      env.manager.testCandidateRetirementHandler = { binding in
        if binding === oldAttempt.binding { await cleanup.wait() }
      }

      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      let secondLoss = try #require(deliveries.withLock { $0.last })
      await secondLoss.value
      await env.ble.simulateReconnected()
      let secondCompletion = try #require(deliveries.withLock { $0.last })
      #expect(await env.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count == 2)
      metadata.open()

      try await waitUntil("old cleanup should park while the next attempt installs") { cleanup.isWaiting }
      try await waitUntil("second cycle should complete the real handshake and sync") { env.manager.connectionState == .ready }
      let current = try #require(env.manager.currentConnection)
      let currentBinding = try #require(env.manager.installedLink?.resource.binding)
      #expect(env.manager.connectionRequestID == requestID)
      #expect(currentBinding !== oldAttempt.binding)
      #expect(currentBinding.isActive)
      cleanup.open()
      await firstCompletion.value
      await secondCompletion.value
      #expect(env.manager.isCurrent(current))
      #expect(currentBinding.isActive)
      #expect(await env.ble.connectCount == 1)
      _ = try await current.services.session.getTime()
    } catch {
      metadata.open()
      cleanup.open()
      env.manager.testConnectionSelectionHandler = nil
      env.manager.testCandidateRetirementHandler = nil
      await env.close()
      for worker in deliveries.withLock({ $0 }) {
        await worker.value
      }
      throw error
    }
    env.manager.testConnectionSelectionHandler = nil
    env.manager.testCandidateRetirementHandler = nil
    await env.close()
    for worker in deliveries.withLock({ $0 }) {
      await worker.value
    }
  }

  @Test(.timeLimit(.minutes(1)))
  func `caller cancelled before publication lets the same request recover`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let selection = Gate()
    let deliveries = OSAllocatedUnfairLock<[Task<Void, Never>]>(initialState: [])
    var connecting: Task<ConnectionResult, Error>?
    do {
      try await installLifecycleDeliveries(env, deliveries: deliveries)
      env.manager.testConnectionSelectionHandler = { _ in await selection.wait() }
      connecting = Task { try await env.connect(.bluetooth) }
      try await waitUntil("selection should hold the unpublished attempt") { selection.isWaiting }
      let requestID = try #require(env.manager.connectionRequestID)
      #expect(env.manager.desiredRequest?.committedGeneration == nil)
      let appStartsAtCancel = await env.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count
      connecting?.cancel()
      try await waitUntil("caller cancellation should reach the admission worker") {
        env.manager.desiredRequest?.worker?.isCancelled == true
      }
      selection.open()
      let outcome = try await #require(connecting).value
      if case .cancelled = outcome {} else { Issue.record("Cancelled caller returned another outcome") }
      #expect(env.manager.connectionRequestID == requestID)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(env.manager.desiredRequest?.committedGeneration == nil)

      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await #require(deliveries.withLock { $0.last }).value
      await env.ble.simulateReconnected()
      try await #require(deliveries.withLock { $0.last }).value
      try await waitUntil("same request recovery should publish") { env.manager.connectionState == .ready }
      #expect(env.manager.connectionRequestID == requestID)
      #expect(env.manager.services != nil)
      #expect(await env.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count > appStartsAtCancel)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      selection.open()
      connecting?.cancel()
      env.manager.testConnectionSelectionHandler = nil
      await env.close()
      _ = await connecting?.result
      for worker in deliveries.withLock({ $0 }) {
        await worker.value
      }
      throw error
    }
    env.manager.testConnectionSelectionHandler = nil
    await env.close()
    for worker in deliveries.withLock({ $0 }) {
      await worker.value
    }
  }

  @Test(.timeLimit(.minutes(1)))
  func `caller cancellation after the next attempt is installed keeps that binding`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let admissionSelection = Gate()
    let recoverySelection = Gate()
    let cancelHop = Gate()
    let deliveries = OSAllocatedUnfairLock<[Task<Void, Never>]>(initialState: [])
    var connecting: Task<ConnectionResult, Error>?
    var selections = 0
    do {
      try await installLifecycleDeliveries(env, deliveries: deliveries)
      env.manager.testConnectionSelectionHandler = { _ in
        selections += 1
        if selections == 1 { await admissionSelection.wait() }
        if selections == 2 { await recoverySelection.wait() }
      }
      env.manager.testAdmissionCancellationHandler = { await cancelHop.wait() }
      connecting = Task { try await env.connect(.bluetooth) }
      try await waitUntil("admission should hold before publication") { admissionSelection.isWaiting }
      let requestID = try #require(env.manager.connectionRequestID)
      connecting?.cancel()
      try await waitUntil("cancel hop should pause before it revokes") { cancelHop.isWaiting }
      let admissionBinding = try #require(env.manager.desiredRequest?.admissionBinding)
      admissionSelection.open()
      let outcome = try await #require(connecting).value
      if case .cancelled = outcome {} else { Issue.record("Cancelled caller returned another outcome") }
      #expect(env.manager.desiredRequest?.committedGeneration == nil)
      let appStartsBeforeRecovery = await env.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count

      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await #require(deliveries.withLock { $0.last }).value
      await env.ble.simulateReconnected()
      let completion = try #require(deliveries.withLock { $0.last })
      try await waitUntil("recovery should install the next attempt before publication") { recoverySelection.isWaiting }
      let recoveryBinding = try #require(env.manager.desiredRequest?.binding)
      #expect(recoveryBinding !== admissionBinding)
      #expect(recoveryBinding.isActive)
      #expect(env.manager.desiredRequest?.committedGeneration == nil)
      cancelHop.open()
      try await waitUntil("cancel hop should revoke the admission binding") {
        env.manager.testAdmissionCancellationFinished
      }
      #expect(recoveryBinding.isActive)
      #expect(env.manager.connectionRequestID == requestID)
      recoverySelection.open()
      await completion.value
      try await waitUntil("same request should reach ready with the recovery binding") {
        env.manager.connectionState == .ready
      }
      #expect(env.manager.connectionRequestID == requestID)
      #expect(recoveryBinding.isActive)
      #expect(env.manager.services != nil)
      #expect(await env.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count > appStartsBeforeRecovery)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      admissionSelection.open()
      recoverySelection.open()
      cancelHop.open()
      connecting?.cancel()
      env.manager.testConnectionSelectionHandler = nil
      env.manager.testAdmissionCancellationHandler = nil
      await env.close()
      _ = await connecting?.result
      for worker in deliveries.withLock({ $0 }) {
        await worker.value
      }
      throw error
    }
    env.manager.testConnectionSelectionHandler = nil
    env.manager.testAdmissionCancellationHandler = nil
    await env.close()
    for worker in deliveries.withLock({ $0 }) {
      await worker.value
    }
  }

  private func installLifecycleDeliveries(
    _ env: ConnectionManagerRadioTestHarness,
    deliveries: OSAllocatedUnfairLock<[Task<Void, Never>]>
  ) async throws {
    try await waitUntil("default lifecycle handler should be installed") { await env.ble.hasLifecycleHandler }
    await env.ble.setLifecycleHandler { [weak manager = env.manager] event in
      let worker = Task<Void, Never> { @MainActor in
        guard let manager else { return }
        await manager.handleBLELifecycleEvent(event)
      }
      deliveries.withLock { $0.append(worker) }
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
      let waiting = continuation
      continuation = nil
      waiting?.resume()
    }
  }
}
