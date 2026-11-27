import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("LoginTimeoutConfig Tests")
struct LoginTimeoutConfigTests {
  private func makeSentInfo(route: UInt8 = 0, timeoutMs: UInt32) -> MessageSentInfo {
    MessageSentInfo(route: route, expectedAck: Data([0x00]), suggestedTimeoutMs: timeoutMs)
  }

  @Test(arguments: [UInt8(0x00), 0x40, 0x80])
  func `Direct 0-hop paths use base timeout regardless of mode bits`(pathLength: UInt8) {
    let timeout = LoginTimeoutConfig.timeout(forPathLength: pathLength)
    #expect(timeout == .seconds(5))
  }

  @Test
  func `Mode 1 with 3 hops computes timeout from hop count`() {
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x43)
    #expect(timeout == .seconds(35))
  }

  @Test
  func `Flood routing (0xFF) budgets for the worst case`() {
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 0xFF)
    #expect(timeout == LoginTimeoutConfig.maximumTimeout)
    #expect(timeout == .seconds(40))
  }

  @Test
  func `Timeout is capped at 40s maximum`() {
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 6)
    #expect(timeout == .seconds(40))
  }

  @Test
  func `Direct login with nonzero firmware hint avoids hop floor inflation`() {
    let shortHint = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 1000),
      pathLength: 0x43
    )
    let scaledHint = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 6560),
      pathLength: 2
    )
    let clampedHint = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 25000),
      pathLength: 0
    )

    #expect(shortHint == .seconds(5))
    #expect(scaledHint == .milliseconds(16400))
    #expect(clampedHint == .seconds(40))
  }

  @Test
  func `Flood login uses 20s floor and 40s cap`() {
    let fastFlood = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(route: MessageSentInfo.floodRoute, timeoutMs: 5200),
      pathLength: 0xFF
    )
    let midFlood = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(route: MessageSentInfo.floodRoute, timeoutMs: 12800),
      pathLength: 0
    )
    let slowFlood = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 28900),
      pathLength: 0xFF
    )

    #expect(fastFlood == .seconds(20))
    #expect(midFlood == .seconds(32))
    #expect(slowFlood == .seconds(40))
  }

  @Test
  func `Zero-hint login falls back to LoginTimeoutConfig capped at 40s`() {
    let zeroHop = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 0),
      pathLength: 0
    )
    let floodRoute = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(route: MessageSentInfo.floodRoute, timeoutMs: 0),
      pathLength: 0
    )

    #expect(zeroHop == .seconds(5))
    #expect(floodRoute == .seconds(40))
  }

  @Test
  func `CLI timeout preserves fireAndForgetCLI and caps normal queries at 40s`() {
    let fireAndForget = RemoteOperationTimeoutPolicy.cliTimeout(
      for: makeSentInfo(timeoutMs: 20000),
      requestedTimeout: RemoteOperationTimeoutPolicy.fireAndForgetCLI,
      allowFirmwareExpansion: false
    )
    let shortWithExpansion = RemoteOperationTimeoutPolicy.cliTimeout(
      for: makeSentInfo(timeoutMs: 100),
      requestedTimeout: .milliseconds(1)
    )
    let callerFloor = RemoteOperationTimeoutPolicy.cliTimeout(
      for: makeSentInfo(timeoutMs: 1000),
      requestedTimeout: RemoteOperationTimeoutPolicy.defaultCLITimeout
    )
    let largeHint = RemoteOperationTimeoutPolicy.cliTimeout(
      for: makeSentInfo(timeoutMs: 25000),
      requestedTimeout: RemoteOperationTimeoutPolicy.defaultCLITimeout
    )

    #expect(fireAndForget == RemoteOperationTimeoutPolicy.fireAndForgetCLI)
    #expect(shortWithExpansion == .milliseconds(200))
    #expect(callerFloor == .seconds(10))
    #expect(largeHint == .seconds(40))
  }

  @Test
  func `Room sync path discovery budget scales from firmware hint between 10s and 40s`() {
    #expect(RemoteOperationTimeoutPolicy.roomSyncPathDiscoveryBudget(for: makeSentInfo(timeoutMs: 0)) == 20)
    #expect(RemoteOperationTimeoutPolicy.roomSyncPathDiscoveryBudget(for: makeSentInfo(timeoutMs: 1000)) == 10)
    #expect(RemoteOperationTimeoutPolicy.roomSyncPathDiscoveryBudget(for: makeSentInfo(timeoutMs: 5000)) == 14)
    #expect(RemoteOperationTimeoutPolicy.roomSyncPathDiscoveryBudget(for: makeSentInfo(timeoutMs: 30000)) == 40)
  }
}
