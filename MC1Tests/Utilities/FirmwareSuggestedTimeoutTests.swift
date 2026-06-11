import Testing
@testable import MC1

@Suite("FirmwareSuggestedTimeout Sanitizing")
struct FirmwareSuggestedTimeoutTests {
    @Test("Accepts sane firmware timeout")
    func acceptsSaneFirmwareTimeout() {
        let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 5_000)
        #expect(timeout == 6.0)
    }

    @Test("Falls back on zero timeout")
    func fallsBackOnZeroTimeout() {
        let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 0)
        #expect(timeout == 30.0)
    }

    @Test("Falls back below minimum timeout")
    func fallsBackBelowMinimumTimeout() {
        let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 3_000)
        #expect(timeout == 30.0)
    }

    @Test("Falls back for absurdly large timeout")
    func fallsBackForAbsurdlyLargeTimeout() {
        let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 68_719_800)
        #expect(timeout == 30.0)
    }
}
