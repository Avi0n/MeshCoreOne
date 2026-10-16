package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TracePathWaiter
import one.meshcore.shared.trace.TraceResult

/** Process-owned trace work. Radio providers belong to the graph, localized copy to the mount. */
class TracePathWorkspace(
    private val scope: CoroutineScope,
    private val dependencies: Dependencies,
    private val waiter: TracePathWaiter? = null,
) {
    data class Dependencies(
        val runner: () -> (suspend (ByteArray, Int, Int) -> TraceResult)?,
        val builder: TracePathBuilder.Dependencies,
        val deviceHashMode: suspend () -> Int?,
        val deviceLocation: () -> LatLng?,
        val savedPathStore: SavedTracePathStore?,
        val recents: RecentHopsStore?,
    )
    data class Messages(val emptyPath: String, val failed: String, val allFailed: String, val loadFailed: String)
    private var messages: Messages? = null
    private var model: TracePathViewModel? = null

    internal fun bind(messages: Messages): TracePathViewModel {
        this.messages = messages
        return model ?: TracePathViewModel(
            scope,
            TracePathViewModel.Dependencies(
                runner = dependencies.runner,
                builder = dependencies.builder,
                deviceHashMode = dependencies.deviceHashMode,
                deviceLocation = dependencies.deviceLocation,
                savedPathStore = dependencies.savedPathStore,
                messages = {
                    val current = requireNotNull(this.messages)
                    TracePathViewModel.Messages(
                        current.emptyPath,
                        current.failed,
                        current.allFailed,
                        current.loadFailed,
                    )
                },
            ),
            dependencies.recents,
        ).also { model = it }
    }
    fun reset(isCurrentOwner: () -> Boolean = { true }) {
        scope.launch {
            if (isCurrentOwner()) {
                waiter?.reset()
                model?.reset()
            }
        }
    }
    fun reattach(isCurrentOwner: () -> Boolean = { true }) {
        scope.launch {
            if (isCurrentOwner()) {
                waiter?.reattach()
                model?.load()
            }
        }
    }
    fun expireDeadline() {
        scope.launch { waiter?.expireDeadline() }
    }
}
