package one.meshcore.shared.trace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.asPrefixCandidate
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.HopPickerSource
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.pathedit.moved
import one.meshcore.shared.pathedit.removingAt
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.pathedit.PathHop as EditableHop

/**
 * The trace path being built: the ordered hops, the nodes available to add, and the effective hop-hash
 * width. Ported from the path-building half of the iOS `TracePathViewModel` and its `HopPickerSource`
 * conformance, so the shared Add-Hop picker drives the trace builder exactly as it drives the contact
 * path editor.
 *
 * Two rules separate this from the contact editor: a trace path is uncapped ([hopLimit] is `null`), and
 * its hop width is a power-of-2 trace width (1/2/4 bytes) that may deviate from the radio's routing
 * width when the firmware honors a per-trace override. Picker adds and bulk codes delegate to
 * [TracePathPicker].
 */
class TracePathBuilder internal constructor(private val parts: TracePathParts) : HopPickerSource by parts.picker {
    private var loadGeneration = 0L
    constructor(
        scope: CoroutineScope,
        dependencies: Dependencies,
        recents: RecentHopsStore? = null,
        logWarning: ((String) -> Unit)? = null,
    ) : this(TracePathParts(scope, dependencies, recents, logWarning))

    /**
     * Live, per-connection inputs read at each use; a provider returning `null` mirrors a disconnected
     * state. [devicePathHashMode] is the connected radio's configured `pathHashMode` — the effective
     * trace mode whenever the user has set no per-trace override (iOS `effectiveTraceMode`).
     */
    class Dependencies(
        val contactService: () -> ContactService?,
        val radioId: () -> Uuid?,
        val devicePathHashMode: () -> Int? = { null },
        val supportsHashSizeOverride: () -> Boolean = { false },
        val discoveredNodes: suspend (Uuid) -> List<DiscoveredNode> = { emptyList() },
        /**
         * This device's position for prefix collisions (iOS `bestAvailableLocation`). Read at each
         * resolve so a fix arriving mid-session lands on the next paste or saved-path load.
         */
        val userLocation: () -> UserLocation? = { null },
    )

    /**
     * The per-trace hash-size override (path_sz code 0/1/2), or `null` to follow the radio's configured
     * `pathHashMode`. Observable so the width chips and the hop rows re-render together.
     */
    val traceHashMode: StateFlow<Int?> = parts.traceHashMode.asStateFlow()

    /** The ordered hops of the outbound path. */
    val outboundPath: List<EditableHop> get() = parts.editState.value.editablePath

    /** The path_sz code this trace will declare. Mirrors iOS `effectiveTraceMode`. */
    val effectiveTraceMode: Int get() = parts.picker.effectiveTraceMode

    /** Hop width in bytes for this trace (1/2/4), derived from [effectiveTraceMode]. */
    val hashSize: Int get() = parts.picker.hashSize

    /** The full on-wire path, optionally mirrored for the return leg. Mirrors iOS `fullPathData`. */
    fun fullPathBytes(autoReturnPath: Boolean): ByteArray = buildFullPathBytes(
        outboundPath.map { PathHop(it.hashBytes, it.publicKey, it.resolvedName) },
        autoReturnPath,
    )

    /** Comma-separated uppercase hex of the outbound hops, for display and copy. */
    val outboundPathString: String get() = outboundPath.joinToString(separator = ",") { it.hashHex }

    /**
     * Comma-separated uppercase hex of [fullPathBytes], chunked by hop width. Matches iOS
     * `fullPathString` so Copy Path includes the auto-return mirror when that toggle is on.
     */
    fun fullPathString(autoReturnPath: Boolean): String {
        val data = fullPathBytes(autoReturnPath)
        val size = (outboundPath.firstOrNull()?.hashBytes?.size ?: hashSize).coerceAtLeast(1)
        return data.toList().chunked(size).joinToString(",") { chunk ->
            chunk.toByteArray().toUppercaseHex()
        }
    }

    /** Contacts available as hops (repeaters and rooms), for hash resolution by callers. */
    val availableNodes: List<ContactDto> get() = parts.editState.value.availableNodes

