package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TraceResult

/** The scope and callers share Main; UI mounts only observe this retained execution owner. */
internal class TracePathViewModel(
    internal val scope: CoroutineScope,
    internal val dependencies: Dependencies,
    recents: RecentHopsStore? = null,
    initialState: State = State(),
) {
    data class Dependencies(
        val runner: () -> (suspend (ByteArray, Int, Int) -> TraceResult)?,
        val builder: TracePathBuilder.Dependencies,
        val deviceHashMode: suspend () -> Int?,
        val deviceLocation: () -> LatLng?,
        val savedPathStore: SavedTracePathStore?,
        val messages: () -> Messages,
    )
    data class Messages(val emptyPath: String, val failed: String, val allFailed: String, val loadFailed: String)
    data class State(
        val autoReturn: Boolean = true,
        val batchEnabled: Boolean = false,
        val batchCount: Int = DEFAULT_BATCH_COUNT,
        val isRunning: Boolean = false,
        val batchProgress: Pair<Int, Int>? = null,
        val result: TraceResult? = null,
        val batchResults: List<TraceResult> = emptyList(),
        val errorMessage: String? = null,
        val savedPaths: List<SavedTracePathDto> = emptyList(),
        val deviceHashMode: Int? = null,
        val mapMode: Boolean = false,
        val mapFilter: MapFilterState = MapFilterState.seed(MapFilterHost.TRACE_PATH),
        val successId: Long? = null,
        val ownerGeneration: Long = 0L,
    )
    internal data class Owner(val epoch: Long, val radioId: Uuid?)

    internal val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<State> = mutableState.asStateFlow()
    val builder = TracePathBuilder(
        scope,
        TracePathBuilder.Dependencies(
            contactService = dependencies.builder.contactService,
            radioId = dependencies.builder.radioId,
            devicePathHashMode = { state.value.deviceHashMode },
            supportsHashSizeOverride = dependencies.builder.supportsHashSizeOverride,
            discoveredNodes = dependencies.builder.discoveredNodes,
            userLocation = { dependencies.deviceLocation()?.let { UserLocation(it.latitude, it.longitude) } },
        ),
        recents,
    )
    internal var epoch = 0L
    internal var runJob: Job? = null
    internal var loadJob: Job? = null
    internal var errorJob: Job? = null
    internal var visible = false
    internal var visibilityGeneration = 0L
    internal var loadedRadio: Uuid? = null
    internal var successSequence = 0L
    internal var presentedSuccessId: Long? = null
    internal var resultDismissed = false

    internal fun owner(): Owner = Owner(epoch, dependencies.builder.radioId())
    internal fun owns(owner: Owner): Boolean = owner.epoch == epoch &&
        (dependencies.builder.radioId() == null || dependencies.builder.radioId() == owner.radioId)

    private companion object {
        const val DEFAULT_BATCH_COUNT = 3
    }
}
