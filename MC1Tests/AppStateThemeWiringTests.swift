import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
@Suite("AppState theme wiring", .serialized)
struct AppStateThemeWiringTests {
  private let preferences = IsolatedUserDefaults()

  private var defaults: UserDefaults {
    preferences.defaults
  }

  @Test
  func `AppState exposes a themeService defaulting to the default theme`() {
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    #expect(appState.themeService.current.id == Theme.default.id)
  }

  @Test
  func `AppState exposes a storeState wrapping an idle StoreService`() {
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    #expect(appState.storeState.service.loadState == .idle)
  }

  @Test
  func `notifyDataRestored does not wipe a restored paid theme while the store is unloaded`() async {
    // notifyDataRestored delegates to refreshFromUserDefaults. AppState's StoreService is .idle
    // until load(), so ownership is not yet authoritative: a restored paid theme is adopted, not
    // wiped, to protect an owner who restores before the entitlement walk. Enforcement once the
    // store is loaded is covered by ThemeServiceOwnershipTests.refreshRevertsUnownedThemeWhenLoaded.
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    #expect(appState.storeState.service.loadState == .idle)
    #expect(appState.themeService.current.id == Theme.default.id)

    defaults.set(Theme.ember.id, forKey: PersistenceKeys.selectedThemeID)
    await appState.notifyDataRestored().value
    #expect(appState.themeService.current.id == Theme.ember.id)
  }

  @Test
  func `separate AppState preferences keep restored themes isolated`() async {
    let otherPreferences = IsolatedUserDefaults()
    defaults.set(Theme.ember.id, forKey: PersistenceKeys.selectedThemeID)
    otherPreferences.defaults.set(Theme.marine.id, forKey: PersistenceKeys.selectedThemeID)
    let appState = AppState(defaults: defaults)
    let otherAppState = AppState(defaults: otherPreferences.defaults)
    defer {
      appState.shutdown()
      otherAppState.shutdown()
    }

    #expect(appState.themeService.current.id == Theme.ember.id)
    #expect(otherAppState.themeService.current.id == Theme.marine.id)
    defaults.set(Theme.fern.id, forKey: PersistenceKeys.selectedThemeID)
    await appState.notifyDataRestored().value
    #expect(appState.themeService.current.id == Theme.fern.id)
    #expect(otherAppState.themeService.current.id == Theme.marine.id)
    #expect(otherPreferences.defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.marine.id)
  }
}
