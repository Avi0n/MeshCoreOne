import Foundation

/// Single source of truth for every in-app-purchase product identifier.
/// Adding a new theme, bundle, or tip means adding a constant here.
public enum StoreCatalog {
    public enum Theme {
        public static let nightops = "io.pocketmesh.app.theme.nightops"
        public static let topographer = "io.pocketmesh.app.theme.topographer"
        public static let marine = "io.pocketmesh.app.theme.marine"
        public static let tactical = "io.pocketmesh.app.theme.tactical"
        public static let rose = "io.pocketmesh.app.theme.rose"
        public static let lavender = "io.pocketmesh.app.theme.lavender"
        public static let sakura = "io.pocketmesh.app.theme.sakura"
        public static let bundleAll = "io.pocketmesh.app.theme.bundle.all"

        /// The seven individual theme product IDs (excludes the bundle).
        public static let individualIDs: Set<String> =
            [nightops, topographer, marine, tactical, rose, lavender, sakura]

        /// All theme-related product IDs, including the bundle.
        public static let all: Set<String> =
            individualIDs.union([bundleAll])
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
