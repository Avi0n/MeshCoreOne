@testable import MC1
@testable import MC1Services
import StoreKit
import StoreKitTest
import SwiftUI
import Testing

@MainActor
@Suite("Appearance selection logic", .serialized, .enabled(if: StoreKitTestAvailability.servesProducts))
final class AppearanceSelectionTests {
  let session: SKTestSession

  init() throws {
    session = try SKTestSession(configurationFileNamed: "MC1")
    session.disableDialogs = true
    session.askToBuyEnabled = false // reset: SKTestSession leaks session flags across instances in-process
    session.clearTransactions()
  }

  deinit { session.clearTransactions() }

  private func freshDefaults() -> UserDefaults {
    UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }

  @Test
  func `with no purchases, only the default theme is available and Browse-more is shown`() async {
    let store = StoreService()
    await store.load()
    let theme = ThemeService(store: store, defaults: freshDefaults())

    #expect(theme.availableToCurrentUser().map(\.id) == [Theme.default.id])
    #expect(AppearanceView.shouldShowBrowseMore(available: theme.availableToCurrentUser()))
  }

  @Test
  func `a theme owned via the bundle becomes available; selecting it updates current`() async throws {
    let store = StoreService()
    await store.load()
    let bundle = try #require(store.product(for: StoreCatalog.Theme.bundleAll))
    _ = try await purchaseWithRetry(bundle, on: store)
    let theme = ThemeService(store: store, defaults: freshDefaults())

    let available = theme.availableToCurrentUser().map(\.id)
    #expect(available.contains(Theme.marine.id))

    try theme.setCurrent(.marine)
    #expect(theme.current.id == Theme.marine.id)
  }

  @Test
  func `owning the bundle makes every theme available and hides Browse-more`() async throws {
    let store = StoreService()
    await store.load()
    let bundle = try #require(store.product(for: StoreCatalog.Theme.bundleAll))
    _ = try await purchaseWithRetry(bundle, on: store)
    let theme = ThemeService(store: store, defaults: freshDefaults())

    #expect(theme.availableToCurrentUser().count == ThemeRegistry.allThemes.count)
    #expect(!AppearanceView.shouldShowBrowseMore(available: theme.availableToCurrentUser()))
  }
}
