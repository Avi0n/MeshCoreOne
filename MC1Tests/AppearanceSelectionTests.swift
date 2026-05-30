import Testing
import SwiftUI
import StoreKit
import StoreKitTest
import MC1Services
@testable import MC1

@MainActor
@Suite("Appearance selection logic", .serialized, .enabled(if: StoreKitTestAvailability.servesProducts))
final class AppearanceSelectionTests {
    let session: SKTestSession

    init() throws {
        session = try SKTestSession(configurationFileNamed: "MC1")
        session.disableDialogs = true
        session.askToBuyEnabled = false   // reset: SKTestSession leaks session flags across instances in-process
        session.clearTransactions()
    }

    deinit { session.clearTransactions() }

    private func freshDefaults() -> UserDefaults {
        UserDefaults(suiteName: "test.\(UUID().uuidString)")!
    }

    @Test("with no purchases, only the default theme is available and Browse-more is shown")
    func emptyOwnership() async throws {
        let store = StoreService()
        await store.load()
        let theme = ThemeService(store: store, defaults: freshDefaults())

        #expect(theme.availableToCurrentUser().map(\.id) == [Theme.default.id])
        #expect(AppearanceView.shouldShowBrowseMore(available: theme.availableToCurrentUser()))
    }

    @Test("an owned theme becomes available; selecting it updates current")
    func ownedThemeSelectable() async throws {
        let store = StoreService()
        await store.load()
        let marine = try #require(store.product(for: StoreCatalog.Theme.marine))
        _ = try await purchaseWithRetry(marine, on: store)
        let theme = ThemeService(store: store, defaults: freshDefaults())

        let available = theme.availableToCurrentUser().map(\.id)
        #expect(available.contains(Theme.marine.id))
        #expect(!available.contains(Theme.ember.id))   // locked theme excluded

        try theme.setCurrent(.marine)
        #expect(theme.current.id == Theme.marine.id)
    }

    @Test("owning the bundle makes every theme available and hides Browse-more")
    func bundleOwnershipHidesBrowseMore() async throws {
        let store = StoreService()
        await store.load()
        let bundle = try #require(store.product(for: StoreCatalog.Theme.bundleAll))
        _ = try await purchaseWithRetry(bundle, on: store)
        let theme = ThemeService(store: store, defaults: freshDefaults())

        #expect(theme.availableToCurrentUser().count == ThemeRegistry.allThemes.count)
        #expect(!AppearanceView.shouldShowBrowseMore(available: theme.availableToCurrentUser()))
    }
}
