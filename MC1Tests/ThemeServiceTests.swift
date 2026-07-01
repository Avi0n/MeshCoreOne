@testable import MC1
@testable import MC1Services
import StoreKit
import StoreKitTest
import SwiftUI
import Testing

@Suite("ThemeServiceError")
struct ThemeServiceErrorTests {
  @Test
  func `notOwned carries the productID and has a non-empty localized description`() {
    let error = ThemeServiceError.notOwned(productID: StoreCatalog.Theme.ember)
    #expect(error.errorDescription?.isEmpty == false)
  }
}

@MainActor
@Suite("ThemeService pure")
struct ThemeServicePureTests {
  /// A StoreService with no purchases and its listener detached, for ownership-independent tests.
  private func emptyStore() -> StoreService {
    let store = StoreService()
    store.shutdown()
    return store
  }

  private func freshDefaults() -> UserDefaults {
    UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }

  @Test
  func `missing selectedThemeID uses default in memory and does NOT write back`() {
    let defaults = freshDefaults()
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    #expect(service.current.id == Theme.default.id)
    #expect(defaults.object(forKey: PersistenceKeys.selectedThemeID) == nil)
  }

  @Test
  func `unknown selectedThemeID falls back to default and overwrites the stored value`() {
    let defaults = freshDefaults()
    defaults.set("ghost-theme", forKey: PersistenceKeys.selectedThemeID)
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    #expect(service.current.id == Theme.default.id)
    #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.default.id)
  }

  @Test
  func `cold start retains a persisted paid theme even with empty ownership`() {
    let defaults = freshDefaults()
    defaults.set(Theme.ember.id, forKey: PersistenceKeys.selectedThemeID)
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    #expect(service.current.id == Theme.ember.id)
    // Valid registry ID — not overwritten on init.
    #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.ember.id)
  }

