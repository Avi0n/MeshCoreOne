import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("LoginTimeoutConfig Tests")
struct LoginTimeoutConfigTests {
  private func makeSentInfo(timeoutMs: UInt32) -> MessageSentInfo {
    MessageSentInfo(route: 0, expectedAck: Data([0x00]), suggestedTimeoutMs: timeoutMs)
  }

  @Test
  func `Direct path (mode 0) uses base timeout only`() {
    // Mode 0, 0 hops → encoded as 0x00
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x00)
    #expect(timeout == .seconds(5))
  }

  @Test
  func `Direct path (mode 1) uses base timeout only, not mode bits`() {
    // Mode 1, 0 hops → encoded as 0x40 (64 decimal)
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x40)
    #expect(timeout == .seconds(5))
  }

  @Test
  func `Direct path (mode 2) uses base timeout only, not mode bits`() {
    // Mode 2, 0 hops → encoded as 0x80 (128 decimal)
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x80)
    #expect(timeout == .seconds(5))
  }

  @Test
  func `Mode 1 with 3 hops computes timeout from hop count`() {
    // Mode 1, 3 hops → encoded as 0x43
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x43)
    #expect(timeout == .seconds(35)) // 5 + 3*10
  }

  @Test
  func `Mode 0 with 5 hops computes correct timeout`() {
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 5)
    #expect(timeout == .seconds(55)) // 5 + 5*10
  }

  @Test
  func `Flood routing (0xFF) falls back to base timeout`() {
    // 0xFF: mode 3 (reserved) → decodePathLen returns nil → 0 hops
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 0xFF)
    #expect(timeout == .seconds(5))
  }

  @Test
  func `Timeout is capped at maximum`() {
    // Mode 0, 6 hops → 5 + 60 = 65, should cap at 60
    let timeout = LoginTimeoutConfig.timeout(forPathLength: 6)
    #expect(timeout == .seconds(60))
  }

  @Test
  func `Login timeout policy clamps long firmware suggestions`() {
    let sentInfo = makeSentInfo(timeoutMs: 20000)

    let timeout = RemoteOperationTimeoutPolicy.loginTimeout(for: sentInfo, pathLength: 0)

    #expect(timeout == .seconds(20))
  }

  @Test
  func `Login timeout policy respects path floor when firmware is shorter`() {
    let sentInfo = makeSentInfo(timeoutMs: 1000)

    let timeout = RemoteOperationTimeoutPolicy.loginTimeout(for: sentInfo, pathLength: 0x43)

    #expect(timeout == .seconds(20))
  }

  @Test
  func `CLI timeout policy clamps long firmware suggestions`() {
    let sentInfo = makeSentInfo(timeoutMs: 20000)

    let timeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: .seconds(10))

    #expect(timeout == .seconds(15))
  }

  @Test
  func `CLI timeout policy keeps caller budget when firmware is shorter`() {
    let sentInfo = makeSentInfo(timeoutMs: 1000)

    let timeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: .seconds(10))

    #expect(timeout == .seconds(10))
  }
}
