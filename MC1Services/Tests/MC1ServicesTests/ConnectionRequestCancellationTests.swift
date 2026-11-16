import Foundation
@testable import MC1Services
import os
import Testing

@Suite("Connection request cancellation")
@MainActor
struct ConnectionRequestCancellationTests {
  @Test
  func `a caller cancelled before admission creates no request`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = IdentityRecoveryFixture.Gate()
    do {
      _ = try await env.connect(.bluetooth)
      let requestID = env.manager.connectionRequestID
      let state = env.manager.connectionState
      let intent = env.manager.connectionIntent
      let generation = env.manager.currentConnection?.generation
      let dials = await env.ble.connectCount
      let teardowns = env.manager.connectionTeardownCount
      let task = Task { () throws -> ConnectionResult in
        await gate.wait()
        return try await env.manager.connect(to: UUID(), forceReconnect: true)
      }
      task.cancel()
      gate.open()
      await #expect(throws: CancellationError.self) { try await task.value }
      #expect(env.manager.connectionRequestID == requestID)
      #expect(env.manager.connectionState == state)
      #expect(env.manager.connectionIntent == intent)
      #expect(env.manager.currentConnection?.generation == generation)
      #expect(await env.ble.connectCount == dials)
      #expect(env.manager.connectionTeardownCount == teardowns)
    } catch {
      gate.open()
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `cancellation while metadata is held never starts the attempt`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = IdentityRecoveryFixture.Gate()
    let entered = OSAllocatedUnfairLock(initialState: false)
    var connecting: Task<ConnectionResult, Error>?
    do {
      _ = try await env.connect(.bluetooth)
      let held = env.manager.enqueueMetadata { _ in await gate.wait() }
      try await waitUntil("metadata predecessor should be held") { gate.arrived }
      let target = UUID()
      connecting = Task {
        try await env.manager.requestConnection(
          targets: [.bluetooth(target)], origin: .explicit, forceFullSync: true, forceReconnect: true,
          operation: { _ in
            entered.withLock { $0 = true }
            return .superseded
          }
        )
      }
      try await waitUntil("replacement should be admitted behind the held metadata") {
        env.manager.desiredRequest?.target.deviceID == target && env.manager.connectionTeardownCount == 0
      }
      let dials = await env.ble.connectCount
      let disconnects = await env.ble.disconnectCount
      connecting?.cancel()
      try await waitUntil("cancellation should reach the admitted worker") {
        env.manager.desiredRequest?.worker?.isCancelled == true
      }
      #expect(entered.withLock { $0 } == false)
      gate.open()
      let outcome = try await connecting?.value
      if case .cancelled = outcome {} else { Issue.record("Held metadata cancellation returned another outcome") }
      #expect(entered.withLock { $0 } == false)
      #expect(env.manager.connectionState == .disconnected)
      #expect(env.manager.currentConnection == nil)
      #expect(env.manager.desiredRequest?.attempt == nil)
      #expect(env.manager.desiredRequest?.target.deviceID == target)
      #expect(env.manager.connectionIntent == .wantsConnection(forceFullSync: true))
      #expect(await env.ble.connectCount == dials)
      #expect(await env.ble.disconnectCount == disconnects)
      #expect(env.manager.connectionTeardownCount == 0)
      _ = try await held.value
    } catch {
      gate.open()
      _ = await connecting?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `a cancelled request cannot publish into the choice that replaced it`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = IdentityRecoveryFixture.Gate()
    var first: Task<ConnectionResult, Error>?
    var second: Task<ConnectionResult, Error>?
    do {
      _ = try await env.connect(.bluetooth)
      var losses: [UUID] = []
      env.manager.onConnectionLost = { losses.append($0.requestID) }
      env.manager.testConnectionTeardownHandler = { await gate.wait() }
      let targetA = UUID()
      first = Task { try await env.manager.connect(to: targetA, forceFullSync: true, forceReconnect: true) }
      try await waitUntil("first replacement should park in retirement") {
        gate.arrived && env.manager.desiredRequest?.target.deviceID == targetA
      }
      let dials = await env.ble.connectCount
      first?.cancel()
      try await waitUntil("first worker should be cancelled while it is still current") {
        env.manager.desiredRequest?.worker?.isCancelled == true
          && env.manager.desiredRequest?.target.deviceID == targetA
      }
      let targetB = UUID()
      second = Task { try await env.manager.connect(to: targetB, forceFullSync: false, forceReconnect: true) }
      try await waitUntil("successor should own the request") {
        env.manager.desiredRequest?.target.deviceID == targetB
      }
      let successorRequest = try #require(env.manager.connectionRequestID)
      let successorIntent = env.manager.connectionIntent
      let lossesAtSuccessor = losses
      gate.open()
      env.manager.testConnectionTeardownHandler = nil
      _ = try await first?.value
      #expect(env.manager.connectionRequestID == successorRequest)
      #expect(env.manager.connectionIntent == successorIntent)
      #expect(env.manager.desiredRequest?.target.deviceID == targetB)
      #expect(losses == lossesAtSuccessor)
      #expect(!losses.contains(successorRequest))
      let outcome = try await second?.value
      if case .operational = outcome {} else { Issue.record("Successor did not become operational") }
      #expect(env.manager.connectedDevice?.id == targetB)
      #expect(env.manager.currentConnection?.generation != nil)
      #expect(await env.ble.connectCount == dials + 1)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      gate.open()
      env.manager.testConnectionTeardownHandler = nil
      _ = await first?.result
      _ = await second?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `cancelling the current successor leaves the superseded worker silent`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = IdentityRecoveryFixture.Gate()
    var first: Task<ConnectionResult, Error>?
    var second: Task<ConnectionResult, Error>?
    do {
      _ = try await env.connect(.bluetooth)
      var losses: [UUID] = []
      env.manager.onConnectionLost = { losses.append($0.requestID) }
      env.manager.testConnectionTeardownHandler = { await gate.wait() }
      let targetA = UUID()
      first = Task { try await env.manager.connect(to: targetA, forceFullSync: false, forceReconnect: true) }
      try await waitUntil("first replacement should park in retirement") {
        gate.arrived && env.manager.desiredRequest?.target.deviceID == targetA
      }
      let dials = await env.ble.connectCount
      let targetB = UUID()
      second = Task { try await env.manager.connect(to: targetB, forceFullSync: true, forceReconnect: true) }
      try await waitUntil("successor should own the request before cancellation") {
        env.manager.desiredRequest?.target.deviceID == targetB
      }
      let admitted = try #require(env.manager.connectionRequestID)
      second?.cancel()
      try await waitUntil("successor cancellation should reach its worker") {
        env.manager.desiredRequest?.id == admitted && env.manager.desiredRequest?.worker?.isCancelled == true
      }
      gate.open()
      env.manager.testConnectionTeardownHandler = nil
      let firstOutcome = try await first?.value
      let secondOutcome = try await second?.value
      if case .superseded = firstOutcome {} else { Issue.record("Superseded worker returned another outcome") }
      if case .cancelled = secondOutcome {} else { Issue.record("Cancelled successor returned another outcome") }
      #expect(env.manager.connectionState == .disconnected)
      #expect(env.manager.currentConnection == nil)
      #expect(env.manager.desiredRequest?.attempt == nil)
      #expect(env.manager.desiredRequest?.target.deviceID == targetB)
      #expect(env.manager.connectionIntent == .wantsConnection(forceFullSync: true))
      #expect(env.manager.connectionTeardownCount == 0)
      #expect(losses == [admitted])
      #expect(await env.ble.connectCount == dials)
    } catch {
      gate.open()
      env.manager.testConnectionTeardownHandler = nil
      _ = await first?.result
      _ = await second?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `cancellation after link commitment leaves the link usable`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = IdentityRecoveryFixture.Gate()
    var connecting: Task<ConnectionResult, Error>?
    do {
      var held = false
      env.manager.testPostSetupResultHandler = { _ in
        guard !held else { return }
        held = true
        await gate.wait()
      }
      connecting = Task { try await env.connect(.bluetooth) }
      try await waitUntil("setup should reach the committed link") { gate.arrived }
      let generation = try #require(env.manager.currentConnection?.generation)
      let dials = await env.ble.connectCount
      let disconnects = await env.ble.disconnectCount
      connecting?.cancel()
      try await Task.sleep(for: Self.cancelDeliveryWindow)
      #expect(env.manager.desiredRequest?.committedGeneration == generation)
      #expect(env.manager.desiredRequest?.worker?.isCancelled != true)
      #expect(env.manager.currentConnection?.generation == generation)
      gate.open()
      env.manager.testPostSetupResultHandler = nil
      let outcome = try await connecting?.value
      if case .cancelled = outcome {} else { Issue.record("Caller cancellation changed the committed result surface") }
      #expect(env.manager.currentConnection?.generation == generation)
      #expect(env.manager.connectionState == .ready)
      #expect(env.manager.connectionTeardownCount == 0)
      #expect(await env.ble.connectCount == dials)
      #expect(await env.ble.disconnectCount == disconnects)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      gate.open()
      env.manager.testPostSetupResultHandler = nil
      _ = await connecting?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  private static let cancelDeliveryWindow: Duration = .milliseconds(50)
}