  @Test
  func `with no purchases, only the default theme is available and paid themes throw on setCurrent`() {
    let service = ThemeService(store: emptyStore(), defaults: freshDefaults())
    #expect(service.availableToCurrentUser().map(\.id) == [Theme.default.id])
    #expect(throws: ThemeServiceError.self) {
      try service.setCurrent(.ember)
    }
  }

  @Test
  func `missing appColorSchemePreference uses .system without writing back`() {
    let defaults = freshDefaults()
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    #expect(service.colorSchemePreference == .system)
    #expect(defaults.object(forKey: PersistenceKeys.appColorSchemePreference) == nil)
  }

  @Test
  func `unknown appColorSchemePreference falls back to .system and overwrites`() {
    let defaults = freshDefaults()
    defaults.set("auto", forKey: PersistenceKeys.appColorSchemePreference)
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    #expect(service.colorSchemePreference == .system)
    #expect(defaults.string(forKey: PersistenceKeys.appColorSchemePreference)
      == AppColorSchemePreference.system.rawValue)
  }

  @Test
  func `setColorSchemePreference persists the raw value and updates state`() {
    let defaults = freshDefaults()
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    service.setColorSchemePreference(.dark)
    #expect(service.colorSchemePreference == .dark)
    #expect(defaults.string(forKey: PersistenceKeys.appColorSchemePreference) == "dark")
  }

  @Test
  func `effectiveColorScheme: default theme defers to the preference`() {
    let service = ThemeService(store: emptyStore(), defaults: freshDefaults())
    service.setColorSchemePreference(.system)
    #expect(service.effectiveColorScheme == nil)
    service.setColorSchemePreference(.light)
    #expect(service.effectiveColorScheme == .light)
    service.setColorSchemePreference(.dark)
    #expect(service.effectiveColorScheme == .dark)
  }

  @Test
  func `effectiveColorScheme: Ember forces .dark regardless of the preference`() {
    let defaults = freshDefaults()
    // init adopts the persisted theme without enforcing ownership.
    defaults.set(Theme.ember.id, forKey: PersistenceKeys.selectedThemeID)
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    service.setColorSchemePreference(.light)
    #expect(service.effectiveColorScheme == .dark)
    service.setColorSchemePreference(.system)
    #expect(service.effectiveColorScheme == .dark)
  }

  @Test
  func `refreshFromUserDefaults adopts an externally written scheme and retains the theme while unloaded`() {
    // Scheme adoption has no ownership gate, so it applies regardless of load state. The theme
    // is retained (not reverted) because `emptyStore()` is `.idle` — ownership is not yet
    // authoritative, so refresh must not destructively revert (mirrors `init`).
    let defaults = freshDefaults()
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    defaults.set(Theme.fern.id, forKey: PersistenceKeys.selectedThemeID)
    defaults.set("dark", forKey: PersistenceKeys.appColorSchemePreference)
    service.refreshFromUserDefaults()
    #expect(service.current.id == Theme.fern.id)
    #expect(service.colorSchemePreference == .dark)
  }

  @Test
  func `refreshFromUserDefaults does not revert a persisted theme while the store is unloaded`() {
    // Regression guard: an owner restoring a backup before the entitlement walk has populated
    // ownedThemeIDs (store still `.idle`/`.failed`) must keep their selection. `emptyStore()` is
    // `.idle`, so the ownership revert is deferred to the post-load listener — refresh neither
    // changes `current` nor overwrites the persisted value.
    let defaults = freshDefaults()
    defaults.set(Theme.ember.id, forKey: PersistenceKeys.selectedThemeID)
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    #expect(service.current.id == Theme.ember.id)

    service.refreshFromUserDefaults()

    #expect(service.current.id == Theme.ember.id)
    #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.ember.id)
  }

  @Test
  func `refreshFromUserDefaults overwrites an unknown selectedThemeID with the default`() {
    // Seed after init so the unknown-value path is exercised in refresh, not init. This is
    // the mid-session backup-import shape: BackupUserDefaults.restore writes the foreign
    // value into defaults, then notifyDataRestored calls refreshFromUserDefaults.
    let defaults = freshDefaults()
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    defaults.set("future-build-theme-id", forKey: PersistenceKeys.selectedThemeID)

    service.refreshFromUserDefaults()

    #expect(service.current.id == Theme.default.id)
    #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.default.id)
  }

  @Test
  func `refreshFromUserDefaults overwrites an unknown color-scheme preference with .system`() {
    let defaults = freshDefaults()
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    defaults.set("auto", forKey: PersistenceKeys.appColorSchemePreference)

    service.refreshFromUserDefaults()

    #expect(service.colorSchemePreference == .system)
    #expect(defaults.string(forKey: PersistenceKeys.appColorSchemePreference)
      == AppColorSchemePreference.system.rawValue)
  }

  @Test
  func `restore-origin downgrade: an unknown backed-up scheme falls back to .system on init`() {
    let defaults = freshDefaults()
    var prefs = BackupUserDefaults()
    prefs.appColorSchemePreference = "auto" // a future raw value this build doesn't know
    prefs.restore(to: defaults) // write-if-missing writes "auto"
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    #expect(service.colorSchemePreference == .system)
    #expect(defaults.string(forKey: PersistenceKeys.appColorSchemePreference)
      == AppColorSchemePreference.system.rawValue)
  }

  @Test
  func `restore-origin downgrade: an unknown backed-up theme ID falls back to default on init`() {
    let defaults = freshDefaults()
    var prefs = BackupUserDefaults()
    prefs.selectedThemeID = "theme-from-a-future-build"
    prefs.restore(to: defaults)
    let service = ThemeService(store: emptyStore(), defaults: defaults)
    #expect(service.current.id == Theme.default.id)
    #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.default.id)
  }
}

@MainActor
@Suite("ThemeService ownership", .serialized, .enabled(if: StoreKitTestAvailability.servesProducts))
final class ThemeServiceOwnershipTests {
  let session: SKTestSession

