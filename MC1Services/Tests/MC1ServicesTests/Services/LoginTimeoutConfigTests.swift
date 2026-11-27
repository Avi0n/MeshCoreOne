import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("LoginTimeoutConfig Tests")
struct LoginTimeoutConfigTests {
  private func makeSentInfo(route: UInt8 = 0, timeoutMs: UInt32) -> MessageSentInfo {
    MessageSentInfo(route: route, expectedAck: Data([0x00]), suggestedTimeoutMs: timeoutMs)
  }

  @Test
  func `login timeout is the hop floor or the scaled hint, capped at 40s`() {
    let zeroHop = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 0),
      pathLength: 0
    )
    let threeHop = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 1000),
      pathLength: 3
    )
    let scaledHint = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 6560),
      pathLength: 2
    )
    let cappedHint = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 25000),
      pathLength: 0
    )
    let flood = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(route: MessageSentInfo.floodRoute, timeoutMs: 5200),
      pathLength: 0
    )
    let unknownPath = RemoteOperationTimeoutPolicy.loginTimeout(
      for: makeSentInfo(timeoutMs: 1000),
      pathLength: 0xFF
    )

    #expect(zeroHop == .seconds(5))
    #expect(threeHop == .seconds(20))
    #expect(scaledHint == .milliseconds(16400))
    #expect(cappedHint == .seconds(40))
    #expect(flood == .seconds(40))
    #expect(unknownPath == .seconds(40))
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
