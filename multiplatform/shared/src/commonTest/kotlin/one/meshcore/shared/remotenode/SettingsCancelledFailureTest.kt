package one.meshcore.shared.remotenode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.room.RoomSettingsController
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsCancelledFailureTest {
    @Test
    fun cancelledSharedApplyDoesNotPublishTransportError() = runTest {
        assertCancelledFailure { send ->
            NodeSettingsController(send, send).applyContactInfoSettings()
        }
    }

    @Test
    fun cancelledRepeaterDirectApplyDoesNotPublishTransportError() = runTest {
        assertCancelledFailure { send ->
            val controller = RepeaterSettingsController(NodeSettingsController(send, send), send, send)
            controller.repeaterEnabled = true
            controller.applyBehaviorSettings()
        }
    }

    @Test
    fun cancelledRoomRawReadDoesNotPublishFallback() = runTest {
        assertCancelledFailure { send ->
            RoomSettingsController(NodeSettingsController(send, send), send, send).fetchRoomAccess()
        }
    }

    @Test
    fun cancelledRoomDirectApplyDoesNotPublishTransportError() = runTest {
        assertCancelledFailure { send ->
            val controller = RoomSettingsController(NodeSettingsController(send, send), send, send)
            controller.guestPassword = "Guest fixture"
            controller.applyRoomAccess()
        }
    }

    private suspend fun TestScope.assertCancelledFailure(
        action: suspend (suspend (String, Duration) -> String) -> Unit,
    ) {
        val release = CompletableDeferred<Unit>()
        var entered = false
        var returned = false
        val job = launch {
            action { _, _ ->
                entered = true
                withContext(NonCancellable) { release.await() }
                throw RemoteNodeError.NotConnected
            }
            returned = true
        }
        runCurrent()
        kotlin.test.assertTrue(entered)
        job.cancel()
        release.complete(Unit)
        job.join()

        assertFalse(returned, "A cancelled sender failure was converted to a normal result")
    }
}
