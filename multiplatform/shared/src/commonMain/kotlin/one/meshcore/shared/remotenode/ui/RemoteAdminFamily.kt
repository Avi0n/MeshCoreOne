package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.remotenode.NodeCliMessages
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.room.RoomSettingsController
import one.meshcore.shared.room.ui.RoomSettingsViewModel
import one.meshcore.shared.room.ui.RoomStatusViewModel
import kotlin.time.Duration

/** Models are lazy so opening Settings does not also request every status section. */
internal class RemoteAdminFamily(
    parent: CoroutineScope,
    session: RemoteNodeSessionDto,
    private val ownership: RemoteAdminWorkspaces.Dependencies,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private var session = session.copy(publicKey = session.publicKey.copyOf())
    private val isCurrent = { scope.isActive && ownership.radioId() == this.session.radioID }
    private val dependencies = RemoteAdminWorkspaces.Dependencies(
        repeaterAdminService = { if (isCurrent()) ownership.repeaterAdminService() else null },
        roomAdminService = { if (isCurrent()) ownership.roomAdminService() else null },
        contactService = { if (isCurrent()) ownership.contactService() else null },
        nodeSnapshotService = { if (isCurrent()) ownership.nodeSnapshotService() else null },
        radioId = { if (isCurrent()) this.session.radioID else null },
        isRadioConnected = { isCurrent() && ownership.isRadioConnected() },
    )
    private var cliMessages: NodeCliMessages? = null
    private var nodeSettings: NodeSettingsViewModel? = null
    private var roomSettings: RoomSettingsViewModel? = null
    private var nodeStatus: NodeStatusViewModel? = null
    private var roomStatus: RoomStatusViewModel? = null
    private var cli: NodeCliViewModel? = null

    internal fun adoptSession(value: RemoteNodeSessionDto) {
        session = value.copy(publicKey = value.publicKey.copyOf())
        nodeStatus?.updateSession(session)
        roomStatus?.updateSession(session)
        cli?.updateSessionName(session.name)
    }

    internal fun repeaterSettings(): NodeSettingsViewModel = nodeSettings ?: NodeSettingsViewModel(
        scope,
        session.id,
        NodeSettingsViewModel.Dependencies(
            repeaterAdminService = dependencies.repeaterAdminService,
            sessionId = { session.id },
            ownsCLIHandler = false,
            isCurrent = isCurrent,
        ),
    ).also { nodeSettings = it }

    internal fun roomSettings(): RoomSettingsViewModel = roomSettings ?: run {
        val send: suspend (String, Duration) -> String = { command, timeout -> sendRoom(command, timeout, false) }
        val raw: suspend (String, Duration) -> String = { command, timeout -> sendRoom(command, timeout, true) }
        RoomSettingsViewModel(
            scope,
            RoomSettingsController(NodeSettingsController(send, raw), send, raw),
            roomAdminService = dependencies.roomAdminService,
            ownsCLIHandler = false,
        ).also { roomSettings = it }
    }

    internal fun repeaterStatus(): NodeStatusViewModel = nodeStatus ?: NodeStatusViewModel(
        scope,
        session,
        NodeStatusViewModel.Dependencies(
            repeaterAdminService = dependencies.repeaterAdminService,
            nodeSnapshotService = dependencies.nodeSnapshotService,
            contactService = dependencies.contactService,
            radioId = dependencies.radioId,
            isRadioConnected = dependencies.isRadioConnected,
        ),
    ).also { nodeStatus = it }

    internal fun roomStatus(): RoomStatusViewModel = roomStatus ?: RoomStatusViewModel(
        scope,
        session,
        RoomStatusViewModel.Dependencies(
            roomAdminService = dependencies.roomAdminService,
            nodeSnapshotService = dependencies.nodeSnapshotService,
            contactService = dependencies.contactService,
            radioId = dependencies.radioId,
            isRadioConnected = dependencies.isRadioConnected,
        ),
    ).also { roomStatus = it }

    internal fun cli(messages: NodeCliMessages): NodeCliViewModel {
        cliMessages = messages
        return cli ?: NodeCliViewModel(
            scope,
            session.name,
            messages = { requireNotNull(cliMessages) },
            dependencies = NodeCliViewModel.Dependencies(
                repeaterAdminService = dependencies.repeaterAdminService,
                sessionId = { session.id },
                sendRawCommand = { command, timeout ->
                    if (session.role == one.meshcore.shared.model.RemoteNodeRole.ROOM_SERVER) {
                        sendRoom(command, timeout, true)
                    } else {
                        val service = dependencies.repeaterAdminService() ?: throw RemoteNodeError.NotConnected
                        currentRemoteRequest({ isCurrent() && dependencies.repeaterAdminService() === service }) {
                            service.sendRawCommand(session.id, command, timeout)
                        }
                    }
                },
            ),
        ).also { cli = it }
    }

    internal fun handleLateResponse(response: String) {
        if (!isCurrent()) return
        nodeSettings?.handleLateResponse(response)
        roomSettings?.handleLateResponse(response)
    }

    internal val discoveryStatus: NodeStatusViewModel? get() = nodeStatus

    internal fun close() {
        job.cancel()
        nodeSettings?.cleanup()
        roomSettings?.cleanup()
        nodeStatus?.close()
        roomStatus?.close()
        cli?.close()
    }

    private suspend fun sendRoom(command: String, timeout: Duration, raw: Boolean): String {
        val service = dependencies.roomAdminService() ?: throw RemoteNodeError.NotConnected
        return currentRemoteRequest({ isCurrent() && dependencies.roomAdminService() === service }) {
            if (raw) {
                service.sendRawCommand(
                    session.id,
                    command,
                    timeout,
                )
            } else {
                service.sendCommand(session.id, command, timeout)
            }
        }
    }
}
