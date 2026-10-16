package one.meshcore.shared.contact

/**
 * The Nodes-list filter segments, rendered as a pinned section-header picker and hidden while
 * searching (search spans all types). Kotlin port of the iOS `NodeSegment`; [raw] is the persisted
 * string the picker and any future preference agree on.
 *
 * [CONTACTS] is the default selection; on the first successful load the list auto-selects [FAVORITES]
 * when any favorite exists and the user has not switched (see [ContactListViewModel]).
 */
enum class NodeSegment(val raw: String) {
    FAVORITES("favorites"),
    CONTACTS("contacts"),
    REPEATERS("repeaters"),
    ROOMS("rooms"),
}
