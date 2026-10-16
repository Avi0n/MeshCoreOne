package one.meshcore.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.clearPendingContactLink
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.util.IdentityHashMap

internal class ActivityIntentReplayFixture(private val nonce: String, private val method: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    private val container = application.container
    private val rows = BackupPostcommitRows(container.database)
    private val directory = File(application.getExternalFilesDir(null), "activity-intent-replay-$nonce-$method")
    private val states = IdentityHashMap<MainActivity, Stage>()
    private var previousActivity: MainActivity? = null
    private var before: Map<String, List<Map<String, Any?>>>? = null
    private var qualified = false
    private var registered = false
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = note(activity, Stage.CREATED)
        override fun onActivityStarted(activity: Activity) = note(activity, Stage.STARTED)
        override fun onActivityResumed(activity: Activity) = note(activity, Stage.RESUMED)
        override fun onActivityPaused(activity: Activity) = note(activity, Stage.PAUSED)
        override fun onActivityStopped(activity: Activity) = note(activity, Stage.STOPPED)
        override fun onActivityDestroyed(activity: Activity) = note(activity, Stage.DESTROYED)
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    init {
        require(Regex("[0-9a-f]{32}").matches(nonce))
        require(method in METHODS)
        identity()
    }

    fun qualify() = runBlocking {
        identity()
        assertFalse(directory.exists())
        assertTrue(directory.mkdirs())
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                container.awaitInitialOfflineData()
                noConnection()
                assertNull(container.coordinator.pendingContactLink.value)
            }
            before = rows.snapshot()
            rows.assertCounts(checkNotNull(before), copies = 0)
            qualified = true
            withContext(Dispatchers.Main) {
                container.onboarding.complete()
                application.registerActivityLifecycleCallbacks(callbacks)
                registered = true
            }
        }
    }

    fun launch(withLink: Boolean = true) {
        main {
            val intent = if (withLink) {
                linkIntent(Intent.ACTION_VIEW)
            } else {
                Intent(application, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            application.startActivity(intent)
        }
        awaitMain {
            val activity = resumed()
            if (activity == null) {
                false
            } else {
                assertTrue((activity.application as MeshCoreApplication).container === container)
                previousActivity = activity
                true
            }
        }
    }

    fun awaitLink() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                container.coordinator.pendingContactLink.first { link ->
                    link != null &&
                        link.publicKey.toUppercaseHex() == KEY_HEX &&
                        link.name == NAME &&
                        link.type == ContactType.CHAT
                }
            }
        }
    }

    fun clearLink() = main {
        assertTrue(container.coordinator.pendingContactLink.value?.publicKey?.toUppercaseHex() == KEY_HEX)
        container.coordinator.clearPendingContactLink()
        assertNull(container.coordinator.pendingContactLink.value)
    }

    fun deliverWarm() {
        main { application.startActivity(linkIntent(Intent.ACTION_VIEW)) }
        awaitLink()
        awaitMain {
            val activity = resumed()
            activity != null &&
                activity === previousActivity &&
                activity.intent.action == Intent.ACTION_VIEW &&
                activity.intent.dataString == URL
        }
    }

    fun recreate(expectPending: Boolean) {
        val old = checkNotNull(previousActivity)
        var task = INVALID_TASK
        main {
            task = old.taskId
            assertTrue(task != INVALID_TASK)
            old.recreate()
        }
        awaitMain {
            val activity = resumed()
            if (states[old] != Stage.DESTROYED || activity == null || activity === old) {
                false
            } else {
                assertTrue(activity.taskId == task)
                assertTrue((activity.application as MeshCoreApplication).container === container)
                assertTrue(activity.intent.action == Intent.ACTION_VIEW && activity.intent.dataString == URL)
                previousActivity = activity
                true
            }
        }
        main {
            assertNull(container.currentRadioId())
            assertFalse(container.connectionManager.state.value.isConnected)
            assertNull(container.connectionManager.services)
            val link = container.coordinator.pendingContactLink.value
            if (expectPending) {
                assertTrue(
                    link?.publicKey?.toUppercaseHex() == KEY_HEX && link.name == NAME && link.type == ContactType.CHAT,
                )
            } else {
                assertNull("Handled external link must not be restaged by recreation", link)
            }
        }
    }

    fun finishAndRelaunch() {
        val finished = checkNotNull(previousActivity)
        main { finished.finish() }
        awaitMain { states[finished] == Stage.DESTROYED }
        launch()
        main { assertTrue("Fresh owner replaces the finished Activity", previousActivity !== finished) }
        awaitLink()
    }

    fun deliverIgnoredNonView() {
        main { application.startActivity(linkIntent(Intent.ACTION_SEND)) }
        awaitMain {
            val activity = resumed()
            activity != null &&
                activity === previousActivity &&
                activity.intent.action == Intent.ACTION_SEND &&
                activity.intent.dataString == URL
        }
        main { assertNull(container.coordinator.pendingContactLink.value) }
    }

    fun record() = runBlocking {
        identity()
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main) { noConnection() }
            assertTrue("External navigation leaves Room unchanged", before == rows.snapshot())
        }
        File(directory, "outcome.json").writeText(
            JSONObject().put("nonce", nonce).put("method", method).put("passed", true)
                .put("uid", Process.myUid()).put("pid", Process.myPid())
                .put("actualApplication", application.javaClass.name).put("actualContainer", container.javaClass.name)
                .put("sameContainer", application.container === container).put("roomRowsUnchanged", true)
                .put("radioActions", false).toString(),
        )
    }

    fun close() {
        var activityClosed = false
        var stagingCleared = false
        try {
            main { states.filterValues { it != Stage.DESTROYED }.keys.toList().forEach { it.finish() } }
            awaitMain { states.values.all { it == Stage.DESTROYED } }
            activityClosed = true
        } finally {
            try {
                if (qualified) {
                    runBlocking {
                        withTimeout(TIMEOUT_MS) {
                            withContext(Dispatchers.Main) {
                                identity()
                                noConnection()
                                container.coordinator.clearPendingContactLink()
                                assertNull(container.coordinator.pendingContactLink.value)
                            }
                            assertTrue(before == rows.snapshot())
                            stagingCleared = true
                        }
                    }
                }
            } finally {
                main {
                    if (registered) application.unregisterActivityLifecycleCallbacks(callbacks)
                    registered = false
                    states.clear()
                    previousActivity = null
                }
                if (directory.exists()) {
                    File(directory, "teardown.json").writeText(
                        JSONObject().put("nonce", nonce).put("method", method).put("uid", Process.myUid())
                            .put(
                                "pid",
                                Process.myPid(),
                            ).put("activityClosed", activityClosed).put("qualified", qualified)
                            .put("stagingCleared", stagingCleared).put(
                                "sameContainer",
                                application.container === container,
                            )
                            .put("observerUnregistered", !registered).toString(),
                    )
                }
            }
        }
    }

    private fun note(activity: Activity, stage: Stage) {
        if (activity is MainActivity && activity.application === application) states[activity] = stage
    }

    private fun resumed(): MainActivity? = states.entries.singleOrNull {
        it.value == Stage.RESUMED && it.key.lifecycle.currentState == Lifecycle.State.RESUMED
    }?.key

    private fun <T> main(action: () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun awaitMain(condition: () -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(DELIVERY_POLL_MS)
        }
    }

    private fun identity() {
        assertTrue(application.packageName == TARGET)
        assertTrue(Process.myUid() == application.applicationInfo.uid)
        assertTrue(application.container === container)
    }

    private suspend fun noConnection() {
        assertFalse(container.connectionManager.state.value.isConnected)
        assertNull(container.connectionManager.services)
        assertNull(container.lastConnectionStore.deviceId())
        assertNull(container.currentRadioId())
    }

    private fun linkIntent(action: String) = Intent(action, URL.toUri(), application, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private enum class Stage { CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED }

    companion object {
        const val ARGUMENT = "activityIntentReplayRun"
        const val TARGET = "io.pocketmesh.app.debug"
        const val TIMEOUT_MS = 20_000L
        private const val DELIVERY_POLL_MS = 25L
        private const val INVALID_TASK = -1
        private const val KEY_LENGTH = 32
        private const val KEY_BYTE = 0x71
        private const val NAME = "Entry fixture"
        private val KEY_HEX = ByteArray(KEY_LENGTH) { KEY_BYTE.toByte() }.toUppercaseHex()
        private val URL = "meshcore://contact/add?name=Entry%20fixture&public_key=$KEY_HEX"
        val METHODS = setOf(
            "coldContactIntentIsNotReplayedAfterRecreate",
            "warmContactIntentIsNotReplayedAfterRecreate",
            "pendingContactReviewSurvivesRecreate",
            "newExplicitDeliveryOfSameContactStillStagesReview",
            "nonViewDataIsNotRouted",
            "freshActivityOwnerAcceptsSameInitialContact",
        )
    }
}
