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

        /// The named application themes.
        public static let individualIDs: Set<String> =
            [ember, fern, marine, olive, lavender, sakura]

        /// The reference-palette themes (Solarized, Nord, Catppuccin).
        public static let referenceIDs: Set<String> =
            [solarized, nord, catppuccin]

        /// Every theme a user can buy on its own. The `bundleAll` purchase grants this entire set,
        /// and the entitlement walker uses it to decide whether a purchased product unlocks itself.
        public static let purchasableIndividually: Set<String> =
            individualIDs.union(referenceIDs)

        /// All theme-related product IDs, including the bundle.
        public static let all: Set<String> =
            individualIDs.union(referenceIDs).union([bundleAll])
    }

    public enum Tip {
        public static let coffee = "io.pocketmesh.app.tip.coffee"
        public static let lunch = "io.pocketmesh.app.tip.lunch"
        public static let dinner = "io.pocketmesh.app.tip.dinner"
        public static let generous = "io.pocketmesh.app.tip.generous"
        public static let massive = "io.pocketmesh.app.tip.massive"
        public static let epic = "io.pocketmesh.app.tip.epic"

        public static let all: Set<String> = [coffee, lunch, dinner, generous, massive, epic]
    }

    public static let allProductIDs: Set<String> =
        Theme.all.union(Tip.all)
}
