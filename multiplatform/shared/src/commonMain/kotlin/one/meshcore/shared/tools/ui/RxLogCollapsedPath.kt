package one.meshcore.shared.tools.ui

import one.meshcore.shared.ui.truncatedPathHopLabels

/** Hop count, path names, and the labels both the chips and the expanded line share. */
internal data class RxLogPathFacts(
    val pathNodesEmpty: Boolean,
    val hopCount: Int,
    val pathLabels: List<String>,
    val directLabel: String,
    val hopSingular: String,
    val hopPlural: String,
)

/** Collapsed path chips and the single label VoiceOver reads for that row. */
internal data class RxLogCollapsedPath(val chips: List<String>, val spoken: String)

internal fun rxLogCollapsedPath(path: RxLogPathFacts): RxLogCollapsedPath {
    val chips = when {
        path.pathLabels.isEmpty() -> emptyList()
        else -> truncatedPathHopLabels(path.pathLabels, PATH_TRUNCATION_THRESHOLD, PATH_TRUNCATION_ENDS)
    }
    val spoken = when {
        path.pathNodesEmpty -> path.directLabel
        path.pathLabels.isEmpty() -> "${path.hopCount} ${if (path.hopCount == 1) path.hopSingular else path.hopPlural}"
        else -> chips.joinToString(" ")
    }
    return RxLogCollapsedPath(chips, spoken)
}

/** Quoted decoded text when the packet decrypted; otherwise the type, version, and size. */
internal fun rxLogRowPreview(
    decodedText: String?,
    typeName: String,
    version: Int,
    size: Int,
    bytesLabel: String,
): String = decodedText?.let { "\"$it\"" }
    ?: rxLogPayloadTypePreview(typeName, version, size, bytesLabel)

/** The expanded path line: Direct, hop count alone, or hop count plus the hop names. */
internal fun rxLogPathDetail(path: RxLogPathFacts): String {
    val hopWord = if (path.hopCount == 1) path.hopSingular else path.hopPlural
    return if (path.pathNodesEmpty && path.pathLabels.isEmpty()) {
        path.directLabel
    } else if (path.pathLabels.isNotEmpty()) {
        "${path.hopCount} $hopWord [${path.pathLabels.joinToString(", ")}]"
    } else {
        "${path.hopCount} $hopWord"
    }
}
