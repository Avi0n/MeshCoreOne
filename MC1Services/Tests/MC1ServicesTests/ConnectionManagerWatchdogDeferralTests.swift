import Foundation
@testable import MC1Services
import Testing

@Suite("Bluetooth watchdog pairing deferral", .timeLimit(.minutes(1)))
@MainActor
struct ConnectionManagerWatchdogDeferralTests {
  @Test(arguments: [false, true])
  func `cancelled discovery retains a later usable retry`(cancelTask: Bool) async throws {
    try await withWatchdogFixture { fixture in
      let request = try #require(fixture.manager.desiredRequest)
      let oldLink = try #require(fixture.manager.installedLink)
      try await fixture.openDiscovery()
      let watchdog = try await fixture.loseConnection()
      let generation = fixture.manager.reconnectionWatchdogGeneration
      #expect(fixture.manager.desiredRequest === request)
      #expect(fixture.manager.installedLink == nil)
      #expect(!oldLink.resource.binding.isActive)
      try await fixture.expectStates([.disconnected])

      fixture.clock.advance(by: WatchdogDeferralFixture.initialDelay)
      try await waitUntil(timeout: WatchdogDeferralFixture.observationTimeout, "the held picker tick must settle") {
        fixture.clock.sleeperCount == 1 || !fixture.manager.isReconnectionWatchdogRunning
      }
      #expect(fixture.pairing.isPresenting)
      #expect(fixture.manager.isPairingInProgress)
      #expect(fixture.manager.connectionIntent.wantsConnection)
      #expect(fixture.manager.connectionState == .disconnected)
      #expect(fixture.manager.desiredRequest === request)
      #expect(await fixture.radio.connectCount == 1)
      try #require(fixture.clock.sleeperCount == 1, "Temporary picker deferral must schedule the next retry")
      #expect(fixture.manager.reconnectionWatchdogGeneration == generation)
      try await fixture.expectStates([.disconnected])

      try await fixture.cancelDiscovery(cancelTask: cancelTask)
      #expect(!fixture.pairing.isPresenting)
      #expect(!fixture.manager.isPairingInProgress)
      #expect(fixture.manager.desiredRequest === request)
      #expect(fixture.manager.isReconnectionWatchdogRunning)
      fixture.clock.advance(by: WatchdogDeferralFixture.initialDelay)
      #expect(await fixture.radio.connectCount == 1)
      #expect(fixture.clock.sleeperCount == 1)
      fixture.clock.advance(by: WatchdogDeferralFixture.initialDelay)
      try await fixture.requireReady()
      await watchdog.value

      let current = try #require(fixture.manager.installedLink)
      #expect(current !== oldLink)
      #expect(current.resource !== oldLink.resource)
      #expect(current.requestID != request.id)
      #expect(current.target == .bluetooth(fixture.deviceID))
      #expect(current.device.radioID == oldLink.device.radioID)
      #expect(await fixture.radio.connectCount == 2)
      #expect(fixture.clock.sleeperCount == 0)
      fixture.clock.advance(by: WatchdogDeferralFixture.laterTime)
      #expect(fixture.manager.installedLink === current)
      #expect(await fixture.radio.connectCount == 2)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
      await fixture.finishAndExpectStates([.disconnected, .connecting, .connecting, .connected, .syncing, .ready])
    }
  }

