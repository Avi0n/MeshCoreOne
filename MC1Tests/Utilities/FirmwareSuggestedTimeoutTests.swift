@testable import MC1
import Testing

@Suite("FirmwareSuggestedTimeout Sanitizing")
struct FirmwareSuggestedTimeoutTests {
  @Test
  func `Accepts sane firmware timeout`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 5000)
    #expect(timeout == 6.0)
  }

  @Test
  func `Falls back on zero timeout`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 0)
    #expect(timeout == 30.0)
  }

  @Test
  func `Falls back below minimum timeout`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 3000)
    #expect(timeout == 30.0)
  }

  @Test
  func `Falls back for absurdly large timeout`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 68_719_800)
    #expect(timeout == 30.0)
  }
}
