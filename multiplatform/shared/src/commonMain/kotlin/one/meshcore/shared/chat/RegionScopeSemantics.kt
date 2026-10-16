package one.meshcore.shared.chat

/** Dual-field read/write helpers for message and RX-log region labeling. */
sealed class RegionMatchResult {
    data object None : RegionMatchResult()
    data class Unique(val name: String) : RegionMatchResult()
    data class Ambiguous(val names: List<String>) : RegionMatchResult()
}

object RegionScopeSemantics {
    const val CHIP_NAME_SEPARATOR = " / "

    data class StorageFields(val regionScope: String?, val regionScopeMatches: List<String>)

    fun storageFields(from: RegionMatchResult): StorageFields = when (from) {
        RegionMatchResult.None -> StorageFields(null, emptyList())
        is RegionMatchResult.Unique -> {
            val trimmed = from.name.trim()
            if (trimmed.isEmpty()) StorageFields(null, emptyList()) else StorageFields(trimmed, listOf(trimmed))
        }
        is RegionMatchResult.Ambiguous -> {
            val filtered = filteredSortedNames(from.names)
            when (filtered.size) {
                0 -> StorageFields(null, emptyList())
                1 -> StorageFields(filtered[0], filtered)
                else -> StorageFields(null, filtered)
            }
        }
    }

    fun coalesce(scope: String?, matches: List<String>): RegionMatchResult {
        val filtered = filteredSortedNames(matches)
        return when (filtered.size) {
            0 -> {
                val trimmed = scope?.trim().orEmpty()
                if (trimmed.isEmpty()) RegionMatchResult.None else RegionMatchResult.Unique(trimmed)
            }
            1 -> RegionMatchResult.Unique(filtered[0])
            else -> RegionMatchResult.Ambiguous(filtered)
        }
    }

    fun chipLabel(from: RegionMatchResult): String? = when (from) {
        RegionMatchResult.None -> null
        is RegionMatchResult.Unique -> from.name
        is RegionMatchResult.Ambiguous -> from.names.joinToString(CHIP_NAME_SEPARATOR)
    }

    fun matchNames(from: RegionMatchResult): List<String> = when (from) {
        RegionMatchResult.None -> emptyList()
        is RegionMatchResult.Unique -> listOf(from.name)
        is RegionMatchResult.Ambiguous -> from.names
    }

    private fun filteredSortedNames(names: List<String>): List<String> =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
}