    /** Loads repeaters, rooms, discovered nodes, and recents for the connected radio. */
    suspend fun load() {
        val radioID = parts.dependencies.radioId() ?: return
        val service = parts.dependencies.contactService()
        val generation = ++loadGeneration
        val isCurrent = {
            generation == loadGeneration &&
                parts.dependencies.radioId() == radioID &&
                parts.dependencies.contactService() === service
        }
        loadTracePathContacts(
            load = {
                val recents = parts.recents?.load(radioID)
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@loadTracePathContacts
                if (service == null) {
                    parts.editState.update { it.copy(recentPublicKeys = recents ?: emptyList()) }
                    return@loadTracePathContacts
                }
                val contacts = service.getContacts(radioID)
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@loadTracePathContacts
                val discovered = parts.dependencies.discoveredNodes(radioID)
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@loadTracePathContacts
                parts.editState.update {
                    it.copy(
                        recentPublicKeys = recents ?: emptyList(),
                        allContacts = contacts,
                        availableRepeaters = contacts.filter { c -> c.contactType == ContactType.REPEATER },
                        availableRooms = contacts.filter { c -> c.contactType == ContactType.ROOM },
                        discoveredNodes = discovered,
                    )
                }
            },
            onFault = {
                if (isCurrent()) {
                    parts.editState.update {
                        it.copy(
                            allContacts = emptyList(),
                            availableRepeaters = emptyList(),
                            availableRooms = emptyList(),
                            discoveredNodes = emptyList(),
                        )
                    }
                }
            },
            logWarning = parts.logWarning ?: { line ->
                PersistentLogger(parts.scope, category = TRACE_PATH_LOG).warning(line)
            },
        )
    }

    /** Removes the hop at [index], bounds-checked. Mirrors iOS `removeRepeater(at:)`. */
    fun removeHop(index: Int) = parts.editState.update { state ->
        state.editablePath.removingAt(index)?.let { state.copy(editablePath = it) } ?: state
    }

    /** Moves the hop at [from] to [to], using SwiftUI `onMove` offset semantics. Mirrors iOS `moveRepeater`. */
    fun moveHop(from: Int, to: Int) = parts.editState.update { state ->
        state.editablePath.moved(from, to)?.let { state.copy(editablePath = it) } ?: state
    }

    /**
     * Empties the path and drops the per-trace hash override so the next hop uses the radio's
     * configured width. Mirrors iOS `TracePathViewModel.clearPath`.
     */
    fun clearPath() {
        parts.traceHashMode.value = null
        parts.editState.update { it.copy(editablePath = emptyList()) }
    }

    /** A retired radio must not publish a suspended picker load into its replacement. */
    fun reset() {
        loadGeneration += 1L
        parts.generation += 1L
        parts.recentsJobs.forEach { it.cancel() }
        parts.recentsJobs.clear()
        parts.traceHashMode.value = null
        parts.editState.value = one.meshcore.shared.pathedit.PathEditState()
    }

    /**
     * Replaces the path with hops resolved from a saved path's hash slices, adopting that path's stored
     * width as the per-trace override. Mirrors iOS `loadSavedPath`.
     */
    fun loadSavedPath(fullPath: ByteArray, savedHashSize: Int) {
        // A radio that ignores the per-trace override decodes at its configured pathHashMode, so adopting
        // the saved width would declare one width in the flags byte and be read at another: the trace
        // resolves nothing. Mirrors iOS clearing traceHashMode on such a radio.
        val honorsOverride = parts.dependencies.supportsHashSizeOverride() && savedHashSize in VALID_TRACE_HASH_SIZES
        parts.traceHashMode.value = if (honorsOverride) traceHashModeForSize(savedHashSize) else null
        val snapshot = parts.editState.value
        val contacts = snapshot.allContacts.map { it.asPrefixCandidate() }
        val discovered = snapshot.discoveredNodes.map { it.asPrefixCandidate() }
        val hops = outboundHashSlices(fullPath, savedHashSize).map { slice ->
            val match = parts.picker.resolveHop(slice, contacts, discovered)
            EditableHop(slice, match?.publicKey, match?.displayName)
        }
        parts.editState.update { it.copy(editablePath = hops) }
    }

    /**
     * Sets the per-trace hash-size override and rebuilds every hop to the new uniform width (the flags
     * byte declares one width for the whole path). Mirrors iOS `setTraceHashMode`.
     */
    fun setTraceHashMode(mode: Int) = parts.picker.setTraceHashMode(mode)
}

/** Debug-log category for trace path building. */
internal const val TRACE_PATH_LOG = "TracePath"

/** Dump when the repeater and room lists for a trace cannot be read. */
internal const val TRACE_PATH_CONTACTS_FAILED = "Trace path contacts failed"

/**
 * Loads the contacts a trace path can use. A store fault is logged and [onFault] runs so the picker
 * lists clear. Cancellation and an Error propagate with the lists left as they were.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun loadTracePathContacts(
    load: suspend () -> Unit,
    onFault: () -> Unit,
    logWarning: (String) -> Unit,
) {
    try {
        load()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        logWarning("$TRACE_PATH_CONTACTS_FAILED: ${error.message ?: error}")
        onFault()
    }
}
