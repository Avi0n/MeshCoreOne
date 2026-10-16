package one.meshcore.shared.trace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.PrefixCandidate
import one.meshcore.shared.contact.asPrefixCandidate
import one.meshcore.shared.model.prefixBytes
import one.meshcore.shared.pathedit.CodeInputResult
import one.meshcore.shared.pathedit.HopCodeClassification
import one.meshcore.shared.pathedit.HopCodeParser
import one.meshcore.shared.pathedit.HopPickerSource
import one.meshcore.shared.pathedit.PathEditState
import one.meshcore.shared.pathedit.PickerNode
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.pathedit.ResolvedNode
import one.meshcore.shared.pathedit.bucketHopCodes
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver
import one.meshcore.shared.pathedit.PathHop as EditableHop

/** Shared edit state for [TracePathBuilder] and the hop picker it delegates to. */
internal class TracePathParts(
    val scope: CoroutineScope,
    val dependencies: TracePathBuilder.Dependencies,
    val recents: RecentHopsStore?,
    val logWarning: ((String) -> Unit)? = null,
) {
    var generation = 0L
    val recentsJobs = mutableSetOf<Job>()
    val editState = MutableStateFlow(PathEditState())
    val traceHashMode = MutableStateFlow<Int?>(null)
    val picker = TracePathPicker(this)
}

/** Add-hop, bulk-code, and hash-width actions for [TracePathBuilder]. */
internal class TracePathPicker(private val parts: TracePathParts) : HopPickerSource {
    override val state: StateFlow<PathEditState> = parts.editState.asStateFlow()

    override val hopLimit: Int? = null

    /** The path_sz code this trace will declare. Mirrors iOS `effectiveTraceMode`. */
    val effectiveTraceMode: Int
        get() = parts.traceHashMode.value ?: parts.dependencies.devicePathHashMode() ?: MIN_TRACE_HASH_MODE

    /** Hop width in bytes for this trace (1/2/4), derived from [effectiveTraceMode]. */
    val hashSize: Int
        get() = traceHashSize(effectiveTraceMode.coerceIn(MIN_TRACE_HASH_MODE, MAX_TRACE_HASH_MODE))

    override fun appendHop(node: PickerNode) {
        val hop = EditableHop(
            hashBytes = node.publicKey.prefixBytes(hashSize),
            publicKey = node.publicKey,
            resolvedName = node.resolvableName,
        )
        parts.editState.update { it.copy(editablePath = it.editablePath + hop) }
        recordRecents(listOf(node.publicKey))
    }

    override fun classifyCodes(input: String): List<HopCodeClassification> {
        val snapshot = parts.editState.value
        val existing = snapshot.editablePath.map { it.hashBytes.toList() }.toSet()
        val contacts = snapshot.allContacts.map { it.asPrefixCandidate() }
        val discovered = snapshot.discoveredNodes.map { it.asPrefixCandidate() }
        return HopCodeParser.classify(input, hashSize, existing, remainingCapacity = null) { hash ->
            resolveHop(hash, contacts, discovered)
                ?.let { ResolvedNode(it.publicKey, it.displayName) }
        }
    }

    override fun addCodes(input: String): CodeInputResult {
        val bucket = bucketHopCodes(classifyCodes(input))
        if (bucket.hops.isNotEmpty()) {
            parts.editState.update { it.copy(editablePath = it.editablePath + bucket.hops) }
            recordRecents(bucket.recordKeys)
        }
        return bucket.result
    }

    override fun adoptHashSize(pastedCodes: String) {
        if (!parts.dependencies.supportsHashSizeOverride()) return
        val mode = inferredTraceHashMode(pastedCodes) ?: return
        if (mode == effectiveTraceMode) return
        setTraceHashMode(mode)
    }

    fun setTraceHashMode(mode: Int) {
        val clamped = mode.coerceIn(MIN_TRACE_HASH_MODE, MAX_TRACE_HASH_MODE)
        parts.traceHashMode.value = clamped
        val width = traceHashSize(clamped)
        parts.editState.update { snapshot ->
            snapshot.copy(
                editablePath = snapshot.editablePath.map { hop ->
                    val source = hop.publicKey ?: hop.hashBytes
                    EditableHop(widenedHashBytes(source, width), hop.publicKey, hop.resolvedName)
                },
            )
        }
    }

    fun resolveHop(hash: ByteArray, contacts: List<PrefixCandidate>, discovered: List<PrefixCandidate>) =
        NeighborPrefixResolver.resolveCandidates(hash, contacts, discovered, parts.dependencies.userLocation())

    private fun recordRecents(keys: List<ByteArray>) {
        val store = parts.recents ?: return
        val radioID = parts.dependencies.radioId() ?: return
        if (keys.isEmpty()) return
        val generation = parts.generation
        val isCurrent = { generation == parts.generation && parts.dependencies.radioId() == radioID }
        val job = parts.scope.launch(start = CoroutineStart.LAZY) {
            var current = parts.editState.value.recentPublicKeys
            for (key in keys) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@launch
                current = store.record(key, current, radioID)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return@launch
            parts.editState.update { it.copy(recentPublicKeys = current) }
        }
        parts.recentsJobs += job
        job.invokeOnCompletion { parts.recentsJobs -= job }
        job.start()
    }
}
