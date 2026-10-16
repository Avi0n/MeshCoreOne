package one.meshcore.shared.channel

/** UI result from probing nearby repeaters for region names. */
sealed class RegionDiscoverUiResult {
    data object SendFailed : RegionDiscoverUiResult()
    data object NoRepeaters : RegionDiscoverUiResult()
    data object ErrorLoading : RegionDiscoverUiResult()

    /** Newly found names remain unpersisted until the user selects them. */
    data class Completed(val newRegions: List<String>, val allTableFull: Boolean) : RegionDiscoverUiResult()
}
