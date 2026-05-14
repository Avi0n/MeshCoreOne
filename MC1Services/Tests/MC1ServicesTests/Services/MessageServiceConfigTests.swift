import Foundation
import Testing
@testable import MC1Services

/// MessageServiceConfig guards against `maxAttempts > 4` via a precondition.
/// Firmware AckCodeBuilder masks attempts with `& 0x03`, so values above 4
/// collide on the wire and produce ambiguous ACKs. Swift Testing has no
/// precondition matcher, so the failing case is documented inline rather
/// than asserted at runtime; the boundary case is exercised here.
@Suite("MessageServiceConfig precondition")
struct MessageServiceConfigTests {

    @Test("maxAttempts == 4 is accepted at the precondition boundary")
    func boundaryValueIsAccepted() {
        let config = MessageServiceConfig(maxAttempts: 4)
        #expect(config.maxAttempts == 4)
    }

    @Test("Default config respects the maxAttempts ceiling")
    func defaultConfigHonoursCeiling() {
        let config = MessageServiceConfig()
        #expect(config.maxAttempts <= 4)
    }
}
