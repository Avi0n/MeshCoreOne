package one.meshcore.android

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.DeviceSelectionFilter
import one.meshcore.shared.model.MessageDirection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AppStickyReadbackInstrumentedTest {
    @Test
    fun freshProcessReadsDurableStateWithoutLaunchingActivity() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit sticky readback opt-in required", arguments.getString(ARGUMENT) == "true")
        try {
            val nonce = checkNotNull(arguments.getString("mc1BleColdNonce"))
            check(nonce.matches(Regex("[a-f0-9]{32}")))
            val expectedDevice = checkNotNull(arguments.getString("mc1StickyDeviceId"))
            val expectedRadio = checkNotNull(arguments.getString("mc1StickyRadioId"))
            val expectedKey = checkNotNull(arguments.getString("mc1BleColdPublicKeySha256"))
            val expectedHandle = checkNotNull(arguments.getString("mc1BleColdHandle"))
            check(expectedDevice.matches(Regex(UUID_PATTERN)) && expectedRadio.matches(Regex(UUID_PATTERN)))
            check(expectedKey.matches(Regex("[a-f0-9]{64}")))
            check(expectedHandle.matches(Regex("(?i)([a-f0-9]{2}:){5}[a-f0-9]{2}")))
            val application = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as MeshCoreApplication
            val container = application.container
            val manager = container.connectionManager
            suspend fun assertState() {
                withContext(Dispatchers.Main.immediate) {
                    assertSame(container, application.container)
                    assertEquals(ConnectionState.Disconnected, manager.state.value)
                    assertNull(manager.services)
                    assertNull(manager.currentTransportType)
                }
                val device = container.database.deviceDao().listAll().single()
                assertTrue("Durable device identity matches seed", device.id.value == expectedDevice)
                assertTrue("Durable radio partition matches seed", device.radioID.value == expectedRadio)
                assertEquals(expectedKey, digest(device.publicKey))
                assertTrue("Saved device identity matches", container.lastConnectionStore.deviceId() == device.id)
                assertTrue("Saved radio partition matches", container.lastConnectionStore.radioId() == device.radioID)
                assertTrue("Saved handle matches", container.lastConnectionStore.handle() == expectedHandle)
                assertTrue("BLE descriptor exists", device.connectionMethods.any { it is ConnectionMethod.Bluetooth })
                assertTrue("Wi-Fi descriptor is absent", device.connectionMethods.none { it is ConnectionMethod.WiFi })
                assertTrue(
                    "Saved BLE descriptor matches",
                    DeviceSelectionFilter.bleHandle(device.connectionMethods, container.lastConnectionStore.handle()) ==
                        expectedHandle,
                )
                assertTrue("Explicit disconnect was not persisted", !container.intentStore.restore().isUserDisconnected)
                assertEquals(
                    0,
                    container.database.messageDao().listAll().count {
                        it.directionRawValue == MessageDirection.OUTGOING.raw
                    },
                )
                container.database.useConnection(isReadOnly = true) { connection ->
                    for (sql in listOf(
                        "SELECT COUNT(*) FROM pending_send",
                        "SELECT COUNT(*) FROM room_message WHERE isFromSelf = 1",
                    )) {
                        connection.usePrepared(sql) {
                            assertTrue(it.step())
                            assertEquals(0L, it.getLong(0))
                            assertTrue(!it.step())
                        }
                    }
                }
            }
            withTimeout(CHECK_TIMEOUT_MS) {
                container.awaitInitialOfflineData()
                assertState()
            }
            val directory = File(application.filesDir, "ble-cold-$nonce")
            check(directory.isDirectory)
            val target = File(directory, "sticky-readback.json")
            val temporary = File(directory, "sticky-readback.writing")
            val ack = File(directory, "sticky-readback.ack")
            check(!target.exists() && !temporary.exists() && !ack.exists())
            val value = JSONObject().put("nonce", nonce).put("stage", "sticky-readback")
                .put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("deviceId", expectedDevice).put("radioId", expectedRadio).put("publicKeySha256", expectedKey)
                .put("actualDefaultApplication", true).put("graphReplaced", false)
                .put("activityOrConnectionStarted", false).put("managerIdle", true)
                .put("userDisconnected", false).put("pendingCount", 0).put("outgoingCount", 0)
                .put("selfRoomCount", 0).put("nonAtomicReads", true)
                .put("originalRecoveryAcceptanceIsExternal", true)
            FileOutputStream(temporary).use {
                it.write(value.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            withTimeout(ACK_TIMEOUT_MS) {
                while (!ack.exists()) delay(ACK_POLL_MS)
                check(ack.readText() == "$nonce:sticky-readback:${Process.myPid()}\n")
            }
            withTimeout(CHECK_TIMEOUT_MS) { assertState() }
        } catch (_: Throwable) {
            throw AssertionError("Sticky durable readback failed; private diagnostics suppressed")
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val ARGUMENT = "mc1StickyReadback"
        private const val CHECK_TIMEOUT_MS = 20_000L
        private const val ACK_TIMEOUT_MS = 30_000L
        private const val ACK_POLL_MS = 50L
        private const val UUID_PATTERN = "[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"
    }
}
