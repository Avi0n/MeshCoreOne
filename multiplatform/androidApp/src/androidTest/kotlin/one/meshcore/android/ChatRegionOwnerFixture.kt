package one.meshcore.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionIntent
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.ServiceContainerIdentity
import one.meshcore.shared.connection.ServiceContainerRuntime
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChannelDao
import one.meshcore.shared.persistence.ChannelEntity
import one.meshcore.shared.persistence.floodScope
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.transport.MeshLink
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class ChatRegionOwnerFixture {
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as MeshCoreApplication
    private val root = File(application.cacheDir, "chat-region-owner-${System.nanoTime()}")
    private val graphs = mutableListOf<ServiceContainer>()
    private val allocations = AtomicInteger()
    private val committed = CompletableDeferred<Unit>()
    private val releaseCommit = CompletableDeferred<Unit>()
    private var originalDao: Pair<Field, Any>? = null
    lateinit var container: AppContainer
        private set
    val captured = SessionCalls()
    val replacement = SessionCalls()

    suspend fun open() {
        check(application.packageName == DEBUG_PACKAGE)
        val live = application.container.connectionManager
        check(live.services == null)
        check(field(ConnectionManager::class.java, "link").get(live) == null)
        check(field(ConnectionManager::class.java, "session").get(live) == null)
        root.mkdirs()
        onMain {
            container = AppContainer(IsolatedContext(application, root))
            installTransportGuards()
        }
        container.awaitInitialOfflineData()
        container.intentStore.persist(ConnectionIntent.UserDisconnected)
        val data = ChatRadioNotificationData
        container.database.deviceDao().upsert(
            data.device(data.RADIO_A).copy(knownRegions = listOf(REGION_A, KEEP_A), defaultFloodScopeName = REGION_A),
        )
        container.database.deviceDao().upsert(
            data.device(data.RADIO_B).copy(knownRegions = listOf(REGION_B), defaultFloodScopeName = REGION_B),
        )
        container.database.channelDao().upsert(data.channel(data.RADIO_A).copy(regionScope = REGION_A))
        container.database.channelDao().upsert(data.channel(data.RADIO_B).copy(regionScope = REGION_B))
        onMain { installOwner(data.RADIO_A, captured, REGION_A, listOf(REGION_A, KEEP_A)) }
    }

    suspend fun holdChannelCommit(scope: ChannelFloodScope) = onMain {
        check(originalDao == null)
        val database = container.database
        val slot = field(database.javaClass, "_channelDao")
        originalDao = slot to checkNotNull(slot.get(database))
        val delegate = database.channelDao()
        val armed = AtomicBoolean(true)
        val gated = object : ChannelDao by delegate {
            override suspend fun upsert(entity: ChannelEntity) {
                delegate.upsert(entity)
                if (entity.id == ChatRadioNotificationData.CHANNEL_A &&
                    entity.floodScope == scope &&
                    armed.compareAndSet(true, false)
                ) {
                    committed.complete(Unit)
                    releaseCommit.await()
                }
            }
        }
        slot.set(database, lazyOf(gated))
        check(database.channelDao() === gated)
    }

    suspend fun awaitCommitted() = withTimeout(TIMEOUT_MS) { committed.await() }

    fun release() {
        releaseCommit.complete(Unit)
    }

    suspend fun replaceOwner(sameRadio: Boolean) = onMain {
        val radio = if (sameRadio) ChatRadioNotificationData.RADIO_A else ChatRadioNotificationData.RADIO_B
        val region = if (sameRadio) REPLACEMENT_A else REGION_B
        installOwner(radio, replacement, region, listOf(region))
    }

    suspend fun launchAction(action: suspend () -> Unit): Deferred<Unit> = onMain {
        container.appScope.async(start = CoroutineStart.UNDISPATCHED) { action() }
    }

    suspend fun join(action: Deferred<Unit>) = withTimeout(TIMEOUT_MS) { action.await() }

    suspend fun assertNoPhysicalWork() {
        check(allocations.get() == 0)
        check(captured.otherCalls.get() == 0 && replacement.otherCalls.get() == 0)
        check(container.database.messageDao().listAll().isEmpty())
        check(container.database.pendingSendDao().listOrphans().isEmpty())
        for (radio in listOf(ChatRadioNotificationData.RADIO_A, ChatRadioNotificationData.RADIO_B)) {
            check(container.database.pendingSendDao().listByRadio(radio).isEmpty())
        }
        val receipt = JSONObject()
            .put("fixture", "synthetic late Room owner")
            .put("committedWriteReached", committed.isCompleted)
            .put("transportAllocations", allocations.get())
            .put("capturedBatteryReads", captured.batteryReads.get())
            .put("replacementBatteryReads", replacement.batteryReads.get())
            .put("otherCapturedCommands", captured.otherCalls.get())
            .put("otherReplacementCommands", replacement.otherCalls.get())
            .put("capturedDefaultWrites", captured.defaultNames.size)
            .put("replacementDefaultWrites", replacement.defaultNames.size)
            .put("capturedFloodWrites", captured.floodTexts.size)
            .put("replacementFloodWrites", replacement.floodTexts.size)
        println(receipt)
    }

    suspend fun close() {
        releaseCommit.complete(Unit)
        try {
            if (::container.isInitialized) {
                originalDao?.let { (slot, value) -> slot.set(container.database, value) }
                originalDao = null
                onMain {
                    field(ConnectionManager::class.java, "services").set(container.connectionManager, null)
                    field(ConnectionManager::class.java, "session").set(container.connectionManager, null)
                    withTimeout(TIMEOUT_MS) {
                        for (graph in graphs) graph.tearDown()
                        container.appScope.coroutineContext[Job]?.cancelAndJoin()
                    }
                }
            }
        } finally {
            if (::container.isInitialized) container.database.close()
            root.deleteRecursively()
        }
    }

    suspend fun <T> onMain(action: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { action() }

    private fun installOwner(radio: Uuid, calls: SessionCalls, defaultName: String, known: List<String>) {
        val data = ChatRadioNotificationData
        val graph = ServiceContainer(
            session = calls.session,
            database = container.database,
            identity = ServiceContainerIdentity(radio, data.device(radio).publicKey, data.device(radio).nodeName),
            runtime = ServiceContainerRuntime(
                scope = container.appScope,
                nodePasswordStore = InMemoryNodePasswordStore(),
                nowEpochSeconds = { NOW_SECONDS },
            ),
        )
        graphs += graph
        val manager = container.connectionManager
        field(ConnectionManager::class.java, "services").set(manager, graph)
        field(ConnectionManager::class.java, "session").set(manager, calls.session)
        @Suppress("UNCHECKED_CAST")
        val state = field(ConnectionManager::class.java, "mutableState").get(manager)
            as MutableStateFlow<ConnectionState>
        state.value = ConnectionState.Ready(data.device(radio).id.value)
        container.connectedDefaultFloodScopeName.value = defaultName
        container.connectedKnownRegions.set(known)
        container.connectedSupportsUnscopedFloodSend.set(true)
    }

    private fun installTransportGuards() {
        val refuseBle: (String) -> MeshLink = { refuseTransport() }
        val refuseWifi: (String, Int, suspend () -> Unit) -> MeshLink = { _, _, _ -> refuseTransport() }
        val manager = container.connectionManager
        field(ConnectionManager::class.java, "linkFactory").set(manager, refuseBle)
        field(ConnectionManager::class.java, "reconnectLinkFactory").set(manager, refuseBle)
        field(ConnectionManager::class.java, "wifiLinkFactory").set(manager, refuseWifi)
    }

    private fun refuseTransport(): Nothing {
        allocations.incrementAndGet()
        error("Physical transport allocation is forbidden in the region-owner fixture")
    }

    class SessionCalls {
        private val delegate = RecordingReplySession()
        val defaultNames = mutableListOf<String>()
        val defaultReads = AtomicInteger()
        val floodTexts = mutableListOf<String>()
        val batteryReads = AtomicInteger()
        val otherCalls = AtomicInteger()
        val session: MeshSession = Proxy.newProxyInstance(
            MeshSession::class.java.classLoader,
            arrayOf(MeshSession::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "setDefaultFloodScope" -> {
                    defaultNames += arguments!![0] as String
                    Unit
                }
                "getDefaultFloodScope" -> {
                    defaultReads.incrementAndGet()
                    null
                }
                "setFloodScope" -> {
                    floodTexts += arguments!![1] as String
                    Unit
                }
                "setFloodScopeUnscoped" -> {
                    floodTexts += "unscoped"
                    Unit
                }
                "getBattery" -> {
                    batteryReads.incrementAndGet()
                    method.invoke(delegate, *(arguments ?: emptyArray()))
                }
                "getEvents" -> delegate.events
                "close" -> Unit
                "toString" -> "Synthetic region owner session"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> arguments?.firstOrNull() === proxy
                else -> {
                    otherCalls.incrementAndGet()
                    try {
                        method.invoke(delegate, *(arguments ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                }
            }
        } as MeshSession
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
        const val REGION_A = "Synthetic A removed default"
        const val KEEP_A = "Synthetic A retained region"
        const val REGION_B = "Synthetic B untouched default"
        const val REPLACEMENT_A = "Synthetic new A untouched default"
        const val LOCAL_SCOPE = "Synthetic A local choice"
        const val TIMEOUT_MS = 8_000L
        private const val DEBUG_PACKAGE = "io.pocketmesh.app.debug"
        private const val NOW_SECONDS = 1_800_000_000.0

        private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    }
}
