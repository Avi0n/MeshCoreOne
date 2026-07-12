@testable import MC1Services
import Testing

@Suite("BLEPhase Tests")
struct BLEPhaseTests {
  // MARK: - Name Tests

  @Test
  func `idle phase has correct name`() {
    let phase = BLEPhase.idle
    #expect(phase.name == "idle")
  }

  // MARK: - isDiscoveryChain Tests

  @Test
  func `idle is not part of discovery chain`() {
    #expect(BLEPhase.idle.isDiscoveryChain == false)
  }

  // Note: discoveringServices, discoveringCharacteristics, and subscribingToNotifications
  // require CBPeripheral instances which can't be created in unit tests.
  // Their isDiscoveryChain == true is verified implicitly through integration tests
  // and the switch statement exhaustiveness check.

  // MARK: - isActive Tests

  @Test
  func `idle phase is not active`() {
    let phase = BLEPhase.idle
    #expect(phase.isActive == false)
  }

  // MARK: - Peripheral Tests

  @Test
  func `idle phase has no peripheral`() {
    let phase = BLEPhase.idle
    #expect(phase.peripheral == nil)
  }

  // MARK: - DeviceID Tests

  @Test
  func `idle phase has no deviceID`() {
    let phase = BLEPhase.idle
    #expect(phase.deviceID == nil)
  }
}
