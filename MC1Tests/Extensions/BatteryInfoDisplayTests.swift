@testable import MC1
import MeshCore
import Testing

struct BatteryInfoDisplayTests {
  // MARK: - Voltage Tests

  @Test func `voltage converts millivolts correctly`() {
    let battery = BatteryInfo(level: 3700)
    #expect(battery.voltage == 3.7)
  }

  @Test func `voltage zero millivolts`() {
    let battery = BatteryInfo(level: 0)
    #expect(battery.voltage == 0.0)
  }

  // MARK: - Percentage Tests

  @Test func `percentage full battery`() {
    let battery = BatteryInfo(level: 4200)
    #expect(battery.percentage == 100)
  }

  @Test func `percentage empty battery`() {
    let battery = BatteryInfo(level: 3000)
    #expect(battery.percentage == 0)
  }

  @Test func `percentage mid range`() {
    let battery = BatteryInfo(level: 3600) // 50% point
    #expect(battery.percentage == 50)
  }

  @Test func `percentage clamps above 100`() {
    let battery = BatteryInfo(level: 4500)
    #expect(battery.percentage == 100)
  }

  @Test func `percentage clamps below 0`() {
    let battery = BatteryInfo(level: 2500)
    #expect(battery.percentage == 0)
  }

  // MARK: - Icon Tests

  @Test func `icon name full battery`() {
    let battery = BatteryInfo(level: 4200)
    #expect(battery.iconName == "battery.100")
  }

  @Test func `icon name 75 percent`() {
    let battery = BatteryInfo(level: 3900) // ~75%
    #expect(battery.iconName == "battery.75")
  }

  @Test func `icon name 50 percent`() {
    let battery = BatteryInfo(level: 3600) // ~50%
    #expect(battery.iconName == "battery.50")
  }

  @Test func `icon name 25 percent`() {
    let battery = BatteryInfo(level: 3300) // ~25%
    #expect(battery.iconName == "battery.25")
  }

  @Test func `icon name low battery`() {
    let battery = BatteryInfo(level: 3100) // ~8%
    #expect(battery.iconName == "battery.0")
  }

  // MARK: - Color Tests

  @Test func `level color normal level`() {
    let battery = BatteryInfo(level: 3600) // 50%
    #expect(battery.levelColor == .primary)
  }

  @Test func `level color warning level`() {
    let battery = BatteryInfo(level: 3180) // ~15%
    #expect(battery.levelColor == .orange)
  }

  @Test func `level color critical level`() {
    let battery = BatteryInfo(level: 3060) // ~5%
    #expect(battery.levelColor == .red)
  }

  // MARK: - Battery Presence Tests

  @Test func `is battery present zero millivolts returns false`() {
    let battery = BatteryInfo(level: 0)
    #expect(!battery.isBatteryPresent)
  }

  @Test func `is battery present normal voltage returns true`() {
    let battery = BatteryInfo(level: 3700)
    #expect(battery.isBatteryPresent)
  }

  @Test func `is battery present minimum valid voltage returns true`() {
    let battery = BatteryInfo(level: 1)
    #expect(battery.isBatteryPresent)
  }
}
