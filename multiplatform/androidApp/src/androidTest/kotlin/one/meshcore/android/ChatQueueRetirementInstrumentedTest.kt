package one.meshcore.android

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.chat.shutdown
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatQueueRetirementInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule
    val testName = TestName()

    private lateinit var fixture: ChatQueueRetirementFixture

    @Before
    fun setUp() = runBlocking {
        fixture = ChatQueueRetirementFixture(inheritsFloodScope = testName.methodName == INHERIT_METHOD)
        fixture.open()
        fixture.showThread()
        fixture.base.assertNoOutbound()
        fixture.assertRoomAndSessionUnchanged(expectedBatteryReads = 1, expectedFloodCalls = 1)
    }

    @After
    fun tearDown() = runBlocking {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun shutdownDisablesTheMountedSameRadioComposerUntilAFreshOwnerBecomesReady() = runBlocking {
        assertFreshOwnerAfterShutdown()
    }

    @Test
    fun anInheritedChannelKeepsTheExactDefaultRegionAcrossFreshOwnerFrames() = runBlocking {
        assertFreshOwnerAfterShutdown()
    }

    private suspend fun assertFreshOwnerAfterShutdown() {
        val retired = fixture.graph.sendQueue
        fixture.onMain { withTimeout(TIMEOUT_MS) { retired.shutdown() } }
        fixture.stage("standalone-shutdown-returned")
        fixture.assertRetiredWhileReady()
        fixture.assertPrewriteRejection(retired, expectedBatteryReads = 1, expectedFloodCalls = 1)
        fixture.assertRoomAndSessionUnchanged(expectedBatteryReads = 1, expectedFloodCalls = 1)
        fixture.installFreshGraph()
        fixture.assertFreshServices()
        fixture.assertPrewriteRejection(retired, expectedBatteryReads = 2, expectedFloodCalls = 2)
        fixture.assertRoomAndSessionUnchanged(expectedBatteryReads = 2, expectedFloodCalls = 2)
        fixture.base.record("queue-retired-then-fresh-owner", sendEnabled = true)
    }

    @Test
    fun teardownRetiresBeforeHeldStopFetchingAndCancellationCannotReviveTheQueue() = runBlocking {
        fixture.onMain { withTimeout(TIMEOUT_MS) { fixture.graph.sendQueue.shutdown() } }
        fixture.installFreshGraph()
        val retiring = fixture.graph
        fixture.teardown = fixture.onMain {
            fixture.base.container.appScope.async(start = CoroutineStart.UNDISPATCHED) { retiring.tearDown() }
        }
        withTimeout(TIMEOUT_MS) { fixture.gatedSession.stopEntered.await() }
        fixture.stage("real-container-teardown-held-at-first-session-stop")
        assertEquals(1, fixture.gatedSession.stopCalls.get())
        fixture.assertRetiredWhileReady()
        fixture.assertPrewriteRejection(retiring.sendQueue, expectedBatteryReads = 2, expectedFloodCalls = 2)
        fixture.assertRoomAndSessionUnchanged(expectedBatteryReads = 2, expectedFloodCalls = 2)
        val cancelled = CancellationException("Synthetic held teardown cancellation")
        checkNotNull(fixture.teardown).cancel(cancelled)
        fixture.awaitCancellation(cancelled)
        fixture.stage("original-teardown-cancellation-propagated")
        fixture.gatedSession.releaseStop.complete(Unit)
        fixture.onMain {
            withTimeout(TIMEOUT_MS) {
                retiring.tearDown()
                retiring.sendQueue.shutdown()
            }
        }
        assertEquals(2, fixture.gatedSession.stopCalls.get())
        fixture.assertRetiredWhileReady()
        fixture.assertPrewriteRejection(retiring.sendQueue, expectedBatteryReads = 2, expectedFloodCalls = 2)
        fixture.assertRoomAndSessionUnchanged(expectedBatteryReads = 2, expectedFloodCalls = 2)
        fixture.base.record("queue-retired-held-cancelled-idempotent", sendEnabled = false)
    }

    private companion object {
        const val TIMEOUT_MS = ChatQueueRetirementFixture.TIMEOUT_MS
        const val INHERIT_METHOD = "anInheritedChannelKeepsTheExactDefaultRegionAcrossFreshOwnerFrames"
    }
}
