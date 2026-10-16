package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.RemoteSectionRetryPolicy
import one.meshcore.shared.remotenode.RepeaterAdminService
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/** A surfaced section-load error; the composable maps each case to its `Status.*` string resource. */
sealed interface NodeSectionError {
    /** The node did not respond before the firmware timeout. */
    data object Timeout : NodeSectionError

    /** A lower-layer failure without a usable message; the UI maps this to a localized generic string. */
    data object Failed : NodeSectionError

    /** A lower-layer failure, carrying its message verbatim (already localized or diagnostic). */
    data class Message(val text: String) : NodeSectionError
}

/**
 * The observable state of the repeater status screen. Each disclosure section owns its own loading, loaded,
 * and error flags so a failure in one surfaces only under that section, mirroring the iOS section-scoped
 * error convention.
 *
 * [previousStatusSnapshot] / [previousNeighborSnapshot] / [seenNeighborPrefixes] are the baselines used
 * for metric deltas and the neighbor New / Not-seen rows — loaded **before** the current capture is
 * recorded so a render never pairs a fresh baseline with a stale seen set (iOS `handleStatusResponse` /
 * `enrichNeighbors` order).
 */
data class NodeStatusState(
    val routePathExpanded: Boolean = false,
    val snapshotVersion: Long = 0,
    val statusExpanded: Boolean = false,
    val isLoadingStatus: Boolean = false,
    val statusLoaded: Boolean = false,
    val statusSectionError: NodeSectionError? = null,
    override val status: StatusResponse? = null,
    override val previousStatusSnapshot: NodeStatusSnapshotDto? = null,
    val telemetryExpanded: Boolean = false,
    val isLoadingTelemetry: Boolean = false,
    val telemetryLoaded: Boolean = false,
    val telemetrySectionError: NodeSectionError? = null,
    override val telemetryDataPoints: List<LPPDataPoint> = emptyList(),
    val ownerInfoExpanded: Boolean = false,
    val isLoadingOwnerInfo: Boolean = false,
    val ownerInfoLoaded: Boolean = false,
    val ownerInfoSectionError: NodeSectionError? = null,
    val ownerInfo: String? = null,
    /**
     * The node's firmware version from the owner-info reply, or null when the node predates owner-info
     * and reports an empty string. A sibling of [ownerInfo] on the wire, so it survives an empty owner.
     */
    val firmwareVersion: String? = null,
    val neighborsExpanded: Boolean = false,
    val isLoadingNeighbors: Boolean = false,
    val neighborsLoaded: Boolean = false,
    val neighborsSectionError: NodeSectionError? = null,
    val neighbors: List<Neighbour> = emptyList(),
    val previousNeighborSnapshot: NodeStatusSnapshotDto? = null,
    val seenNeighborPrefixes: Set<List<Byte>> = emptySet(),
    val isDiscovering: Boolean = false,
    val discoverySecondsRemaining: Int = 0,
    val batteryCurveExpanded: Boolean = false,
    override val ocvLoaded: Boolean = false,
    override val ocvPreset: OCVPreset = OCVPreset.LI_ION,
    override val ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
    override val ocvError: NodeSectionError? = null,
    override val isSavingOcv: Boolean = false,
) : StatusReadingState,
    OcvCurveState

/**
 * Drives the repeater status screen, ported from the iOS `NodeStatusViewModel` + `RepeaterStatusViewModel`
 * for counters, live request-telemetry (LPP sensor rows), owner info, neighbours, admin Discover
 * Neighbors, and the battery-curve editor.
 * Plain over a [CoroutineScope] and provider closures with the observable surface as a [StateFlow];
 * each section loads lazily on first expand and reload on demand, resolving the admin service live so a
 * reconnect-minted instance is used.
 */
