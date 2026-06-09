import Testing
@testable import MC1Services

@Suite("BLEPhase Tests")
struct BLEPhaseTests {

    // MARK: - Name Tests

    @Test("idle phase has correct name")
    func idlePhaseHasCorrectName() {
        let phase = BLEPhase.idle
        #expect(phase.name == "idle")
    }

    // MARK: - isDiscoveryChain Tests

    @Test("idle is not part of discovery chain")
    func idleIsNotDiscoveryChain() {
        #expect(BLEPhase.idle.isDiscoveryChain == false)
    }

    // Note: discoveringServices, discoveringCharacteristics, and subscribingToNotifications
    // require CBPeripheral instances which can't be created in unit tests.
    // Their isDiscoveryChain == true is verified implicitly through integration tests
    // and the switch statement exhaustiveness check.

    // MARK: - isActive Tests

    @Test("idle phase is not active")
    func idlePhaseIsNotActive() {
        let phase = BLEPhase.idle
        #expect(phase.isActive == false)
    }

    // MARK: - Peripheral Tests

    @Test("idle phase has no peripheral")
    func idlePhaseHasNoPeripheral() {
        let phase = BLEPhase.idle
        #expect(phase.peripheral == nil)
    }

    // MARK: - DeviceID Tests

    @Test("idle phase has no deviceID")
    func idlePhaseHasNoDeviceID() {
        let phase = BLEPhase.idle
        #expect(phase.deviceID == nil)
    }
}