  @Test
  func `a pending health handshake owns one physical attempt`() async throws {
    try await withWatchdogFixture { fixture in
      let oldLink = try #require(fixture.manager.installedLink)
      let watchdog = try await fixture.loseConnection()
      await fixture.radio.holdReplies(for: .appStart)
      fixture.clock.advance(by: WatchdogDeferralFixture.initialDelay)
      try await waitUntil(timeout: WatchdogDeferralFixture.observationTimeout, "health recovery must reach the held handshake") {
        await fixture.radio.heldReplyCount(for: .appStart) == 1
      }
      let request = try #require(fixture.manager.desiredRequest)
      let attempt = try #require(request.attempt)
      let resource = try #require(attempt.resource)
      #expect(request.id != oldLink.requestID)
      #expect(resource !== oldLink.resource)
      #expect(resource.binding.isActive)
      #expect(await fixture.radio.connectCount == 2)
      #expect(fixture.clock.sleeperCount == 0)
      try await fixture.expectStates([.disconnected, .connecting, .connecting, .connected])

      fixture.clock.advance(by: WatchdogDeferralFixture.laterTime)
      #expect(fixture.manager.desiredRequest === request)
      #expect(request.attempt === attempt)
      #expect(attempt.resource === resource)
      #expect(await fixture.radio.heldReplyCount(for: .appStart) == 1)
      #expect(await fixture.radio.connectCount == 2)
      await fixture.radio.releaseReplies(for: .appStart)
      try await fixture.requireReady()
      await watchdog.value

      let current = try #require(fixture.manager.installedLink)
      #expect(current.requestID == request.id)
      #expect(current.resource === resource)
      #expect(current.resource.binding.isActive)
      #expect(await fixture.radio.connectCount == 2)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
      await fixture.finishAndExpectStates([.disconnected, .connecting, .connecting, .connected, .syncing, .ready])
    }
  }

  @Test(arguments: [false, true])
  func `a sleeping old timer cannot alter a successor`(sameRadio: Bool) async throws {
    try await withWatchdogFixture { fixture in
      let oldRequest = try #require(fixture.manager.desiredRequest)
      let watchdog = try await fixture.loseConnection()
      let targetID = sameRadio ? fixture.deviceID : UUID()
      await fixture.radio.setPublicKey(Data(repeating: sameRadio ? 1 : 2, count: 32), for: targetID)
      let result = try await fixture.manager.connect(to: targetID, forceFullSync: true, forceReconnect: true)
      await watchdog.value
      let current = try #require(fixture.manager.installedLink)
      let physicalOrigin = await fixture.radio.lifecycleOrigin
      let disconnectCount = await fixture.radio.disconnectCount
      #expect(current.requestID != oldRequest.id)
      #expect(fixture.manager.isCurrent(result))
      #expect(current.target == .bluetooth(targetID))
      #expect(current.resource.binding.isActive)
      #expect(fixture.clock.sleeperCount == 0)

      fixture.clock.advance(by: WatchdogDeferralFixture.laterTime)
      #expect(fixture.manager.installedLink === current)
      #expect(fixture.manager.connectionRequestID == current.requestID)
      #expect(fixture.manager.connectionState == .ready)
      #expect(current.resource.binding.isActive)
      #expect(await fixture.radio.lifecycleOrigin == physicalOrigin)
      #expect(await fixture.radio.connectCount == 2)
      #expect(await fixture.radio.disconnectCount == disconnectCount)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
      await fixture.finishAndExpectStates([.disconnected, .connecting, .connecting, .connected, .syncing, .ready])
    }
  }

  @Test
  func `explicit disconnect revokes a sleeping retry`() async throws {
    try await withWatchdogFixture { fixture in
      let watchdog = try await fixture.loseConnection()
      let receipt = await fixture.manager.disconnect()
      await watchdog.value
      let requestID = fixture.manager.connectionRequestID
      let disconnectCount = await fixture.radio.disconnectCount
      fixture.clock.advance(by: WatchdogDeferralFixture.laterTime)

      #expect(fixture.manager.isCurrent(receipt))
      #expect(fixture.manager.connectionRequestID == requestID)
      #expect(!fixture.manager.connectionIntent.wantsConnection)
      #expect(fixture.manager.connectionState == .disconnected)
      #expect(fixture.manager.installedLink == nil)
      #expect(!fixture.manager.isReconnectionWatchdogRunning)
      #expect(fixture.clock.sleeperCount == 0)
      #expect(await fixture.radio.connectCount == 1)
      #expect(await fixture.radio.disconnectCount == disconnectCount)
      await fixture.finishAndExpectStates([.disconnected, .disconnected])
    }
  }
}
