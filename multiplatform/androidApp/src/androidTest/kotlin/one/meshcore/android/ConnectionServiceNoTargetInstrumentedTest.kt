package one.meshcore.android

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionServiceNoTargetInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private lateinit var fixture: ConnectionServiceIntentFixture

    @Before
    fun setUp() = runBlocking {
        fixture = ConnectionServiceIntentFixture()
        fixture.open()
    }

    @After
    fun tearDown() = runBlocking {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun freshActivationAndReturnDoNotStartAnAutomaticService() = runBlocking {
        fixture.launchActivity()
        val firstAbsent = awaitServiceAbsent()
        fixture.record("fresh-first")
        returnToForeground()
        val returnAbsent = awaitServiceAbsent()
        fixture.record("fresh-return")
        assertNoSavedTarget()
        fixture.assertNoRadioOrQueueWork()
        assertTrue("LAST: no-target automatic service is absent", firstAbsent && returnAbsent)
    }

    @Test
    fun offlineScopeIsNotAnAutomaticConnectionTarget() = runBlocking {
        fixture.container.lastConnectionStore.persistOfflineScopeIfAbsent(
            ConnectionServiceIntentRadio.RADIO,
            ConnectionServiceIntentRadio.NAME,
        )
        fixture.launchActivity()
        val firstAbsent = awaitServiceAbsent()
        fixture.record("offline-first")
        returnToForeground()
        val returnAbsent = awaitServiceAbsent()
        fixture.record("offline-return")
        assertNoSavedTarget()
        fixture.assertNoRadioOrQueueWork()
        assertTrue("LAST: no-target automatic service is absent", firstAbsent && returnAbsent)
    }

    @Test
    fun anEmptyBootRestorationRetiresItsService() = runBlocking {
        fixture.launchActivity()
        fixture.stopService()
        val restored = fixture.reads.capture()
        ConnectionService.startAfterBoot(fixture.context)
        restored.awaitFinished()
        fixture.waitForMainIdle()
        val absent = awaitServiceAbsent()
        fixture.record("empty-boot-restoration")
        assertNoSavedTarget()
        fixture.assertNoRadioOrQueueWork()
        assertTrue("LAST: no-target automatic service is absent", absent)
    }

    @Test
    fun anOlderEmptyRestorationCannotStopANewerExplicitStart() = runBlocking {
        fixture.launchActivity()
        fixture.stopService()
        val restored = fixture.reads.capture(held = true)
        try {
            ConnectionService.startAfterBoot(fixture.context)
            restored.awaitEntered()
            ConnectionService.start(fixture.context)
            fixture.awaitSecondStartDelivered()
            restored.release.complete(Unit)
            restored.awaitFinished()
            fixture.waitForMainIdle()
            fixture.record("older-empty-restore-new-explicit-start")
            assertNoSavedTarget()
            fixture.assertNoRadioOrQueueWork()
            fixture.assertServiceForeground()
        } finally {
            restored.release.complete(Unit)
        }
    }

    private suspend fun returnToForeground() = coroutineScope {
        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.waitForMainIdle()
        val finished = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(ConnectionServiceIntentFixture.TIMEOUT_MS) { fixture.container.appForeground.first() }
        }
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        finished.await()
        fixture.waitForMainIdle()
    }

    private suspend fun assertNoSavedTarget() {
        assertNull(fixture.container.lastConnectionStore.deviceId())
        assertNull(fixture.container.lastConnectionStore.handle())
        assertFalse(fixture.container.intentStore.restore().isUserDisconnected)
    }

    private suspend fun awaitServiceAbsent(): Boolean = withTimeoutOrNull(ConnectionServiceIntentFixture.TIMEOUT_MS) {
        while (serviceOrNoticePresent()) delay(POLL_MS)
        true
    } == true

    @Suppress("DEPRECATION")
    private fun serviceOrNoticePresent(): Boolean {
        val manager = fixture.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val service = manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ConnectionService::class.java.name
        }
        val notifications = fixture.context.getSystemService(NotificationManager::class.java)
        return service || notifications.activeNotifications.any { it.id == ConnectionNotification.NOTIFICATION_ID }
    }

    companion object {
        private const val POLL_MS = 50L
    }
}
