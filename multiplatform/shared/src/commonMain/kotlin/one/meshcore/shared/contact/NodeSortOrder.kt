package one.meshcore.shared.contact

/**
 * The Nodes-list sort orders, surfaced in the options menu. Kotlin port of the iOS `NodeSortOrder`;
 * [raw] is the value persisted under [SORT_ORDER_PREFERENCE_KEY].
 *
 * When [DISTANCE] is selected but no user location is available the list falls back to [LAST_HEARD]
 * for the actual sort while the stored preference stays [DISTANCE] (see [ContactListFiltering]).
 */
enum class NodeSortOrder(val raw: String) {
    LAST_HEARD("lastHeard"),
    NAME("name"),
    DISTANCE("distance"),
    HOPS("hops"),
    ;

    companion object {
        /** The order for [raw], or [LAST_HEARD] as the default for an unknown stored value. */
        fun fromRaw(raw: String?): NodeSortOrder = entries.firstOrNull { it.raw == raw } ?: LAST_HEARD

        /**
         * The preference key the sort order persists under. Mirrors the iOS `AppStorageKey.nodesSortOrder`
         * raw value so the two platforms name the same setting.
         */
        const val SORT_ORDER_PREFERENCE_KEY: String = "nodesSortOrder"

        /**
         * The key Discover's own sort order persists under, mirroring iOS `AppStorageKey.discoverySortOrder`.
         * Deliberately distinct from [SORT_ORDER_PREFERENCE_KEY]: the two screens remember their order
         * independently, and the iOS backup carries both keys separately.
         */
        const val DISCOVERY_SORT_ORDER_PREFERENCE_KEY: String = "discoverySortOrder"
    }
}