class NodeStatusViewModel(
    scope: CoroutineScope,
    internal var session: RemoteNodeSessionDto,
    internal val dependencies: Dependencies,
) {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    internal val scope = CoroutineScope(scope.coroutineContext + ownerJob)

    class Dependencies(
        val repeaterAdminService: () -> RepeaterAdminService?,
        val nodeSnapshotService: () -> NodeSnapshotService? = { null },
        val contactService: () -> ContactService? = { null },
        val radioId: () -> Uuid? = { null },
        /**
         * Whether a radio session is live. Offline [ContactService] still exists for OCV reads;
         * iOS save requires live `contactService`.
         */
        val isRadioConnected: () -> Boolean = { true },
        /** Epoch seconds for baseline "before" queries; injectable for tests. */
        val nowEpochSeconds: () -> Double = {
            @OptIn(ExperimentalTime::class)
            Clock.System.now().epochSeconds.toDouble()
        },
    )

    /** Same-package sections write this flow. Callers read [state]. */
    internal val statusState = MutableStateFlow(NodeStatusState())
    val state: StateFlow<NodeStatusState> = statusState.asStateFlow()
    private val ocv = OcvCurveController(
        scope = this.scope,
        access = OcvCurveAccess(
            publicKey = session.publicKey,
            contactService = dependencies.contactService,
            radioId = dependencies.radioId,
            isRadioConnected = dependencies.isRadioConnected,
        ),
        state = statusState,
        copyOcv = { fields ->
            copy(
                ocvLoaded = fields.loaded,
                ocvPreset = fields.preset,
                ocvValues = fields.values,
                ocvError = fields.error,
                isSavingOcv = fields.saving,
            )
        },
    )
    internal val ocvJob: Job? get() = ocv.job

    internal val statusRequest = RemoteSectionRequest(this.scope)
    internal val statusJob: Job? get() = statusRequest.job
    internal val telemetryRequest = RemoteSectionRequest(this.scope)
    internal val telemetryJob: Job? get() = telemetryRequest.job
    internal val ownerInfoRequest = RemoteSectionRequest(this.scope)
    internal val ownerInfoJob: Job? get() = ownerInfoRequest.job
    internal val neighborsRequest = RemoteSectionRequest(this.scope)
    internal val neighborsJob: Job? get() = neighborsRequest.job
    internal var discoverJob: Job? = null
    internal val discoveryNowMillis: Long get() = (dependencies.nowEpochSeconds() * DISCOVERY_TICK_MS).toLong()
    internal var discoveryGeneration = 0L
    internal var discoveryDeadlineMillis: Long? = null
    internal var discoveryPollsEnabled = false
    internal var isClosed = false
        private set

    fun setRoutePathExpanded(expanded: Boolean) {
        statusState.update { it.copy(routePathExpanded = expanded) }
    }

    internal fun updateSession(value: RemoteNodeSessionDto) {
        session = value.copy(publicKey = value.publicKey.copyOf())
    }

    /** Stops every request and persistence write owned by a retired status surface. */
    fun close() {
        if (isClosed) return
        isClosed = true
        ownerJob.cancel()
    }

    /** Selects a named preset (not custom) and persists it. */
    fun selectOcvPreset(preset: OCVPreset) = ocv.select(preset)

    /**
     * Commits a custom curve after validation. No-op when values still match the selected non-custom
     * preset (avoids flipping to custom on a focus-only commit).
     */
    fun commitOcvValues(values: List<Int>) = ocv.commit(values)

    internal fun loadOcvSettings() = ocv.load()

    /**
     * Runs a section request under the shared transient-retry ladder, so a firmware "no response yet" while
     * a multi-hop reply is still in flight is re-issued instead of surfacing as a section error.
     */
    internal suspend fun <T> retrying(operation: suspend (timeout: Duration) -> T): T =
        RemoteSectionRetryPolicy.performWithTransientRetries(operation = operation)

    companion object {
        internal const val DISCOVER_NEIGHBORS_COMMAND = "discover.neighbors"
        internal const val DISCOVERY_DURATION_SECONDS = 60
        internal const val DISCOVERY_POLL_INTERVAL_SECONDS = 5
        internal const val DISCOVERY_TICK_MS = 1_000L
        internal const val OCV_LOAD_FAILED_KEY = OcvCurveKeys.LOAD_FAILED
        internal const val OCV_SAVE_NO_CONTACT_KEY = OcvCurveKeys.SAVE_NO_CONTACT
        internal const val OCV_SAVE_FAILED_KEY = OcvCurveKeys.SAVE_FAILED
    }
}
