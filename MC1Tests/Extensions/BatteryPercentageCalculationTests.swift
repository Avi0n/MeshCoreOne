@testable import MC1
import MeshCore
import Testing

@Suite("Battery Percentage Calculation Tests")
struct BatteryPercentageCalculationTests {
  /// Li-Ion array for testing: [4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100]
  let liIonArray = [4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100]

  @Test
  func `Voltage at 100% point returns 100`() {
    let battery = BatteryInfo(level: 4190)
    #expect(battery.percentage(using: liIonArray) == 100)
  }

  @Test
  func `Voltage at 0% point returns 0`() {
    let battery = BatteryInfo(level: 3100)
    #expect(battery.percentage(using: liIonArray) == 0)
  }

  @Test
  func `Voltage above max returns 100`() {
    let battery = BatteryInfo(level: 4500)
    #expect(battery.percentage(using: liIonArray) == 100)
  }

  @Test
  func `Voltage below min returns 0`() {
    let battery = BatteryInfo(level: 2800)
    #expect(battery.percentage(using: liIonArray) == 0)
  }

  @Test
  func `Voltage at 50% point returns 50`() {
    // 50% is at index 5 = 3720mV
    let battery = BatteryInfo(level: 3720)
    #expect(battery.percentage(using: liIonArray) == 50)
  }

  @Test
  func `Voltage interpolates between points`() {
    // Midpoint between 4190 (100%) and 4050 (90%) = 4120 should be ~95%
    let battery = BatteryInfo(level: 4120)
    let percent = battery.percentage(using: liIonArray)
    #expect(percent >= 94 && percent <= 96, "Expected ~95%, got \(percent)")
  }
}
