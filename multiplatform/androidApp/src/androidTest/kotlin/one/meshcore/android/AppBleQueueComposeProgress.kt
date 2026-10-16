package one.meshcore.android

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.connection.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicReference

internal class AppBleQueueComposeProgress(private val compose: ComposeTestRule) {
    suspend fun <T> await(action: suspend () -> T): T = coroutineScope {
        check(Looper.myLooper() != Looper.getMainLooper())
        val operation = async(start = CoroutineStart.UNDISPATCHED) { action() }
        while (!operation.isCompleted) {
            compose.mainClock.advanceTimeByFrame()
            delay(AppBleQueueCheckpoints.POLL_MS)
        }
        operation.await()
    }

    suspend fun verifyRoomControl() {
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MeshCoreApplication
        val container = application.container
        withContext(Dispatchers.Main.immediate) {
            container.awaitInitialOfflineData()
            assertEquals(ConnectionState.Disconnected, container.connectionManager.state.value)
            assertNull(container.connectionManager.services)
            assertNull(container.lastConnectionStore.deviceId())
        }
        val release = CompletableDeferred<Unit>()
        val readCompleted = CompletableDeferred<Int>()
        val resumed = CompletableDeferred<Int>()
        val job = AtomicReference<Job?>()
        var scenario: ActivityScenario<MainActivity>? = null
        var primary: Throwable? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    BasicText(
                        CONTROL_TAG,
                        Modifier.testTag(CONTROL_TAG).clickable {
                            job.set(
                                scope.launch {
                                    val count = withContext(Dispatchers.IO) {
                                        release.await()
                                        container.database.remoteNodeSessionDao().getAll().size.also {
                                            readCompleted.complete(it)
                                        }
                                    }
                                    resumed.complete(count)
                                },
                            )
                        },
                    )
                }
            }
            compose.onNodeWithTag(CONTROL_TAG).performClick()
            assertTrue(job.get() != null)
            release.complete(Unit)
            assertEquals(0, withTimeout(AppBleQueueFixture.TIMEOUT_MS) { readCompleted.await() })
            assertNull(withTimeoutOrNull(RAW_WAIT_MS) { resumed.await() })
            assertMainResponsive()
            withTimeout(AppBleQueueFixture.TIMEOUT_MS) {
                assertEquals(0, await { resumed.await() })
            }
            withContext(Dispatchers.Main.immediate) {
                assertEquals(ConnectionState.Disconnected, container.connectionManager.state.value)
                assertNull(container.connectionManager.services)
                assertNull(container.lastConnectionStore.deviceId())
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            release.complete(Unit)
            val settleFailure = runCatching {
                withContext(NonCancellable) {
                    withTimeout(AppBleQueueFixture.TIMEOUT_MS) {
                        await { job.get()?.cancelAndJoin() }
                    }
                }
            }.exceptionOrNull()
            val closeFailure = runCatching { scenario?.close() }.exceptionOrNull()
            for (failure in listOfNotNull(settleFailure, closeFailure)) {
                if (primary == null) {
                    primary = failure
                } else if (primary !== failure) {
                    primary.addSuppressed(failure)
                }
            }
            if (settleFailure != null || closeFailure != null) throw checkNotNull(primary)
        }
    }

    private suspend fun assertMainResponsive() {
        val completed = CompletableDeferred<Unit>()
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable { completed.complete(Unit) }
        try {
            assertTrue(handler.post(task))
            withTimeout(MAIN_TURN_TIMEOUT_MS) { completed.await() }
        } finally {
            handler.removeCallbacks(task)
        }
    }

    private companion object {
        const val CONTROL_TAG = "compose-room-progress-control"
        const val RAW_WAIT_MS = 250L
        const val MAIN_TURN_TIMEOUT_MS = 1000L
    }
}
