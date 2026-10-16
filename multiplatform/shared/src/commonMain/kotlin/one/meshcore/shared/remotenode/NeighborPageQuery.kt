package one.meshcore.shared.remotenode

import kotlin.time.Duration

/** One neighbour page. Defaults match the repeater's first page. */
data class NeighborPageQuery(
    val count: Int = DEFAULT_COUNT,
    val offset: Int = 0,
    val orderBy: NeighborSortOrder = NeighborSortOrder.NEWEST_FIRST,
    val pubkeyPrefixLength: Int = DEFAULT_PUBKEY_PREFIX_LENGTH,
    val timeout: Duration? = null,
) {
    companion object {
        const val DEFAULT_COUNT = 20
        const val DEFAULT_PUBKEY_PREFIX_LENGTH = 6
    }
}
