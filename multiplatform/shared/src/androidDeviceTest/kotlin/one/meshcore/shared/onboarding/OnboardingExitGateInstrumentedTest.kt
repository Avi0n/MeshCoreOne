package one.meshcore.shared.onboarding

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.connect
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.meshcore.MeshCoreSession
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.DeepLinkLookups
import one.meshcore.shared.navigation.DeepLinkRouter
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.transport.MeshLink
import one.meshcore.shared.transport.MeshLinkListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE W1 EXIT GATE (the shell/UX half). Runs on the emulator with a fake link injected in place of BLE
 * (the brief's "skip real BLE on the emulator") and proves three W1 behaviors end-to-end through the
 * REAL Swift bridge:
 *
 *  1. Onboarding completes: the step machine walks Welcome→Preset, a WiFi/pair connect over the fake
 *     transport reaches Ready through the real `MeshCoreSession`, the region is committed, and
 *     `hasCompletedOnboarding` is persisted.
 *  2. A deep link routes while connected: a `meshcore://map` link moves the coordinator to the Map tab.
 *  3. The connection is process-scoped: rebuilding the UI coordinator (an Activity recreation) leaves the
 *     live connection on the manager untouched — the foreground-service ownership model in miniature.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber") // Numeric timings and region payloads are deliberate test fixtures.
class OnboardingExitGateInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "onboarding-gate-${System.nanoTime()}.db"
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        db = meshDatabase(context, Dispatchers.IO, dbName)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun onboardingCompletesAndDeepLinkRoutesWhileConnected() = runBlocking {
        val link = ScriptedLink()
        // The device reports maxChannels=8, so the post-connect ceremony's pipelined channel sync primes a
        // getChannel(index) request per slot; each answers as unconfigured (empty name, zero secret) so
        // the fast path completes instead of falling to the slow serial-retry reconciliation. The backlog
        // drain's getMessage loop stops on the first reply, so one noMoreMessages answers it.
        val responder = scope.launch {
            while (true) {
                val command = link.nextOutbound()
                when (command[0].toInt() and 0xFF) {
                    CMD_APP_START -> link.deliver(selfInfoFrame())
                    CMD_DEVICE_QUERY -> link.deliver(deviceInfoFrame())
                    CMD_GET_CONTACTS -> {
                        link.deliver(contactStartFrame())
                        link.deliver(contactEndFrame())
                    }
                    CMD_GET_CHANNEL -> link.deliver(unconfiguredChannelInfoFrame(command[1].toInt() and 0xFF))
                    CMD_GET_MESSAGE -> link.deliver(noMoreMessagesFrame())
                    // iOS issues both during connect too: getAutoAddConfig overlaps the BLE roundtrip
                    // and getTime feeds the clock-drift check. A real radio answers them.
                    CMD_GET_AUTO_ADD_CONFIG -> link.deliver(autoAddConfigFrame())
                    CMD_GET_TIME -> link.deliver(currentTimeFrame())
                }
            }
        }

        val prefs = InMemoryPreferencesStore()
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { link },
            sessionFactory = { MeshCoreSession(it) },
            nowEpochSeconds = { NOW_EPOCH_SECONDS },
            ports = ConnectionManagerPorts(
                newRadioId = { Uuid.normalized("A0000000-0000-0000-0000-000000000010") },
            ),
        )
        val onboarding = OnboardingViewModel(prefs)

        // 1) Walk the onboarding step machine; the Pair step's connect reaches Ready over the fake link.
        onboarding.start()
        assertEquals(OnboardingStep.PERMISSIONS, onboarding.currentStep)
        onboarding.advance()
        assertEquals(OnboardingStep.PAIR, onboarding.currentStep)

        withTimeout(TIMEOUT_MS) { manager.connect(DEVICE) }
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)

        onboarding.advance()
        assertEquals(OnboardingStep.REGION, onboarding.currentStep)
        onboarding.commitRegion(RegionSelection(countryCode = "US", source = RegionSource.MANUAL))
        assertEquals(OnboardingStep.PRESET, onboarding.currentStep)
        onboarding.advance() // completes

        assertTrue("onboarding must complete", onboarding.completed.value)
        assertEquals(true, prefs.getBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING))

        // 2) Deep link routes while connected.
        val coordinator = NavigationCoordinator(scope)
        val router = DeepLinkRouter(coordinator, DisconnectedLookups)
        val handled = router.routeExternalOpen("meshcore://map?lat=37.33&lon=-122.03")
        assertTrue("map deep link must be handled", handled)
        assertEquals(AppTab.MAP.index, coordinator.selectedTab.value)
        assertNotNull(coordinator.pendingMapFocus.value)

        // 3) Rebuilding the UI coordinator (Activity recreation) leaves the process-scoped connection up.
        NavigationCoordinator(scope)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertNotNull("services survive a UI rebuild", manager.services)

        responder.cancel()
        withTimeout(TIMEOUT_MS) { manager.disconnect() }
    }

    /** A [MeshLink] whose outbound frames the test reads and whose inbound frames the test injects. */
    private class ScriptedLink : MeshLink {
        private var listener: MeshLinkListener? = null
        private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

        override fun setListener(listener: MeshLinkListener?) {
            this.listener = listener
        }

        override suspend fun connect() = Unit

        override suspend fun disconnect() = Unit

        override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) {
            outbound.trySend(bytes)
        }

        suspend fun nextOutbound(): ByteArray = outbound.receive()

        fun deliver(frame: ByteArray) {
            listener?.onReceived(frame)
        }
    }

    private object DisconnectedLookups : DeepLinkLookups {
        override suspend fun ownPublicKey(): ByteArray? = null
        override suspend fun existingContactKeyHexes(): Set<String> = emptySet()
        override suspend fun existingChannelSecretHexes(): Set<String> = emptySet()
    }

    private companion object {
        const val DEVICE = "emulator-fake-radio"
        const val TIMEOUT_MS = 10_000L
        const val NOW_EPOCH_SECONDS = 1_000.0

        const val CMD_APP_START = 0x01
        const val CMD_GET_CONTACTS = 0x04
        const val CMD_GET_MESSAGE = 0x0A
        const val CMD_DEVICE_QUERY = 0x16
        const val CMD_GET_CHANNEL = 0x1F
        const val CMD_GET_TIME = 0x05
        const val CMD_GET_AUTO_ADD_CONFIG = 0x3B

        const val RES_SELF_INFO = 0x05
        const val RES_CONTACT_START = 0x02
        const val RES_CONTACT_END = 0x04
        const val RES_DEVICE_INFO = 0x0D
        const val RES_NO_MORE_MESSAGES = 0x0A
        const val RES_CURRENT_TIME = 0x09
        const val RES_AUTO_ADD_CONFIG = 0x19
        const val RES_CHANNEL_INFO = 0x12

        const val PUBLIC_KEY_LENGTH = 32

        // `Parsers.ChannelInfo` layout: [index:1][name:32 null-padded][secret:16].
        const val CHANNEL_NAME_FIELD_LENGTH = 32
        const val CHANNEL_SECRET_LENGTH = 16

        // Frame layouts mirror the Swift MeshCore parsers byte-for-byte (see ConnectionExitGateInstrumentedTest).
        fun selfInfoFrame(): ByteArray = frame(RES_SELF_INFO) {
            add(1)
            add(22)
            add(22)
            repeat(PUBLIC_KEY_LENGTH) { add(0x01) }
            le32(0)
            le32(0)
            le32(0)
            le32(915_000)
            le32(125_000)
            add(7)
            add(5)
            ascii("Test")
        }

        fun deviceInfoFrame(): ByteArray = frame(RES_DEVICE_INFO) {
            add(10)
            add(50)
            add(8)
            le32(0)
            fixedAscii("01 Jan 2026", 12)
            fixedAscii("Heltec", 40)
            fixedAscii("v1.15.0", 20)
            add(0)
            add(0)
        }

        fun contactStartFrame(): ByteArray = frame(RES_CONTACT_START) { le32(0) }

        fun contactEndFrame(): ByteArray = frame(RES_CONTACT_END) { le32(0) }

        /** Answers `getChannel(index)` as unconfigured (empty name, zero secret) — an unset slot. */
        fun unconfiguredChannelInfoFrame(index: Int): ByteArray = frame(RES_CHANNEL_INFO) {
            add(index)
            repeat(CHANNEL_NAME_FIELD_LENGTH) { add(0) }
            repeat(CHANNEL_SECRET_LENGTH) { add(0) }
        }

        /** Ends the backlog drain's `getMessage` loop; the test radio has no queued messages. */
        fun noMoreMessagesFrame(): ByteArray = frame(RES_NO_MORE_MESSAGES) {}

        /** Device clock, LE u32 seconds, matching the phone so the drift check stays quiet. */
        fun currentTimeFrame(): ByteArray = frame(RES_CURRENT_TIME) {
            val epoch = NOW_EPOCH_SECONDS.toLong().toInt()
            add(epoch and 0xFF)
            add((epoch shr 8) and 0xFF)
            add((epoch shr 16) and 0xFF)
            add((epoch shr 24) and 0xFF)
        }

        /** Auto-add off, zero max hops. */
        fun autoAddConfigFrame(): ByteArray = frame(RES_AUTO_ADD_CONFIG) {
            add(0)
            add(0)
        }

        private fun frame(code: Int, body: Builder.() -> Unit): ByteArray {
            val b = Builder()
            b.add(code)
            b.body()
            return b.toByteArray()
        }

        private class Builder {
            private val out = ArrayList<Byte>()
            fun add(v: Int) {
                out.add(v.toByte())
            }
            fun le32(v: Int) {
                add(v and 0xFF)
                add((v ushr 8) and 0xFF)
                add((v ushr 16) and 0xFF)
                add(
                    (v ushr 24) and 0xFF,
                )
            }
            fun ascii(v: String) {
                v.encodeToByteArray().forEach { out.add(it) }
            }
            fun fixedAscii(v: String, len: Int) {
                val e = v.encodeToByteArray()
                for (i in 0 until len) out.add(if (i < e.size) e[i] else 0)
            }
            fun toByteArray(): ByteArray = out.toByteArray()
        }
    }
}
