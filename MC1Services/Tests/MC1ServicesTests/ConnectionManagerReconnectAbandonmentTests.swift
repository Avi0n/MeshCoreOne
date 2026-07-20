import Foundation
@testable import MC1Services
import Testing

/// Tests for the reconnect-abandonment paths: while `connectionIntent.wantsConnection`
/// and the BLE state machine is auto-reconnecting, no teardown path may disconnect the
/// transport (that cancels the OS pending connect, which never expires on its own), and
/// every UI-level abandonment must leave the reconnection watchdog running.
@Suite("ConnectionManager Reconnect Abandonment Tests")
@MainActor
struct ConnectionManagerReconnectAbandonmentTests {
  // MARK: - handleReconnectionFailure

  @Test
  func `reconnection failure preserves a live link and keeps the watchdog armed`() async throws {
    // A rebuild failure is an app-layer failure. Severing a healthy link cancels
    // the OS pending connect and the live GATT subscription — the only wake
    // sources that survive process suspension. Preserve while budget remains.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await env.manager.handleReconnectionFailure()

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.isReconnectionWatchdogRunning)
    #expect(env.manager.consecutiveRebuildFailures == 1)
    #expect(env.manager.activeReconnectDeviceID == nil)
  }

  @Test
  func `reconnection failure without a live link still disconnects the transport`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsAutoReconnecting(false)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await env.manager.handleReconnectionFailure()

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `reconnection failure after user disconnect severs a held link and leaves the watchdog stopped`() async throws {
    // Intent gate is load-bearing: with holdsLink true and userDisconnected,
    // preserve must not leave the link up. Vacuous if holdsLink is false.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .userDisconnected
    )

    await env.manager.handleReconnectionFailure()

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(!env.manager.isReconnectionWatchdogRunning)
    #expect(env.manager.consecutiveRebuildFailures == 0)
  }

  @Test
  func `preserve budget exhausts and severs the link`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    env.manager.startReconnectionWatchdog()
    let armedGeneration = env.manager.reconnectionWatchdogGeneration
    #expect(env.manager.isReconnectionWatchdogRunning)

    env.manager.setTestState(connectionState: .connecting)

    let budget = ConnectionManager.maxRebuildFailuresPreservingLink
    for _ in 0..<budget {
      await env.manager.handleReconnectionFailure()
      #expect(await env.transport.disconnectInvocations == 0)
    }

    await env.manager.handleReconnectionFailure()
    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.consecutiveRebuildFailures == budget + 1)
    #expect(env.manager.lastDisconnectDiagnostic?.contains("preserveBudgetExhausted") == true)
    #expect(env.manager.isReconnectionWatchdogRunning)
    // Exhaustion uses notifyConnectionLost → cancel+restart (generation advanced).
    #expect(env.manager.reconnectionWatchdogGeneration > armedGeneration)
  }

  @Test
  func `preserve with autoReconnecting alone keeps the link`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(false)
    await env.stateMachine.setStubbedIsAutoReconnecting(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await env.manager.handleReconnectionFailure()

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `switchDevice success path refills preserve budget via named reset site`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    let budget = ConnectionManager.maxRebuildFailuresPreservingLink
    for _ in 0...budget {
      await env.manager.handleReconnectionFailure()
    }
    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.consecutiveRebuildFailures == budget + 1)

    // Semantics of the helper used by switchDevice after promoteToReady.
    env.manager.resetPreserveBudgetAfterDeviceSwitch()
    #expect(env.manager.consecutiveRebuildFailures == 0)

    await env.manager.handleReconnectionFailure()
    #expect(await env.transport.disconnectInvocations == 1)
  }

  @Test
  func `resetPreserveBudgetAfterDeviceSwitch has exactly one production call site in switchDevice`() throws {
    // Removing the sole production call fails this test. Walk production sources
    // and require exactly one non-definition invocation of the reset helper
    // (the switchDevice success path after promoteToReady).
    let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    let sourcesRoot = testsDir
      .deletingLastPathComponent() // MC1ServicesTests
      .deletingLastPathComponent() // Tests
      .appendingPathComponent("Sources/MC1Services", isDirectory: true)

    let callPattern = "resetPreserveBudgetAfterDeviceSwitch()"
    let definitionPattern = "func resetPreserveBudgetAfterDeviceSwitch()"
    var callSites: [(relativePath: String, line: String)] = []

    let enumerator = FileManager.default.enumerator(
      at: sourcesRoot,
      includingPropertiesForKeys: [.isRegularFileKey],
      options: [.skipsHiddenFiles]
    )
    while let item = enumerator?.nextObject() as? URL {
      guard item.pathExtension == "swift" else { continue }
      let text = try String(contentsOf: item, encoding: .utf8)
      let relative = item.path.replacingOccurrences(of: sourcesRoot.path + "/", with: "")
      for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard trimmed.contains(callPattern) else { continue }
        if trimmed.contains(definitionPattern) { continue }
        // Skip doc comments that mention the symbol.
        if trimmed.hasPrefix("//") || trimmed.hasPrefix("///") { continue }
        callSites.append((relative, String(trimmed)))
      }
    }

    #expect(callSites.count == 1, "Expected one production call site, found \(callSites)")
    let site = try #require(callSites.first)
    #expect(site.relativePath.hasSuffix("Connection/ConnectionManager+Lifecycle.swift"))
    #expect(site.line.contains(callPattern))
  }

  @Test
  func `watchdog natural exit nils the task and preserve re-arms`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    // Short first sleep so the intent/state guard can fire without a 30s wait.
    // Does not call stopReconnectionWatchdog — that is the cancel path.
    env.manager.testWatchdogInitialDelay = .milliseconds(30)
    env.manager.startReconnectionWatchdog()
    let armedGeneration = env.manager.reconnectionWatchdogGeneration
    #expect(env.manager.isReconnectionWatchdogRunning)

    // Guard-exit: intent no longer wants connection when the sleep completes.
    env.manager.setTestState(connectionIntent: .userDisconnected)
    try await Task.sleep(for: .milliseconds(80))

    // Natural-exit defer must nil the finished Task (not isCancelled).
    #expect(!env.manager.isReconnectionWatchdogRunning)
    #expect(env.manager.reconnectionWatchdogGeneration == armedGeneration)

    env.manager.testWatchdogInitialDelay = nil
    env.manager.setTestState(
      connectionState: .connecting,
      connectionIntent: .wantsConnection()
    )
    await env.manager.handleReconnectionFailure()

    #expect(env.manager.isReconnectionWatchdogRunning)
    #expect(env.manager.reconnectionWatchdogGeneration > armedGeneration)
    #expect(await env.transport.disconnectInvocations == 0)
  }

  @Test
  func `user disconnect does not advance the preserve budget`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .userDisconnected
    )

    await env.manager.handleReconnectionFailure()

    #expect(env.manager.consecutiveRebuildFailures == 0)
  }

  @Test
  func `recordConnectionSuccess refills the preserve budget`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    let budget = ConnectionManager.maxRebuildFailuresPreservingLink
    for _ in 0...budget {
      await env.manager.handleReconnectionFailure()
    }
    #expect(await env.transport.disconnectInvocations == 1)

    // Reset via recordConnectionSuccess (must sit above the .closed early return).
    env.manager.recordConnectionSuccess()
    #expect(env.manager.consecutiveRebuildFailures == 0)

    // Further failure still preserves.
    await env.manager.handleReconnectionFailure()
    #expect(await env.transport.disconnectInvocations == 1)
  }

  @Test
  func `preserve failure arms a live watchdog without cancel-restarting an existing one`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    env.manager.startReconnectionWatchdog()
    let firstGeneration = env.manager.reconnectionWatchdogGeneration
    #expect(env.manager.isReconnectionWatchdogRunning)

    env.manager.setTestState(connectionState: .connecting)
    await env.manager.handleReconnectionFailure()

    // Preserve must not cancel+restart (generation would advance via stop).
    #expect(env.manager.reconnectionWatchdogGeneration == firstGeneration)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }

  // MARK: - notifyConnectionLost

  @Test
  func `connection-lost notification while wanting connection arms the watchdog`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: TransportType?.none,
      connectionIntent: .wantsConnection()
    )

    await manager.notifyConnectionLost()

    #expect(manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `connection-lost notification after user disconnect does not arm the watchdog`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: TransportType?.none,
      connectionIntent: .userDisconnected
    )

    await manager.notifyConnectionLost()

    #expect(!manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `connection-lost notification on WiFi transport does not arm the BLE watchdog`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )

    await manager.notifyConnectionLost()

    #expect(!manager.isReconnectionWatchdogRunning)
  }

  // MARK: - connect(to:forceReconnect:) break-glass

  @Test
  func `forceReconnect connect for an abandoned reconnect cycle tears down the pending connect and attempts fresh`() async throws {
    // The pill already flipped to "Disconnected" (UI abandonment) but the coordinator's
    // reconnect cycle survives because the OS pending connect never resolved. A forced
    // tap must not re-defer into the same stuck wait.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    // The mock pairing registry has no paired accessories, so the fresh attempt
    // fails fast with the real "device not found" error instead of hanging - proof
    // the call reached a genuine connect attempt rather than deferring.
    await #expect(throws: ConnectionError.self) {
      try await env.manager.connect(to: deviceID, forceReconnect: true)
    }

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == nil)
    #expect(env.manager.connectionState == .disconnected)
  }

  @Test
  func `forceReconnect connect while still connecting keeps deferring`() async throws {
    // The pill still shows "Connecting..." - the first tap case. Must keep restarting
    // the timeout rather than tearing down a reconnect the UI hasn't abandoned yet.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    try await env.manager.connect(to: deviceID, forceReconnect: true)

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)
    #expect(env.manager.connectionState == .connecting)
  }

  @Test
  func `non-force connect for an abandoned reconnect cycle still defers`() async throws {
    // Background/unattended reconnects must keep the full indefinite wait; only a
    // user-initiated (forceReconnect) tap gets the break-glass path.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    try await env.manager.connect(to: deviceID, forceReconnect: false)

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)
    #expect(env.manager.connectionState == .disconnected)
  }

  @Test
  func `forceReconnect connect while a session rebuild is in flight for the device defers instead of breaking glass`() async throws {
    // A live session rebuild is progress, not a stuck wait: tearing down the
    // transport under it would destroy a reconnect that is about to succeed.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection(),
      sessionRebuildDeviceID: deviceID
    )

    try await env.manager.connect(to: deviceID, forceReconnect: true)

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)
    #expect(env.manager.connectionState == .disconnected)
  }

  @Test
  func `forceReconnect connect while transport is stuck auto-reconnecting to the same device breaks glass`() async throws {
    // Covers the second deferral branch: the coordinator's cycle was already cleared
    // (e.g. by UI timeout) but the state machine itself is still mid auto-reconnect
    // for this same device.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    await env.stateMachine.setStubbedIsAutoReconnecting(true)
    await env.stateMachine.setStubbedConnectedDeviceID(deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await #expect(throws: ConnectionError.self) {
      try await env.manager.connect(to: deviceID, forceReconnect: true)
    }

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.connectionState == .disconnected)
  }

  // MARK: - Single-flight claims

  @Test
  func `overlapping health checks on a connected missing stack rebuild once`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.testLastConnectedDeviceID = deviceID
    env.manager.setTestState(
      connectionState: .disconnected,
      services: .some(nil),
      session: .some(nil),
      connectedDevice: .some(nil),
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    var rebuildCount = 0
    env.manager.rebuildSessionForHealthCheckOverride = { _ in
      rebuildCount += 1
      try await Task.sleep(for: .milliseconds(50))
      throw ConnectionError.initializationFailed("test rebuild fail")
    }

    async let first: Void = env.manager.checkBLEConnectionHealth()
    async let second: Void = env.manager.checkBLEConnectionHealth()
    _ = await (first, second)

    #expect(rebuildCount == 1)
    // Failure path preserves the link under budget.
    #expect(await env.transport.disconnectInvocations == 0)
  }

  @Test
  func `coordinator cycle claim blocks health rebuild during retry gap`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.testLastConnectedDeviceID = deviceID
    env.manager.setTestState(
      connectionState: .disconnected,
      services: .some(nil),
      session: .some(nil),
      connectedDevice: .some(nil),
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    // Claim via the real entry path so reconnectingDeviceID is set the same way
    // handleReconnectionComplete keeps it across first-fail → sleep.
    await env.manager.reconnectionCoordinator.handleEnteringAutoReconnect(deviceID: deviceID)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)

    // After entry, state is .connecting; health path for connected transport needs
    // BLE connected + missing stack. Keep the claim and flip UI to disconnected
    // while SM stays connected so health would rebuild if unclaimed.
    env.manager.setTestState(connectionState: .disconnected)

    var rebuildCount = 0
    env.manager.rebuildSessionForHealthCheckOverride = { _ in
      rebuildCount += 1
    }

    await env.manager.checkBLEConnectionHealth()

    #expect(env.manager.activeReconnectDeviceID == deviceID)
    #expect(rebuildCount == 0)
  }
}