  init() throws {
    session = try SKTestSession(configurationFileNamed: "MC1")
    session.disableDialogs = true
    // Reset Ask-to-Buy: a fresh SKTestSession does not clear this flag on storekitd, so a
    // prior suite that enabled it would otherwise leak a pending-purchase mode into these tests.
    session.askToBuyEnabled = false
    session.clearTransactions()
  }

  deinit { session.clearTransactions() }

  private func freshDefaults() -> UserDefaults {
    UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }

  @Test
  func `a theme owned via the bundle is accessible and selectable`() async throws {
    let store = StoreService()
    await store.load()
    let bundle = try #require(store.product(for: StoreCatalog.Theme.bundleAll))
    _ = try await purchaseWithRetry(bundle, on: store)
    store.shutdown()

    let theme = ThemeService(store: store, defaults: freshDefaults())
    #expect(theme.availableToCurrentUser().contains { $0.id == Theme.ember.id })
    try theme.setCurrent(.ember)
    #expect(theme.current.id == Theme.ember.id)
  }

  @Test
  func `owning the bundle makes every theme available`() async throws {
    let store = StoreService()
    await store.load()
    let bundle = try #require(store.product(for: StoreCatalog.Theme.bundleAll))
    _ = try await purchaseWithRetry(bundle, on: store)
    store.shutdown()

    let theme = ThemeService(store: store, defaults: freshDefaults())
    #expect(Set(theme.availableToCurrentUser().map(\.id))
      == Set(ThemeRegistry.allThemes.map(\.id)))
  }

  @Test
  func `refunding the bundle reverts the selected theme to default via the listener`() async throws {
    let store = StoreService() // listener stays live for the refund path
    await store.load()
    let defaults = freshDefaults()
    let theme = ThemeService(store: store, defaults: defaults)

    let bundle = try #require(store.product(for: StoreCatalog.Theme.bundleAll))
    _ = try await purchaseWithRetry(bundle, on: store)
    try theme.setCurrent(.ember)
    #expect(theme.current.id == Theme.ember.id)

    let txn = try #require(session.allTransactions().first {
      $0.productIdentifier == StoreCatalog.Theme.bundleAll
    })
    try session.refundTransaction(identifier: txn.identifier)

    try await waitUntil(timeout: .seconds(5)) {
      theme.current.id == Theme.default.id
    }
    #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.default.id)
  }

  @Test
  func `the load() walk does not wipe a persisted theme on an empty ownership read`() async {
    // Guards the cold-start reversion hole: the first entitlement walk fires
    // onEntitlementsChanged from inside load() while still .loading, and on a cold storekitd
    // it can read empty. With the listener live and a paid theme already persisted, that empty
    // read must not overwrite the selection.
    let store = StoreService()
    let defaults = freshDefaults()
    defaults.set(Theme.ember.id, forKey: PersistenceKeys.selectedThemeID)
    let theme = ThemeService(store: store, defaults: defaults)
    #expect(theme.current.id == Theme.ember.id)

    await store.load()
    store.shutdown()

    #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.ember.id)
  }

  @Test
  func `refreshFromUserDefaults reverts an unowned restored theme once the store is loaded`() async {
    // The post-load enforcement path: with a loaded store and no purchases, a restored paid
    // theme is genuinely unowned, so refresh reverts it and overwrites the persisted value.
    let store = StoreService()
    await store.load()
    store.shutdown()
    let defaults = freshDefaults()
    defaults.set(Theme.ember.id, forKey: PersistenceKeys.selectedThemeID)
    let theme = ThemeService(store: store, defaults: defaults)
    #expect(theme.current.id == Theme.ember.id)

    theme.refreshFromUserDefaults()

    #expect(theme.current.id == Theme.default.id)
    #expect(defaults.string(forKey: PersistenceKeys.selectedThemeID) == Theme.default.id)
  }
}
