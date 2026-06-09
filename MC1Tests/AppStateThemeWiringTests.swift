import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@MainActor
@Suite("AppState theme wiring", .serialized)
struct AppStateThemeWiringTests {

    @Test("AppState exposes a themeService defaulting to the default theme")
    func exposesThemeService() {
        UserDefaults.standard.removeObject(forKey: PersistenceKeys.selectedThemeID)
        let appState = AppState()
        #expect(appState.themeService.current.id == Theme.default.id)
    }

    @Test("AppState exposes a storeState wrapping an idle StoreService")
    func exposesStoreState() {
        let appState = AppState()
        #expect(appState.storeState.service.loadState == .idle)
    }

    @Test("notifyDataRestored does not wipe a restored paid theme while the store is unloaded")
    func notifyDataRestoredDefersWhileUnloaded() {
        // notifyDataRestored delegates to refreshFromUserDefaults. AppState's StoreService is .idle
        // until load(), so ownership is not yet authoritative: a restored paid theme is adopted, not
        // wiped, to protect an owner who restores before the entitlement walk. Enforcement once the
        // store is loaded is covered by ThemeServiceOwnershipTests.refreshRevertsUnownedThemeWhenLoaded.
        defer { UserDefaults.standard.removeObject(forKey: PersistenceKeys.selectedThemeID) }
        UserDefaults.standard.removeObject(forKey: PersistenceKeys.selectedThemeID)
        let appState = AppState()
        #expect(appState.storeState.service.loadState == .idle)
        #expect(appState.themeService.current.id == Theme.default.id)

        UserDefaults.standard.set(Theme.ember.id, forKey: PersistenceKeys.selectedThemeID)
        appState.notifyDataRestored()
        #expect(appState.themeService.current.id == Theme.ember.id)
    }
}
