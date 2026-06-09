import Foundation
import Testing
@testable import MC1Services
@testable import MeshCore

@Suite("LoginTimeoutConfig Tests")
struct LoginTimeoutConfigTests {

    private func makeSentInfo(timeoutMs: UInt32) -> MessageSentInfo {
        MessageSentInfo(route: 0, expectedAck: Data([0x00]), suggestedTimeoutMs: timeoutMs)
    }

    @Test("Direct path (mode 0) uses base timeout only")
    func directPathMode0() {
        // Mode 0, 0 hops → encoded as 0x00
        let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x00)
        #expect(timeout == .seconds(5))
    }

    @Test("Direct path (mode 1) uses base timeout only, not mode bits")
    func directPathMode1() {
        // Mode 1, 0 hops → encoded as 0x40 (64 decimal)
        let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x40)
        #expect(timeout == .seconds(5))
    }

    @Test("Direct path (mode 2) uses base timeout only, not mode bits")
    func directPathMode2() {
        // Mode 2, 0 hops → encoded as 0x80 (128 decimal)
        let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x80)
        #expect(timeout == .seconds(5))
    }

    @Test("Mode 1 with 3 hops computes timeout from hop count")
    func mode1With3Hops() {
        // Mode 1, 3 hops → encoded as 0x43
        let timeout = LoginTimeoutConfig.timeout(forPathLength: 0x43)
        #expect(timeout == .seconds(35))  // 5 + 3*10
    }

    @Test("Mode 0 with 5 hops computes correct timeout")
    func mode0With5Hops() {
        let timeout = LoginTimeoutConfig.timeout(forPathLength: 5)
        #expect(timeout == .seconds(55))  // 5 + 5*10
    }

    @Test("Flood routing (0xFF) falls back to base timeout")
    func floodRouting() {
        // 0xFF: mode 3 (reserved) → decodePathLen returns nil → 0 hops
        let timeout = LoginTimeoutConfig.timeout(forPathLength: 0xFF)
        #expect(timeout == .seconds(5))
    }

    @Test("Timeout is capped at maximum")
    func timeoutCapped() {
        // Mode 0, 6 hops → 5 + 60 = 65, should cap at 60
        let timeout = LoginTimeoutConfig.timeout(forPathLength: 6)
        #expect(timeout == .seconds(60))
    }

    @Test("Login timeout policy clamps long firmware suggestions")
    func loginTimeoutPolicyClampsFirmwareSuggestion() {
        let sentInfo = makeSentInfo(timeoutMs: 20_000)

        let timeout = RemoteOperationTimeoutPolicy.loginTimeout(for: sentInfo, pathLength: 0)

        #expect(timeout == .seconds(20))
    }

    @Test("Login timeout policy respects path floor when firmware is shorter")
    func loginTimeoutPolicyUsesPathFloor() {
        let sentInfo = makeSentInfo(timeoutMs: 1_000)

        let timeout = RemoteOperationTimeoutPolicy.loginTimeout(for: sentInfo, pathLength: 0x43)

        #expect(timeout == .seconds(20))
    }

    @Test("CLI timeout policy clamps long firmware suggestions")
    func cliTimeoutPolicyClampsFirmwareSuggestion() {
        let sentInfo = makeSentInfo(timeoutMs: 20_000)

        let timeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: .seconds(10))

        #expect(timeout == .seconds(15))
    }

    @Test("CLI timeout policy keeps caller budget when firmware is shorter")
    func cliTimeoutPolicyUsesCallerBudgetFloor() {
        let sentInfo = makeSentInfo(timeoutMs: 1_000)

        let timeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: .seconds(10))

        #expect(timeout == .seconds(10))
    }
}
