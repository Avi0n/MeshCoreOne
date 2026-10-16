package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.pathdetail.loadDiscoveredNodes
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Unresolved Tools → Node Discovery hits speak Unknown plus a 4-byte hex prefix, matching iOS
 * `NodeDiscoveryViewModel.resolveName`.
 */
internal fun unknownDiscoveredNodeName(unknownLabel: String, publicKey: ByteArray): String {
    val n = minOf(NodeDiscoveryViewModel.UNKNOWN_KEY_PREFIX_BYTES, publicKey.size)
    val prefix = if (n == 0) "" else publicKey.copyOfRange(0, n).toUppercaseHex()
    return "$unknownLabel ($prefix)"
}

/** Blank advert/contact names must not win over [unknownDiscoveredNodeName]. */
internal fun storedDiscoveryName(name: String): String? = name.trim().takeIf { it.isNotEmpty() }

/**
 * Active Tools → Node Discovery: issues [sendNodeDiscoverRequest], listens for tagged
 * [MeshEvent.DiscoverResponseReceived] for [scanDurationMs], and can import a hit into the
 * contact table. Pure of Compose; injectable deps keep desktop unit tests free of JNI.
 */
@OptIn(ExperimentalTime::class)
class NodeDiscoveryViewModel(
    private val dependencies: Dependencies,
    private val scanDurationMs: Long = DEFAULT_SCAN_DURATION_MS,
    private val nowEpochSeconds: () -> Long = { 0L },
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    data class Dependencies(
        val sendDiscover: suspend (filter: Int, prefixOnly: Boolean) -> Long,
        val events: () -> Flow<MeshEvent>?,
        val contactService: () -> ContactService?,
        val discoveredNodeStore: () -> DiscoveredNodeStore?,
        val radioId: () -> Uuid?,
        /**
         * Whether a radio session is live. Offline [ContactService] still exists for Added-badge
         * reads; Add is a device `CMD_ADD` that iOS gates on `appState.services?.contactService`.
         */
        val isRadioConnected: () -> Boolean = { false },
        val maxContacts: () -> Int? = { null },
        val notConnectedMessage: (NodeDiscoveryFilter) -> String,
        /** Current localized scan failure, resolved at the Compose boundary. */
        val scanFailedMessage: () -> String,
        /** Current iOS `NodeDiscovery.unknownNode` equivalent, resolved at the Compose boundary. */
        val unknownNodeLabel: () -> String,
        /** Localized text for every contact-service failure, resolved at the Compose boundary. */
        val errorText: (Throwable) -> String,
    )

    private val _state = MutableStateFlow(NodeDiscoveryState())
    val state: StateFlow<NodeDiscoveryState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var scanGeneration = 0L
    private var scanDeadlineMillis: Long? = null
    private val sessionChanges = MutableStateFlow(0L)
    private var addGeneration = 0L
    private val addOwners = mutableMapOf<String, Long>()
    private val addJobs = mutableSetOf<Job>()
    private var isClosed = false
    private val namesByKeyHex = mutableMapOf<String, String>()

    fun setFilter(filter: NodeDiscoveryFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setSortOrder(order: NodeDiscoverySortOrder) {
        _state.update { it.copy(sortOrder = order) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null, addError = null) }
    }

    /**
     * Starts (or restarts) a scan for the current filter. Cancels any in-flight scan first.
     * Safe to call from a composable [CoroutineScope].
     */
    fun scan(scope: CoroutineScope) {
        if (isClosed) return
        val generation = ++scanGeneration
        scanJob?.cancel()
        scanDeadlineMillis = null
        val filter = _state.value.filter
        val radio = dependencies.radioId()
        val owner = ScanOwner(generation, radio)
        if (dependencies.events() == null) {
            _state.update {
                it.copy(errorMessage = dependencies.notConnectedMessage(filter), isScanning = false)
            }
            return
        }
        _state.update {
            it.copy(
                isScanning = true,
                errorMessage = null,
                results = it.results.filterNot { row -> row.scanFilter == filter },
            )
        }
        scanJob = scope.launch {
            try {
                val owns = owner::isCurrent
                collectNodeDiscoveryScan(
                    NodeDiscoveryScanInputs(
                        dependencies = dependencies, filter = filter, durationMs = scanDurationMs,
                        sessionChanges = sessionChanges, owns = owns,
                        preloadNames = { preloadNames(owns) },
                        armDeadline = { scanDeadlineMillis = nowEpochMillis() + scanDurationMs },
                        onResponse = { response ->
                            _state.appendNodeDiscoveryResult(
                                namesByKeyHex,
                                response,
                                filter,
                                dependencies.unknownNodeLabel(),
                                nowEpochSeconds(),
                            )
                        },
                        onFailure = { _state.update { it.copy(errorMessage = dependencies.scanFailedMessage()) } },
                    ),
                )
            } finally {
                if (owner.isCurrent()) {
                    scanDeadlineMillis = null
                    scanJob = null
                    _state.update {
                        it.copy(
                            isScanning = false,
                            hasScannedOnce = true,
                        )
                    }
                }
            }
        }
    }

    private inner class ScanOwner(private val generation: Long, private val radio: Uuid?) {
        fun isCurrent(): Boolean {
            val liveRadio = dependencies.radioId()
            // A reconnect temporarily has no container; terminal loss retires the generation explicitly.
            return !isClosed && generation == scanGeneration && (liveRadio == null || liveRadio == radio)
        }
    }

    fun reattachIfWaiting() {
        if (_state.value.isScanning) sessionChanges.update { it + 1L }
    }

    fun expireScanIfDeadlinePassed() {
        val deadline = scanDeadlineMillis ?: return
        if (_state.value.isScanning && nowEpochMillis() >= deadline) stopScan()
    }

    /** Cancels the scan; a retired owner also drops its add work, caches, and radio-derived results. */
    fun stopScan(clearOwnedState: Boolean = false) {
        scanGeneration += 1
        scanDeadlineMillis = null
        scanJob?.cancel()
        scanJob = null
        if (clearOwnedState) {
            addGeneration += 1
            addOwners.clear()
            addJobs.toList().forEach { it.cancel() }
            addJobs.clear()
            namesByKeyHex.clear()
            _state.update {
                NodeDiscoveryState(
                    filter = it.filter,
                    sortOrder = it.sortOrder,
                )
            }
            return
        }
        _state.update {
            if (it.isScanning) it.copy(isScanning = false, hasScannedOnce = true) else it
        }
    }

    /** Closes a local screen owner. Retained workspaces reset only when their radio is retired. */
    fun close() {
        if (isClosed) return
        isClosed = true
        stopScan()
        addJobs.toList().forEach { it.cancel() }
        addJobs.clear()
    }

    /**
     * Adds [result] to the radio contact table via [ContactService.addOrUpdateContact], then refreshes
     * the Added badge set. A failed save reports the contact error. Cancellation and a thrown error
     * leave that error clear.
     */
    suspend fun addNode(result: NodeDiscoveryResult) {
        if (isClosed) return
        val radio = dependencies.radioId() ?: return
        val service = dependencies.contactService() ?: return
        val ticket = ++addGeneration
        val key = result.publicKeyHex
        addOwners[key] = ticket
        val owns = {
            !isClosed &&
                addOwners[key] == ticket &&
                dependencies.radioId() == radio &&
                dependencies.contactService() === service &&
                dependencies.isRadioConnected()
        }
        val addJob = currentCoroutineContext()[Job]
        if (addJob != null) addJobs += addJob
        try {
            performNodeDiscoveryAdd(
                dependencies,
                _state,
                namesByKeyHex,
                result,
                NodeDiscoveryAddOwner(service, radio, owns),
            )
        } finally {
            if (addJob != null) addJobs -= addJob
            if (addOwners[key] == ticket) {
                val active = owns()
                addOwners.remove(key)
                if (active && _state.value.addingPublicKeyHex == key) {
                    _state.update { it.copy(addingPublicKeyHex = null) }
                }
            }
        }
    }

    private suspend fun preloadNames(owns: () -> Boolean) {
        val radioID = dependencies.radioId() ?: return
        val baseline = namesByKeyHex.toMap()
        val baselineAdded = _state.value.addedPublicKeyHexes
        val names = mutableMapOf<String, String>()
        // iOS `loadNameResolutionData` logs a persist fault and still starts the scan.
        val nodes = loadDiscoveredNodes(radioID) { id ->
            dependencies.discoveredNodeStore()?.list(id).orEmpty()
        }
        currentCoroutineContext().ensureActive()
        if (!owns()) return
        nodes.forEach { node ->
            storedDiscoveryName(node.name)?.let { names[node.publicKey.toUppercaseHex()] = it }
        }
        val contacts = loadContacts(dependencies.contactService(), radioID)
        currentCoroutineContext().ensureActive()
        if (!owns()) return
        val added = mutableSetOf<String>()
        for (contact in contacts) {
            val hex = contact.publicKey.toUppercaseHex()
            storedDiscoveryName(contact.name)?.let { names[hex] = it }
            added += hex
        }
        val localUpdates = namesByKeyHex.filter { (key, value) -> baseline[key] != value }
        namesByKeyHex.clear()
        namesByKeyHex.putAll(names)
        namesByKeyHex.putAll(localUpdates)
        _state.update { it.copy(addedPublicKeyHexes = added + (it.addedPublicKeyHexes - baselineAdded)) }
    }

    companion object {
        const val DEFAULT_SCAN_DURATION_MS = 15_000L

        /** iOS stamps 0 on an active-scan add; the radio's last-advert clock is unknown. */
        const val ADDED_CONTACT_LAST_MODIFIED = 0L

        private const val BYTE_MASK = 0xFF
        private const val SHIFT_8 = 8
        private const val SHIFT_16 = 16
        private const val SHIFT_24 = 24
        internal const val UNKNOWN_KEY_PREFIX_BYTES = 4

        /** Little-endian 4-byte encoding of a UInt32 tag (matches Swift `withUnsafeBytes` LE). */
        fun tagToLittleEndianBytes(tag: Long): ByteArray {
            val u = tag.toInt()
            return byteArrayOf(
                (u and BYTE_MASK).toByte(),
                ((u ushr SHIFT_8) and BYTE_MASK).toByte(),
                ((u ushr SHIFT_16) and BYTE_MASK).toByte(),
                ((u ushr SHIFT_24) and BYTE_MASK).toByte(),
            )
        }
    }
}

/**
 * Firmware type byte for an active-scan add. Unknown values become repeater, matching
 * iOS `ContactType(rawValue:) ?? .repeater`.
 */
internal fun discoveryContactType(nodeType: Int): ContactType = ContactType.fromRaw(nodeType) ?: ContactType.REPEATER
