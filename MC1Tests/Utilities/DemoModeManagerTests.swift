import Foundation
@testable import MC1
import Testing

@Suite("DemoModeManager Tests")
@MainActor
struct DemoModeManagerTests {
  private let defaults: UserDefaults

  init() {
    defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }

  // MARK: - Singleton Pattern Tests

  @Test(.serialized)
  func `shared returns the same instance`() {
    let instance1 = DemoModeManager.shared
    let instance2 = DemoModeManager.shared
    #expect(instance1 === instance2)
  }

  // MARK: - Default Values Tests

  @Test
  func `properties default to false for new instances`() {
    let manager = DemoModeManager(defaults: defaults)
    #expect(manager.isUnlocked == false)
    #expect(manager.isEnabled == false)
  }

  // MARK: - unlock() Method Tests

  @Test
  func `unlock sets both isUnlocked and isEnabled to true`() {
    let manager = DemoModeManager(defaults: defaults)

    #expect(manager.isUnlocked == false)
    #expect(manager.isEnabled == false)

    manager.unlock()

    #expect(manager.isUnlocked == true)
    #expect(manager.isEnabled == true)
  }

  // MARK: - UserDefaults Persistence Tests

  @Test
  func `UserDefaults persistence works for isUnlocked`() {
    let manager = DemoModeManager(defaults: defaults)

    manager.isUnlocked = true

    let persistedValue = defaults.bool(forKey: "isDemoModeUnlocked")
    #expect(persistedValue == true)
  }

  @Test
  func `UserDefaults persistence works for isEnabled`() {
    let manager = DemoModeManager(defaults: defaults)

    manager.isEnabled = true

    let persistedValue = defaults.bool(forKey: "isDemoModeEnabled")
    #expect(persistedValue == true)
  }

  @Test
  func `unlock persists both values to UserDefaults`() {
    let manager = DemoModeManager(defaults: defaults)

    manager.unlock()

    let unlockedValue = defaults.bool(forKey: "isDemoModeUnlocked")
    let enabledValue = defaults.bool(forKey: "isDemoModeEnabled")

    #expect(unlockedValue == true)
    #expect(enabledValue == true)
  }

  @Test
  func `values persist and can be read back`() {
    defaults.set(true, forKey: "isDemoModeUnlocked")
    defaults.set(true, forKey: "isDemoModeEnabled")

    let manager = DemoModeManager(defaults: defaults)
    #expect(manager.isUnlocked == true)
    #expect(manager.isEnabled == true)
  }
}
