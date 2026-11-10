import Foundation
@testable import MC1Services
import Testing

@Suite("DMSendBudget reader")
struct DMSendBudgetTests {
  @Test
  func `missing preferences use four initial and four fallback attempts`() {
    let (defaults, suiteName) = makeDefaults()
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let budget = DMSendBudget.current(defaults: defaults)
    #expect(budget.directAttempts == 4)
    #expect(budget.floodAttempts == 4)
    #expect(budget.totalAttempts == 8)
  }

  @Test
  func `stored flood 0 stays 0`() {
    let (defaults, suiteName) = makeDefaults()
    defer { defaults.removePersistentDomain(forName: suiteName) }
    defaults.set(0, forKey: AppStorageKey.dmFloodAttempts.rawValue)
    let budget = DMSendBudget.current(defaults: defaults)
    #expect(budget.directAttempts == 4)
    #expect(budget.floodAttempts == 0)
    #expect(budget.totalAttempts == 4)
  }

  @Test
  func `out of range values clamp to the picker ranges`() {
    let (defaults, suiteName) = makeDefaults()
    defer { defaults.removePersistentDomain(forName: suiteName) }
    defaults.set(0, forKey: AppStorageKey.dmDirectAttempts.rawValue)
    defaults.set(9, forKey: AppStorageKey.dmFloodAttempts.rawValue)
    let highFlood = DMSendBudget.current(defaults: defaults)
    #expect(highFlood.directAttempts == 1)
    #expect(highFlood.floodAttempts == 4)
    #expect(highFlood.totalAttempts == 5)

    defaults.set(8, forKey: AppStorageKey.dmDirectAttempts.rawValue)
    defaults.set(-1, forKey: AppStorageKey.dmFloodAttempts.rawValue)
    let lowFlood = DMSendBudget.current(defaults: defaults)
    #expect(lowFlood.directAttempts == 4)
    #expect(lowFlood.floodAttempts == 0)
    #expect(lowFlood.totalAttempts == 4)
  }

  @Test
  func `turning fallback off stores zero and on restores the default only from zero`() {
    #expect(DMSendBudget.floodAttempts(enablingFallback: false, stored: 3) == 0)
    #expect(DMSendBudget.floodAttempts(enablingFallback: true, stored: 0) == 4)
    #expect(DMSendBudget.floodAttempts(enablingFallback: true, stored: 2) == 2)
    #expect(DMSendBudget.floodAttempts(enablingFallback: true, stored: 9) == 9)
  }

  @Test
  func `the flood menu shows a count from 1 to 4 and clamps a write`() {
    #expect(DMSendBudget.menuFloodAttempts(stored: 3) == 3)
    #expect(DMSendBudget.menuFloodAttempts(stored: 0) == 4)
    #expect(DMSendBudget.menuFloodAttempts(stored: 9) == 4)
    #expect(DMSendBudget.clampedMenuFloodAttempts(0) == 1)
    #expect(DMSendBudget.clampedMenuFloodAttempts(2) == 2)
    #expect(DMSendBudget.clampedMenuFloodAttempts(9) == 4)
  }

  private func makeDefaults() -> (UserDefaults, String) {
    let name = "test.dm-budget.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name)!
    defaults.removePersistentDomain(forName: name)
    return (defaults, name)
  }
}
