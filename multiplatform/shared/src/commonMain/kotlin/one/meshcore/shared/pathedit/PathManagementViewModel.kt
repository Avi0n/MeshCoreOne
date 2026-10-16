package one.meshcore.shared.pathedit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.lifecycle.OwnedJobs
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.model.Uuid
import kotlin.math.min

/**
 * Drives the contact path editor, ported from the iOS `PathManagementViewModel`. Plain over a
 * [CoroutineScope] and provider closures (the Kotlin analogue of the iOS `@MainActor () -> T?` providers)
 * so it is unit-testable on the desktop JVM with fakes, with the observable surface as a [StateFlow].
 *
 * The pure logic ([HopCodeParser], [HopNodeMatching], [HopResolution], [PathEditLogic], [RecentHopsStore])
 * lives in [PathEditState]; this view model wires it to the live connection and bridged [ContactService].
 * Editing writes only `outPathLength`/`outPath`; saves go through the service's whole-path replace
 * (last-write-wins by design — no merge). The direct-routing action is the one place `outPathLength` is
 * written as `0x00`, and it means a real learned 0-hop route, not a derived label.
 */
class PathManagementViewModel(
    scope: CoroutineScope,
    internal val recents: RecentHopsStore,
    internal val dependencies: Dependencies,
) : HopPickerSource {
    /**
     * The live, per-connection inputs read at each use. A provider returning `null` mirrors a disconnected
     * state, so unconfigured calls are no-ops. [hashSize] is the connected device's key-prefix size
     * (iOS `connectedDevice?.hashSize`); [discoveredNodes] is the passively-heard node source (no reactive
     * Kotlin discovered-node DTO exists yet, so it defaults to empty).
     */
    data class Dependencies(
        val contactService: () -> ContactService?,
        val radioId: () -> Uuid?,
        /**
         * Whether a radio session is live. Offline [ContactService] still exists for hop-name
         * lookups; iOS path save/reset/discover require `appState.services`.
         */
        val isRadioConnected: () -> Boolean = { true },
        val hashSize: () -> Int? = { null },
        val discoveredNodes: suspend (Uuid) -> List<DiscoveredNode> = { emptyList() },
        val onContactNeedsRefresh: () -> Unit = {},
        /** Localized text for every contact-service failure, resolved at the Compose boundary. */
        val errorText: (Throwable) -> String,
        /**
         * A failed path-discovery resend. The wait continues. Production passes a logger warning.
         */
        val warning: (String) -> Unit = {},
    )

    /** Same-package editors write this flow. Callers read [state]. */
    internal val editState = MutableStateFlow(PathEditState())
    internal val jobs = OwnedJobs(scope)
    override val state: StateFlow<PathEditState> = editState.asStateFlow()

    internal var discoveryJob: Job? = null
    internal var countdownJob: Job? = null

    /** The contact a discovery is in flight for, so a push is matched to it and non-matching pushes ignored. */
    internal var discoveringContact: ContactDto? = null

    /** Current hash size, clamped to 1..3 so a reserved mode-3 never trips `encodePathLen`. */
    fun hashSize(): Int = (dependencies.hashSize() ?: DEFAULT_HASH_SIZE).coerceIn(MIN_HASH_SIZE, MAX_HASH_SIZE)

    /** Maximum hops under the current hash size: bounded by the 6-bit field and the 64-byte payload. */
    fun maxHopCount(): Int = min(HOP_FIELD_CAP, PATH_BYTE_BUDGET / hashSize())

    override val hopLimit: Int? get() = maxHopCount()

    override fun appendHop(node: PickerNode) = insert(node.publicKey, node.resolvableName)

    override fun classifyCodes(input: String): List<HopCodeClassification> {
        val snapshot = editState.value
        val existing = snapshot.editablePath.map { it.hashBytes.toList() }.toSet()
        val remaining = (maxHopCount() - snapshot.editablePath.size).coerceAtLeast(0)
        return HopCodeParser.classify(input, hashSize(), existing, remaining) { hash ->
            HopResolution.resolvePublicKey(hash, snapshot.allContacts, snapshot.discoveredNodes)?.let { pk ->
                ResolvedNode(pk, HopResolution.resolveName(hash, snapshot.allContacts, snapshot.discoveredNodes))
            }
        }
    }

    override fun addCodes(input: String): CodeInputResult {
        if (jobs.isClosed) return CodeInputResult()
        val bucket = bucketHopCodes(classifyCodes(input))
        if (bucket.hops.isNotEmpty()) {
            editState.update { it.copy(editablePath = it.editablePath + bucket.hops) }
            jobs.launch { recordRecents(bucket.recordKeys) }
        }
        return bucket.result
    }

    internal companion object {
        const val DIRECT_ROUTING_PATH_LENGTH = 0x00
        const val COUNTDOWN_TICK_MS = 5_000L
        const val MILLIS_PER_SECOND = 1_000.0
        const val DEFAULT_HASH_SIZE = 1
        const val MIN_HASH_SIZE = 1
        const val MAX_HASH_SIZE = 3
        const val HOP_FIELD_CAP = 63
        const val PATH_BYTE_BUDGET = 64
    }
}

/**
 * Whether this response refers to [publicKey]. The wire carries only a key prefix; an empty prefix never
 * matches (rather than matching every key), mirroring the iOS `PathInfo.matches(publicKey:)`.
 */
internal fun PathInfo.matches(publicKey: ByteArray): Boolean = publicKeyPrefix.isNotEmpty() &&
    publicKey.size >= publicKeyPrefix.size &&
    publicKey.copyOfRange(0, publicKeyPrefix.size).contentEquals(publicKeyPrefix)

/**
 * Hop count decoded from `out_path_len`; `null` when the byte uses the reserved hash-size mode and can't
 * decode, so callers treat it as no valid path instead of defaulting to direct. Mirrors iOS `outHopCount`.
 */
internal fun PathInfo.outHopCount(): Int? = PathEncoding.decodePathLen(outPathLength)?.hopCount

/** iOS path-editor log category. */
internal const val PATH_MANAGEMENT_LOG_CATEGORY = "PathManagement"
