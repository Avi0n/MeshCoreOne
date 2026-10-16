package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodeCliMessages
import one.meshcore.shared.remotenode.NodeSession
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.InMemoryRoomStore
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomAdminSession

internal class RemoteAdminTestHarness(
    scope: CoroutineScope,
    roomSession: RoomAdminSession = FakeRoomAdminSession(),
    nodeSession: NodeSession = FakeNodeSession(),
) {
    val store = InMemoryRoomStore()
    private val passwords = InMemoryNodePasswordStore()
    private val cli = NodeCliCoordinator(nodeSession, store, passwords, scope)
    val repeater = RepeaterAdminService(
        RemoteNodeService(nodeSession, store, passwords, scope),
        cli,
        nodeSession,
        store,
        passwords,
    )
    val room = RoomAdminService(roomSession, store)
    var currentRepeater: RepeaterAdminService? = repeater
    var currentRoom: RoomAdminService? = room
    var radioId: Uuid? = testUuid(99)
    var currentContacts: ContactService? = null
    var currentSnapshots: NodeSnapshotService? = null
    val workspaces = RemoteAdminWorkspaces(
        scope,
        RemoteAdminWorkspaces.Dependencies(
            repeaterAdminService = { currentRepeater },
            roomAdminService = { currentRoom },
            contactService = { currentContacts },
            nodeSnapshotService = { currentSnapshots },
            radioId = { radioId },
            isRadioConnected = { true },
        ),
    )
}

internal val remoteCliMessages = NodeCliMessages(
    bannerConnected = { "Connected to $it" },
    bannerHint = "Help available",
    cancelled = "Cancelled",
    rebootSent = "Reboot sent",
    promptSuffix = "> ",
    help = listOf("Help"),
)
