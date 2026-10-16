@testable import MC1
import Testing

@Suite("FirmwareSuggestedTimeout Sanitizing")
struct FirmwareSuggestedTimeoutTests {
  private let tolerance = 0.0001

  // MARK: - Zero-hop (direct single-neighbor ping)

  @Test
  func `Zero-hop honors a small valid hint instead of inflating it`() {
    // Firmware default preset (SF10/BW250) estimates ~3.2s for a zero-hop trace.
    // A hint that small is valid, not implausible, so it is honored rather than
    // raised to the no-hint default.
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 3200, profile: .zeroHop)
    #expect(abs(timeout - 3.84) < tolerance)
    #expect(timeout < 5.0) // below the no-hint default, not snapped up to it
  }

  @Test
  func `Zero-hop honors a fast-preset hint`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 1300, profile: .zeroHop)
    #expect(abs(timeout - 1.56) < tolerance)
  }

  @Test
  func `Zero-hop floors a tiny hint`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 500, profile: .zeroHop)
    #expect(timeout == 1.0)
  }

  @Test
  func `Zero-hop honors a slow max-range preset hint up to the ceiling`() {
    // SF12/BW125 zero-hop round trips genuinely approach ~20s; it must not be
    // clamped down, or a valid slow link reports a false no-response.
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 20000, profile: .zeroHop)
    #expect(timeout == 24.0)
  }

  @Test
  func `Zero-hop defaults on a missing hint`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 0, profile: .zeroHop)
    #expect(timeout == 5.0)
  }

  @Test
  func `Zero-hop caps an absurd hint`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 68_719_800, profile: .zeroHop)
    #expect(timeout == 30.0)
  }

  // MARK: - Flood (path discovery, user-built multi-hop traces)

  @Test
  func `Flood adds return-leg grace on top of a sane hint`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 5000, profile: .flood)
    #expect(timeout == 14.0) // 5000ms × 1.2 + 8s grace
  }

  @Test
  func `Flood grace lifts a small hint above the floor`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 3000, profile: .flood)
    #expect(abs(timeout - 11.6) < tolerance) // 3000ms × 1.2 + 8s grace
  }

  @Test
  func `Flood defaults on a missing hint`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 0, profile: .flood)
    #expect(timeout == 30.0)
  }

  @Test
  func `Flood caps an absurd hint`() {
    let timeout = FirmwareSuggestedTimeout.sanitizedSeconds(suggestedTimeoutMs: 68_719_800, profile: .flood)
    #expect(timeout == 60.0)
  }

  // MARK: - Path discovery overall budget

  @Test
  func `Path discovery floors a fast-preset hint to the multi-hop minimum`() {
    let timeout = FirmwareSuggestedTimeout.pathDiscoverySeconds(suggestedTimeoutMs: 5000)
    #expect(timeout == FirmwareSuggestedTimeout.pathDiscoveryMinimumOverallSeconds)
    #expect(timeout == 20.0)
  }

  @Test
  func `Path discovery honors a larger flood budget above the minimum`() {
    let timeout = FirmwareSuggestedTimeout.pathDiscoverySeconds(suggestedTimeoutMs: 20000)
    #expect(timeout == 32.0) // 20000ms × 1.2 + 8s grace
  }

  @Test
  func `Path discovery missing hint uses the flood default above the minimum`() {
    let timeout = FirmwareSuggestedTimeout.pathDiscoverySeconds(suggestedTimeoutMs: 0)
    #expect(timeout == 30.0)
  }

  @Test
  func `Path discovery retransmit interval uses double firmware est with a five second floor`() {
    #expect(FirmwareSuggestedTimeout.pathDiscoveryRetransmitInterval(suggestedTimeoutMs: 0) == nil)
    #expect(
      FirmwareSuggestedTimeout.pathDiscoveryRetransmitInterval(suggestedTimeoutMs: 1000) == .seconds(5)
    )
    #expect(
      FirmwareSuggestedTimeout.pathDiscoveryRetransmitInterval(suggestedTimeoutMs: 5000) == .seconds(10)
    )
  }
}
