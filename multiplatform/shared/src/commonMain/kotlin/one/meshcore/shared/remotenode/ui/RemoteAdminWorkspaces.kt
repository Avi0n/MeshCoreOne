package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodeCliMessages
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.runReportingException

/** Process/Main-owned admin work, partitioned by radio, full node identity and role. */
class RemoteAdminWorkspaces(private val scope: CoroutineScope, internal val dependencies: Dependencies) {
    data class Dependencies(
        val repeaterAdminService: () -> RepeaterAdminService?,
        val roomAdminService: () -> RoomAdminService?,
        val contactService: () -> ContactService?,
        val nodeSnapshotService: () -> NodeSnapshotService?,
        val radioId: () -> Uuid?,
        val isRadioConnected: () -> Boolean,
    )

    private data class Key(val radioId: Uuid, val publicKey: List<Byte>, val role: RemoteNodeRole)
    private val families = mutableMapOf<Key, RemoteAdminFamily>()
    private var handlerGeneration = 0L
    private var handlersBound = false
    private var repeaterHandlerService: RepeaterAdminService? = null
    private var roomHandlerService: RoomAdminService? = null

    internal fun family(session: RemoteNodeSessionDto): RemoteAdminFamily {
        val key = Key(session.radioID, session.publicKey.toList(), session.role)
        return families.getOrPut(key) { RemoteAdminFamily(scope, session, dependencies) }
            .also { it.adoptSession(session) }
    }

    internal fun cli(session: RemoteNodeSessionDto, messages: NodeCliMessages): NodeCliViewModel =
        family(session).cli(messages)

    fun reset(isCurrentOwner: () -> Boolean = { true }) {
        scope.launch {
            if (!isCurrentOwner()) return@launch
            handlerGeneration++
            handlersBound = false
            repeaterHandlerService = null
            roomHandlerService = null
            families.values.forEach(RemoteAdminFamily::close)
            families.clear()
        }
    }

    fun reattach(isCurrentOwner: () -> Boolean = { true }) {
        scope.launch {
            if (!isCurrentOwner()) return@launch
            registerHandlers()
        }
    }

    fun expireDeadline() {
        scope.launch { families.values.forEach { it.discoveryStatus?.expireDiscoveryDeadline() } }
    }

    fun pauseDiscoveryPolls() {
        scope.launch { families.values.forEach { it.discoveryStatus?.pauseDiscoveryPolls() } }
    }

    private suspend fun registerHandlers() {
        val repeater = dependencies.repeaterAdminService()
        val room = dependencies.roomAdminService()
        if (handlersBound && repeaterHandlerService === repeater && roomHandlerService === room) return
        val generation = ++handlerGeneration
        handlersBound = true
        repeaterHandlerService = repeater
        roomHandlerService = room
        repeater?.setCLIHandler { message, contact ->
            if (message.text != repeater.cliBodyClaimedByWaiter()) {
                scope.launch {
                    if (generation == handlerGeneration && dependencies.repeaterAdminService() === repeater) {
                        route(contact, RemoteNodeRole.REPEATER, message.text)
                    }
                }
            }
        }
        room?.setCLIHandler { contact, response ->
            if (response != room.cliBodyClaimedByWaiter()) {
                scope.launch {
                    if (generation == handlerGeneration && dependencies.roomAdminService() === room) {
                        route(contact, RemoteNodeRole.ROOM_SERVER, response)
                    }
                }
            }
        }
    }

    private fun route(contact: ContactDto, role: RemoteNodeRole, response: String) {
        if (dependencies.radioId() != contact.radioID) return
        families[Key(contact.radioID, contact.publicKey.toList(), role)]?.handleLateResponse(response)
    }
}

internal suspend fun <T> currentRemoteRequest(isCurrent: () -> Boolean, send: suspend () -> T): T {
    val context = currentCoroutineContext()
    fun checkOwner() {
        context.ensureActive()
        if (!isCurrent()) throw CancellationException("Remote admin owner was replaced")
    }
    return runReportingException(onFailure = { error ->
        checkOwner()
        throw error
    }) {
        checkOwner()
        send().also { checkOwner() }
    }
}
