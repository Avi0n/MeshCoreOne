package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.runNodeSnapshot
import one.meshcore.shared.remotenode.ui.NodeSectionError
import one.meshcore.shared.remotenode.ui.OcvCurveAccess
import one.meshcore.shared.remotenode.ui.OcvCurveController
import one.meshcore.shared.remotenode.ui.OcvCurveKeys
import one.meshcore.shared.remotenode.ui.OcvCurveState
import one.meshcore.shared.remotenode.ui.StatusReadingState
import one.meshcore.shared.remotenode.ui.currentRemoteRequest
import one.meshcore.shared.room.RoomAdminService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Room-server status surface: status counters (including posts received/pushed), last-visit metric
 * deltas, live request-telemetry with LPP sensor rows, battery-curve editor (iOS
 * `NodeBatteryCurveDisclosureSection`), and snapshot history for charts. Mirrors iOS
 * [RoomStatusViewModel] without owner-info/neighbours (repeater-only).
 */
class RoomStatusViewModel(
    scope: CoroutineScope,
    private var session: RemoteNodeSessionDto,
    private val dependencies: Dependencies,
) {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + ownerJob)

    class Dependencies(
        val roomAdminService: () -> RoomAdminService?,
        val nodeSnapshotService: () -> NodeSnapshotService? = { null },
        val contactService: () -> ContactService? = { null },
        val radioId: () -> Uuid? = { null },
        /**
         * Whether a radio session is live. Offline [ContactService] still exists for OCV reads;
         * iOS save requires live `contactService`.
         */
        val isRadioConnected: () -> Boolean = { true },
        /** Epoch seconds for the previous-status baseline query; injectable for tests. */
        val nowEpochSeconds: () -> Double = {
            @OptIn(ExperimentalTime::class)
            Clock.System.now().epochSeconds.toDouble()
        },
    )

    private val _state = MutableStateFlow(RoomStatusState())
    val state: StateFlow<RoomStatusState> = _state.asStateFlow()

    private var isClosed = false
    private var historyJob: Job? = null
    private val refresh = RoomStatusRefresh(this.scope, { this.session }, dependencies, _state, ::loadHistory)
    private val ocv = OcvCurveController(
        scope = this.scope,
        access = OcvCurveAccess(
            publicKey = session.publicKey,
            contactService = dependencies.contactService,
            radioId = dependencies.radioId,
            isRadioConnected = dependencies.isRadioConnected,
        ),
        state = _state,
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

    /** Loads persisted snapshot history for [TelemetryOverviewScreen]. */
    fun loadHistory() {
        val snapshots = dependencies.nodeSnapshotService() ?: return
        historyJob?.cancel()
        historyJob = scope.launch {
            val rows = currentRemoteRequest({ !isClosed && dependencies.nodeSnapshotService() === snapshots }) {
                runNodeSnapshot(onFailure = { emptyList() }) {
                    snapshots.fetchSnapshots(session.publicKey)
                }
            }
            _state.update { it.copy(historySnapshots = rows) }
        }
    }

    fun setStatusExpanded(expanded: Boolean) {
        _state.update { it.copy(statusExpanded = expanded) }
        val current = _state.value
        if (expanded) ocv.load()
        if (expanded && !current.statusLoaded && !current.isLoadingStatus) requestStatus()
    }

    fun setTelemetryExpanded(expanded: Boolean) {
        _state.update { it.copy(telemetryExpanded = expanded) }
        val current = _state.value
        if (expanded) ocv.load()
        if (expanded && !current.telemetryLoaded && !current.isLoadingTelemetry) requestTelemetry()
    }

    /** Expands/collapses the battery-curve section, loading OCV settings once on first expand. */
    fun setBatteryCurveExpanded(expanded: Boolean) {
        _state.update { it.copy(batteryCurveExpanded = expanded) }
        val current = _state.value
        if (expanded && !current.ocvLoaded) ocv.load()
    }

    /** Selects a named preset (not custom) and persists it on the room contact. */
    fun selectOcvPreset(preset: OCVPreset) = ocv.select(preset)

    /**
     * Commits a custom curve after validation. No-op when values still match the selected non-custom
     * preset (avoids flipping to custom on a focus-only commit).
     */
    fun commitOcvValues(values: List<Int>) = ocv.commit(values)

    fun requestStatus() {
        if (!isClosed) refresh.requestStatus()
    }

    /**
     * Live `requestTelemetry`: takes the bridge-decoded [TelemetryResponse.points], drops channel 0, shows
     * sensor rows, and records numeric snapshot entries plus a GPS fix when the reply carried one.
     */
    fun requestTelemetry() {
        if (!isClosed) refresh.requestTelemetry()
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

    companion object {
        internal const val OCV_LOAD_FAILED_KEY = OcvCurveKeys.LOAD_FAILED
        internal const val OCV_SAVE_NO_CONTACT_KEY = OcvCurveKeys.SAVE_NO_CONTACT
        internal const val OCV_SAVE_FAILED_KEY = OcvCurveKeys.SAVE_FAILED
    }
}

/** Observable state for [RoomStatusScreen]. */
data class RoomStatusState(
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
    val batteryCurveExpanded: Boolean = false,
    override val ocvLoaded: Boolean = false,
    override val ocvPreset: OCVPreset = OCVPreset.LI_ION,
    override val ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
    override val ocvError: NodeSectionError? = null,
    override val isSavingOcv: Boolean = false,
    val historySnapshots: List<NodeStatusSnapshotDto> = emptyList(),
) : StatusReadingState,
    OcvCurveState
