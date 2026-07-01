import MC1Services
import SwiftUI

/// Owns the selected theme and the global color-scheme preference, enforces the ownership
/// access rule against `StoreService`, and reverts an inaccessible theme on entitlement loss.
/// `defaults` is injectable (defaulting to `.standard`) so the service is unit-testable in
/// isolation — matching `BackupUserDefaults`'s existing `defaults:` parameter convention.
@Observable
@MainActor
final class ThemeService {
  private(set) var current: Theme
  private(set) var colorSchemePreference: AppColorSchemePreference
  private let store: StoreService
  private let defaults: UserDefaults

  init(store: StoreService, defaults: UserDefaults = .standard) {
    self.store = store
    self.defaults = defaults

    // Init contract: registry-based fallback only. Do not enforce ownership here —
    // StoreService.ownedThemeIDs is not yet populated (the entitlement walk is async,
    // triggered by load() later). Enforcing access here would overwrite a paid user's
    // persisted theme on every cold launch. Reversion happens only via the post-load callback.
    let storedID = defaults.string(forKey: PersistenceKeys.selectedThemeID)
    if let id = storedID, let theme = ThemeRegistry.theme(forID: id) {
      current = theme // adopt as-is; no write-back
    } else if storedID == nil {
      current = .default // missing key: default in memory, no write-back
    } else {
      current = .default // unknown ID: fall back and overwrite the stale value
      defaults.set(Theme.default.id, forKey: PersistenceKeys.selectedThemeID)
    }

    let storedScheme = defaults.string(forKey: PersistenceKeys.appColorSchemePreference)
    if let raw = storedScheme, let preference = AppColorSchemePreference(rawValue: raw) {
      colorSchemePreference = preference
    } else if storedScheme == nil {
      colorSchemePreference = .system // missing key: no write-back
    } else {
      colorSchemePreference = .system // unknown raw value: fall back and overwrite
      defaults.set(AppColorSchemePreference.system.rawValue,
                   forKey: PersistenceKeys.appColorSchemePreference)
    }

    // StoreService is constructed before ThemeService, so this callback is wired before
    // any Transaction.updates emission.
    store.onEntitlementsChanged = { [weak self] in
      self?.revertToDefaultIfInaccessible()
    }
  }

  func setCurrent(_ theme: Theme) throws {
    guard Self.isAccessible(theme, store: store) else {
      throw ThemeServiceError.notOwned(productID: theme.productID ?? "")
    }
    current = theme
    defaults.set(theme.id, forKey: PersistenceKeys.selectedThemeID)
  }

  func setColorSchemePreference(_ preference: AppColorSchemePreference) {
    colorSchemePreference = preference
    defaults.set(preference.rawValue, forKey: PersistenceKeys.appColorSchemePreference)
  }

  /// Theme-forced override wins; otherwise the user's global preference applies.
  var effectiveColorScheme: ColorScheme? {
    current.preferredColorScheme ?? colorSchemePreference.colorScheme
  }

  func availableToCurrentUser() -> [Theme] {
    ThemeRegistry.allThemes.filter { Self.isAccessible($0, store: store) }
  }

  /// Re-reads both keys so a mid-session backup import (`AppState.notifyDataRestored`)
  /// takes effect without a cold launch. Unlike `init`, the restore caller is the only
  /// production path here, so this branch *does* enforce ownership: a backup imported from a
  /// different Apple ID can carry a paid theme this user doesn't own, and without the check
  /// the paid theme would render for free until the next entitlement walk (which never fires
  /// on the restore path). Unknown stored values are corrected with a write-back, mirroring
  /// `init`'s ladder.
  func refreshFromUserDefaults() {
    let resolvedTheme = resolveThemeFromDefaults()
    if resolvedTheme.id != current.id {
      current = resolvedTheme
      if resolvedTheme.id == Theme.default.id {
        AccessibilityNotification.Announcement(L10n.Settings.Appearance.Accessibility.themeReverted).post()
      }
    }

    let resolvedScheme = resolveSchemePreferenceFromDefaults()
    if resolvedScheme != colorSchemePreference { colorSchemePreference = resolvedScheme }
  }

  private func resolveThemeFromDefaults() -> Theme {
    let storedID = defaults.string(forKey: PersistenceKeys.selectedThemeID)
    if let id = storedID, let theme = ThemeRegistry.theme(forID: id) {
      if Self.isAccessible(theme, store: store) { return theme }
      // Known but inaccessible. Only revert + overwrite once entitlements are authoritative
      // (`loadState == .loaded`). Before then `ownedThemeIDs` may be empty merely because the
      // walk hasn't populated it (idle/loading) or failed (offline) — wiping the persisted
      // selection there downgrades a paid user who restored a backup before the walk finished.
      // Adopt the stored theme in memory without write-back, mirroring `init`; the post-load
      // listener reverts it later if it is genuinely unowned.
      guard store.loadState == .loaded else { return theme }
      defaults.set(Theme.default.id, forKey: PersistenceKeys.selectedThemeID)
      return .default
    }
    if storedID == nil { return .default } // missing key: no write-back
    // Unknown ID (e.g., future-build theme via backup): fall back + corrective overwrite.
    defaults.set(Theme.default.id, forKey: PersistenceKeys.selectedThemeID)
    return .default
  }

  private func resolveSchemePreferenceFromDefaults() -> AppColorSchemePreference {
    let storedScheme = defaults.string(forKey: PersistenceKeys.appColorSchemePreference)
    if let raw = storedScheme, let preference = AppColorSchemePreference(rawValue: raw) {
      return preference
    }
    if storedScheme == nil { return .system } // missing key: no write-back
    // Unknown raw value (e.g., a future-build case via backup): fall back + corrective overwrite.
    defaults.set(AppColorSchemePreference.system.rawValue,
                 forKey: PersistenceKeys.appColorSchemePreference)
    return .system
  }

  /// Idempotent: invoked from `StoreService.onEntitlementsChanged`. If the current theme is no
  /// longer accessible (e.g. a refund), revert to default, persist, and announce for VoiceOver.
  ///
  /// Acts only once entitlements are authoritative (`loadState == .loaded`). The first walk fires
  /// this from inside `load()` while still `.loading`, and on a cold storekitd that walk can read
  /// an empty `ownedThemeIDs` for a user who does own the theme — persisting a revert there would
  /// wipe their selection (and the listener never re-applies, so it is unrecoverable until they
  /// re-select). Post-load walks (refund, restore) fire this while `.loaded`, where empty ownership
  /// is trustworthy.
  private func revertToDefaultIfInaccessible() {
    guard store.loadState == .loaded else { return }
    guard !Self.isAccessible(current, store: store) else { return }
    current = .default
    defaults.set(Theme.default.id, forKey: PersistenceKeys.selectedThemeID)
    AccessibilityNotification.Announcement(L10n.Settings.Appearance.Accessibility.themeReverted).post()
  }

  private static func isAccessible(_ theme: Theme, store: StoreService) -> Bool {
    #if SIDELOAD
      // Sideload builds have no App Store entitlement, so StoreKit can never populate
      // ownedThemeIDs. Unlock every theme rather than leaving paid themes permanently locked.
      return true
    #else
      guard let productID = theme.productID else { return true } // default is always accessible
      return store.ownedThemeIDs.contains(productID)
    #endif
  }
}
