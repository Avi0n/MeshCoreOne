package one.meshcore.shared.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Owns pending selection and confirmed writes independently of their presenting Composition. */
class NodeConfigImportWorkspace(
    internal val scope: CoroutineScope,
    private val isDestinationCurrent: () -> Boolean = { true },
) {
    internal var confirmedState by mutableStateOf<NodeConfigImportState?>(null)
        private set
    internal var pendingState by mutableStateOf<NodeConfigImportState?>(null)
        private set
    private var pickJob: Job? = null
    private var recoveredReader: (suspend () -> String)? = null
    private var isRoutingRecovery = false
    private val presentations = mutableSetOf<Presentation>()

    fun recoverPickedConfig(readSelected: suspend () -> String, showReview: () -> Unit): Boolean {
        retireIfDestinationChanged()
        if (pendingState != null || confirmedState != null) return false
        val state = NodeConfigImportState().also { it.isParsing = true }
        var routed = false
        isRoutingRecovery = true
        try {
            pendingState = state
            recoveredReader = readSelected
            showReview()
            routed = isDestinationCurrent()
        } finally {
            isRoutingRecovery = false
            if (!routed && pendingState === state) discardPending()
        }
        return routed && pendingState === state
    }

    internal fun takeRecoveredReader(state: NodeConfigImportState): (suspend () -> String)? {
        if (!isPickCurrent(state)) return null
        return recoveredReader.also { recoveredReader = null }
    }

    internal fun claimPick(state: NodeConfigImportState): Boolean {
        retireIfDestinationChanged()
        if (!isDestinationCurrent() || confirmedState?.isApplying == true || pendingState?.isParsing == true) {
            return false
        }
        recoveredReader = null
        pendingState = state
        return true
    }

    internal fun installPickJob(state: NodeConfigImportState, job: Job) {
        if (pendingState === state) pickJob = job else job.cancel()
    }

    internal fun isPickCurrent(state: NodeConfigImportState): Boolean {
        retireIfDestinationChanged()
        return pendingState === state
    }

    fun retireIfDestinationChanged() {
        if (!isRoutingRecovery && !isDestinationCurrent()) discardPending()
    }

    private fun discardPending() {
        recoveredReader = null
        pendingState = null
        val retired = pickJob
        pickJob = null
        retired?.cancel()
    }

    internal fun claim(state: NodeConfigImportState): Boolean {
        if (confirmedState?.isApplying == true || state.isApplying) return false
        discardPending()
        confirmedState = state
        state.isApplying = true
        return true
    }

    internal fun acquirePresentation(state: NodeConfigImportState): Presentation =
        Presentation(state).also { presentations.add(it) }

    internal fun release(presentation: Presentation) {
        if (!presentations.remove(presentation)) return
        val state = presentation.state
        if (presentations.any { it.state === state }) return
        if (pendingState === state && (state.importedConfig != null || !state.isParsing)) discardPending()
        if (confirmedState === state && !state.isApplying) confirmedState = null
    }

    internal class Presentation(val state: NodeConfigImportState)
}
