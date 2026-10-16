package one.meshcore.shared.pathedit

/**
 * The outcome of bulk-adding repeater codes, ported from the iOS `CodeInputResult`. Codes are bucketed
 * by fate so the UI can report each group. The iOS `errorMessage` (which joins the buckets through
 * `CodeInput.Error.*` L10n keys with " · ") is a presentation concern built by the UI
 * layer from these buckets, keeping this logic slice free of the string system.
 */
data class CodeInputResult(
    val added: List<String> = emptyList(),
    val notFound: List<String> = emptyList(),
    val alreadyInPath: List<String> = emptyList(),
    val invalidFormat: List<String> = emptyList(),
) {
    /** True when any code failed to add for a reportable reason (path-full codes are skipped silently). */
    val hasErrors: Boolean
        get() = notFound.isNotEmpty() || alreadyInPath.isNotEmpty() || invalidFormat.isNotEmpty()
}

/**
 * Hops to append, keys to remember, and the buckets shown for a bulk add. Path-full codes are omitted
 * from every list so they stay silent.
 */
internal data class HopCodeBuckets(
    val result: CodeInputResult,
    val hops: List<PathHop>,
    val recordKeys: List<ByteArray>,
)

internal fun bucketHopCodes(entries: List<HopCodeClassification>): HopCodeBuckets {
    val added = mutableListOf<String>()
    val notFound = mutableListOf<String>()
    val alreadyInPath = mutableListOf<String>()
    val invalidFormat = mutableListOf<String>()
    val hops = mutableListOf<PathHop>()
    val recordKeys = mutableListOf<ByteArray>()
    for (entry in entries) {
        when (val status = entry.status) {
            is HopCodeStatus.WillAdd -> {
                hops += status.hop
                status.hop.publicKey?.let { recordKeys += it }
                added += entry.code
            }
            HopCodeStatus.AlreadyInPath -> alreadyInPath += entry.code
            HopCodeStatus.NotFound -> notFound += entry.code
            HopCodeStatus.InvalidFormat -> invalidFormat += entry.code
            HopCodeStatus.PathFull -> Unit
        }
    }
    return HopCodeBuckets(
        result = CodeInputResult(
            added = added,
            notFound = notFound,
            alreadyInPath = alreadyInPath,
            invalidFormat = invalidFormat,
        ),
        hops = hops,
        recordKeys = recordKeys,
    )
}
