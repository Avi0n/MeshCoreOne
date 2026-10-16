package one.meshcore.android

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.app.AppStateViewModel
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import one.meshcore.shared.transport.MeshLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal class ConnectionServiceIntentFixture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    val context: Context get() = application
    private val original = application.container
    private val fixtureId = System.nanoTime().toString()
    private val root = File(application.cacheDir, "service-intent-$fixtureId")
    private val allocations = AtomicInteger()
    private var installed = false
    lateinit var container: AppContainer
        private set
    lateinit var reads: ConnectionServiceIntentRead
        private set
    lateinit var scenario: ActivityScenario<MainActivity>
        private set

    suspend fun open() {
        check(application.packageName == DEBUG_PACKAGE)
        check(original.connectionManager.services == null)
        check(field(ConnectionManager::class.java, "link").get(original.connectionManager) == null)
        check(field(ConnectionManager::class.java, "session").get(original.connectionManager) == null)
        check(original.connectionManager.state.value == ConnectionState.Disconnected)
        check(original.lastConnectionStore.deviceId() == null)
        ConnectionService.stop(context)
        await("previous debug service stopped") { !serviceRunning() && !noticePresent() }
        root.mkdirs()
        withContext(Dispatchers.Main.immediate) { container = AppContainer(IsolatedContext(application, root)) }
        container.awaitInitialOfflineData()
        container.onboarding.complete()
        reads = ConnectionServiceIntentRead(container.prefs)
        val intent = ConnectionIntentStore(reads)
        field(AppContainer::class.java, "intentStore").set(container, intent)
        field(AppStateViewModel::class.java, "intentStore").set(container.appState, intent)
        field(ConnectionManager::class.java, "intentStore").set(container.connectionManager, intent)
        val refuseBle: (String) -> MeshLink = { refuseTransport() }
        val refuseWifi: (String, Int, suspend () -> Unit) -> MeshLink = { _, _, _ -> refuseTransport() }
        field(ConnectionManager::class.java, "linkFactory").set(container.connectionManager, refuseBle)
        field(ConnectionManager::class.java, "reconnectLinkFactory").set(container.connectionManager, refuseBle)
        field(ConnectionManager::class.java, "wifiLinkFactory").set(container.connectionManager, refuseWifi)
        field(ConnectionManager::class.java, "bluetoothConnectAuthorized").set(container.connectionManager, { false })
        instrumentation.runOnMainSync { applicationField.set(application, container) }
        installed = true
    }

    suspend fun launchActivity() = coroutineScope {
        val foreground = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) { container.appForeground.first() }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        foreground.await()
        waitForMainIdle()
    }

    suspend fun stopService() {
        ConnectionService.stop(context)
        await("fixture service stopped") { !serviceRunning() && !noticePresent() }
    }

    suspend fun seedSavedRadio() {
        container.database.deviceDao().upsert(ConnectionServiceIntentRadio.entity())
        container.lastConnectionStore.persist(
            ConnectionServiceIntentRadio.DEVICE,
            ConnectionServiceIntentRadio.RADIO,
            ConnectionServiceIntentRadio.NAME,
            ConnectionServiceIntentRadio.HANDLE,
        )
    }

    fun assertServiceAbsent() {
        await("restore must remove its service and notification") { !serviceRunning() && !noticePresent() }
        assertFalse(serviceRunning())
        assertFalse(noticePresent())
    }

    fun assertServiceForeground() {
        await("wanted or explicit service remains foreground") { serviceForeground() && noticePresent() }
        assertTrue(serviceForeground())
        assertTrue(noticePresent())
    }

    fun awaitSecondStartDelivered() {
        await("new explicit start acknowledged by the actual service") {
            val dump = serviceDump()
            val lastStart = Regex("lastStartId=(\\d+)").find(dump)?.groupValues?.get(1)?.toIntOrNull()
            val executing = Regex("executeNesting=(\\d+)").find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            lastStart != null && lastStart >= SECOND_START_ID && executing == 0 && !dump.contains("Pending Starts:")
        }
        waitForMainIdle()
    }

    fun waitForMainIdle() {
        instrumentation.waitForIdleSync()
    }

    suspend fun assertNoRadioOrQueueWork() {
        assertEquals("no production transport factory may be reached", 0, allocations.get())
        assertTrue(container.database.messageDao().listAll().isEmpty())
        assertTrue(container.database.pendingSendDao().listByRadio(ConnectionServiceIntentRadio.RADIO).isEmpty())
        assertTrue(container.database.pendingSendDao().listOrphans().isEmpty())
    }

    suspend fun record(label: String) {
        val receipt = JSONObject()
            .put("label", label)
            .put("fixtureId", fixtureId)
            .put("serviceRunning", serviceRunning())
            .put("serviceForeground", serviceForeground())
            .put("notice1001Present", noticePresent())
            .put("transportAllocations", allocations.get())
            .put("state", container.connectionManager.state.value.toString())
            .put("userExplicitlyDisconnected", container.intentStore.restore().isUserDisconnected)
            .put("messages", container.database.messageDao().listAll().size)
            .put("pending", container.database.pendingSendDao().listByRadio(ConnectionServiceIntentRadio.RADIO).size)
        val evidence = File(application.getExternalFilesDir(null), "connection-service-intent").apply { mkdirs() }
        File(evidence, "$fixtureId-$label.json").writeText(receipt.toString(2))
        println(receipt)
    }

    suspend fun close() {
        if (::reads.isInitialized) reads.releasePending()
        try {
            if (::scenario.isInitialized) scenario.close()
            stopService()
            if (::container.isInitialized) record("teardown")
        } finally {
            context.stopService(Intent(context, ConnectionService::class.java))
            await("real service released before fixture restoration") { !serviceRunning() && !noticePresent() }
            if (::container.isInitialized) {
                withTimeout(TIMEOUT_MS) {
                    withContext(Dispatchers.Main.immediate) {
                        container.appScope.coroutineContext[Job]?.cancelAndJoin()
                    }
                }
            }
            if (installed) {
                instrumentation.runOnMainSync {
                    check(application.container === container)
                    applicationField.set(application, original)
                }
                installed = false
            }
            if (::container.isInitialized) container.database.close()
            root.deleteRecursively()
        }
        assertTrue(application.container === original)
    }

    private fun refuseTransport(): Nothing {
        allocations.incrementAndGet()
        throw BluetoothUnauthorizedException(SecurityException("fixture refuses physical transport allocation"))
    }

    @Suppress("DEPRECATION")
    private fun serviceInfo(): ActivityManager.RunningServiceInfo? =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == ConnectionService::class.java.name }

    private fun serviceRunning(): Boolean = serviceInfo() != null

    private fun serviceForeground(): Boolean = serviceInfo()?.foreground == true

    private fun noticePresent(): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == ConnectionNotification.NOTIFICATION_ID }

    private fun serviceDump(): String {
        val command = "dumpsys activity services $DEBUG_PACKAGE"
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(description, condition())
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = directory("files")

        override fun getCacheDir(): File = directory("cache")

        override fun getNoBackupFilesDir(): File = directory("nobackup")

        override fun getDataDir(): File = root

        override fun getDatabasePath(name: String): File = File(directory("databases"), name)

        private fun directory(name: String): File = File(root, name).apply { mkdirs() }
    }

    companion object {
        const val TIMEOUT_MS = 8_000L
        private const val POLL_MS = 25L
        private const val SECOND_START_ID = 2
        private const val DEBUG_PACKAGE = "io.pocketmesh.app.debug"
        private val applicationField = field(MeshCoreApplication::class.java, "container")

        private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    }
}
