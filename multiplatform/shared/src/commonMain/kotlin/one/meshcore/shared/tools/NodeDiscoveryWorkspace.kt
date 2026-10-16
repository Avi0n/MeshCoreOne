package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Process-owned discovery work. The supplied scope and UI mounts share the Main dispatcher. */
@OptIn(ExperimentalTime::class)
class NodeDiscoveryWorkspace(
    private val scope: CoroutineScope,
    private val dependencies: Dependencies,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val scanDurationMs: Long = NodeDiscoveryViewModel.DEFAULT_SCAN_DURATION_MS,
) {
    data class Dependencies(
        val sendDiscover: suspend (Int, Boolean) -> Long,
        val events: () -> Flow<MeshEvent>?,
        val contactService: () -> ContactService?,
        val discoveredNodeStore: () -> DiscoveredNodeStore?,
        val radioId: () -> Uuid?,
        val isRadioConnected: () -> Boolean,
        val maxContacts: () -> Int?,
    )

    data class Messages(
        val notConnected: (NodeDiscoveryFilter) -> String,
        val scanFailed: String,
        val unknownNode: String,
        val contactError: (Throwable) -> String,
    )

    private var messages: Messages? = null
    private var model: NodeDiscoveryViewModel? = null

    /** Only localized copy comes from the mount; radio providers belong to the process graph. */
    internal fun bind(messages: Messages): NodeDiscoveryViewModel {
        this.messages = messages
        return model ?: NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = dependencies.sendDiscover,
                events = dependencies.events,
                contactService = dependencies.contactService,
                discoveredNodeStore = dependencies.discoveredNodeStore,
                radioId = dependencies.radioId,
                isRadioConnected = dependencies.isRadioConnected,
                maxContacts = dependencies.maxContacts,
                notConnectedMessage = { requireNotNull(this.messages).notConnected(it) },
                scanFailedMessage = { requireNotNull(this.messages).scanFailed },
                unknownNodeLabel = { requireNotNull(this.messages).unknownNode },
                errorText = { requireNotNull(this.messages).contactError(it) },
            ),
            scanDurationMs = scanDurationMs,
            nowEpochSeconds = { nowEpochMillis() / MILLIS_PER_SECOND },
            nowEpochMillis = nowEpochMillis,
        ).also { model = it }
    }

    fun scan() {
        scope.launch { model?.scan(scope) }
    }
    fun stopScan() {
        scope.launch { model?.stopScan() }
    }
    fun addNode(result: NodeDiscoveryResult) {
        scope.launch { model?.addNode(result) }
    }
    fun reset(isCurrentOwner: () -> Boolean = { true }) {
        scope.launch { if (isCurrentOwner()) model?.stopScan(clearOwnedState = true) }
    }
    fun reattach(isCurrentOwner: () -> Boolean = { true }) {
        scope.launch { if (isCurrentOwner()) model?.reattachIfWaiting() }
    }
    fun expireDeadline() {
        scope.launch { model?.expireScanIfDeadlinePassed() }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
