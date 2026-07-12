import Foundation

/// Single source of truth for every in-app-purchase product identifier.
/// Adding a new theme, bundle, or tip means adding a constant here.
public enum StoreCatalog {
  public enum Theme {
    public static let ember = "io.pocketmesh.app.theme.ember"
    public static let fern = "io.pocketmesh.app.theme.fern"
    public static let marine = "io.pocketmesh.app.theme.marine"
    public static let olive = "io.pocketmesh.app.theme.olive"
    public static let lavender = "io.pocketmesh.app.theme.lavender"
    public static let sakura = "io.pocketmesh.app.theme.sakura"
    public static let solarized = "io.pocketmesh.app.theme.solarized"
    public static let nord = "io.pocketmesh.app.theme.nord"
    public static let catppuccin = "io.pocketmesh.app.theme.catppuccin"
    public static let bundleAll = "io.pocketmesh.app.theme.bundle.all"

    /// Every theme the `bundleAll` purchase unlocks. Themes are not sold individually — the
    /// bundle is the only theme purchase — so this set is purely the bundle's entitlement
    /// expansion and the per-theme ownership keys used to render locked/owned state.
    public static let bundledThemeIDs: Set<String> =
      [ember, fern, marine, olive, lavender, sakura, solarized, nord, catppuccin]
  }

  public enum Tip {
    public static let coffee = "io.pocketmesh.app.tips.coffee"
    public static let lunch = "io.pocketmesh.app.tips.lunch"
    public static let dinner = "io.pocketmesh.app.tips.dinner"
    public static let generous = "io.pocketmesh.app.tips.generous"
    public static let massive = "io.pocketmesh.app.tips.massive"
    public static let epic = "io.pocketmesh.app.tips.epic"

    public static let all: Set<String> = [coffee, lunch, dinner, generous, massive, epic]
  }

  /// The product IDs the app fetches from the App Store and sells: the All Themes bundle and the
  /// tips. Individual themes are not sold, so they are never requested from StoreKit — the bundle
  /// purchase confers them as entitlements via `Theme.bundledThemeIDs`.
  public static let sellableProductIDs: Set<String> =
    Tip.all.union([Theme.bundleAll])
}
