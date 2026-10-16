package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.testSession

/** A connected repeater admin, so a status fault test can fail one request. */
internal class RepeaterStatusRequestHarness(val scope: CoroutineScope) {
    val nodeSession = FakeNodeSession()
    val store = InMemoryRemoteNodeStore()
    val passwordStore = InMemoryNodePasswordStore()
    val remote = RemoteNodeService(nodeSession, store, passwordStore, scope)
    val cli = NodeCliCoordinator(nodeSession, store, passwordStore, scope)
    val admin = RepeaterAdminService(remote, cli, nodeSession, store, passwordStore)
}

/** A repeater admin whose scope records uncaught errors, so a fault test can read the thrown instance. */
internal fun TestScope.repeaterStatusHarness(thrown: MutableList<Throwable>): RepeaterStatusRequestHarness {
    val scope = CoroutineScope(
        SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
            thrown += error
        },
    )
    return RepeaterStatusRequestHarness(scope)
}

/** Status sheet for [harness], with the admin service the sheet reads while it opens. */
internal suspend fun repeaterStatusViewModel(harness: RepeaterStatusRequestHarness): NodeStatusViewModel {
    val session = testSession(permissionLevel = RoomPermissionLevel.ADMIN)
    harness.store.saveSession(session)
    return NodeStatusViewModel(
        scope = harness.scope,
        session = session,
        dependencies = NodeStatusViewModel.Dependencies(repeaterAdminService = { harness.admin }),
    )
}
