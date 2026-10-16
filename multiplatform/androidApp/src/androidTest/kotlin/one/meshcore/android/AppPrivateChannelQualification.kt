package one.meshcore.android

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChannelEntity
import one.meshcore.shared.persistence.DeviceEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class AppPrivateChannelQualification {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    private val container = application.container
    private val manager = container.connectionManager

    suspend fun verify(owner: ServiceContainer, checkpoints: AppBleColdCheckpoints) {
        try {
            verifyCurrent(owner, checkpoints)
        } catch (_: Throwable) {
            throw AssertionError("Private channel qualification failed; private diagnostics suppressed")
        }
    }

    private suspend fun verifyCurrent(owner: ServiceContainer, checkpoints: AppBleColdCheckpoints) {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString(ARGUMENT) == "true")
        check(arguments.getString(AppBleColdCheckpoints.PHASE_ARGUMENT) == "seed")
        check(
            arguments.getString("class") ==
                "${AppBleColdCheckpoints.CLASS}#${AppBleColdCheckpoints.METHODS.getValue("seed")}",
        )
        val controls = verifyControls()
        val radioId = withContext(Dispatchers.Main.immediate) {
            val captured = owner.radioId
            assertOwner(owner, checkpoints, captured)
            captured
        }
        assertEmpty()
        val deviceBefore = assertDevice(radioId, checkpoints)
        val before = selectedChannel(radioId, owner.maxChannels)
        val expectedSecret = before.secret.copyOf()
        try {
            assertTrue("Stored Test secret meets the private eligibility checks", eligibleSecret(expectedSecret))
            val live = checkNotNull(owner.channelService.fetchChannel(before.index)) { "Live Test slot is absent" }
            val liveReadAt = SystemClock.elapsedRealtime()
            assertTrue("Live Test slot and name match", live.index == before.index && live.name == CHANNEL)
            assertTrue("Live Test secret meets the private eligibility checks", eligibleSecret(live.secret))
            assertTrue("Live Test secret matches the selected local row", live.secret.contentEquals(expectedSecret))
            withContext(Dispatchers.Main.immediate) { assertOwner(owner, checkpoints, radioId) }
            val after = selectedChannel(radioId, owner.maxChannels)
            assertTrue(
                "Selected Test identity is stable",
                before.id == after.id &&
                    before.radioID == after.radioID &&
                    before.index == after.index,
            )
            assertTrue("Selected Test secret is stable", after.secret.contentEquals(expectedSecret))
            val device = assertDevice(radioId, checkpoints)
            assertTrue("Device identity is stable across the live read", device.id == deviceBefore.id)
            assertEmpty()
            withContext(Dispatchers.Main.immediate) { assertOwner(owner, checkpoints, radioId) }
            publish(
                checkpoints,
                JSONObject().put("nonce", checkpoints.nonce).put("stage", "private-qualified")
                    .put("pid", Process.myPid()).put("uid", Process.myUid())
                    .put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).put("liveReadCompletedAtMs", liveReadAt)
                    .put("radioId", device.radioID.value).put("deviceId", device.id.value)
                    .put("publicKeySha256", checkpoints.keyHash).put("slot", before.index).put("channelName", CHANNEL)
                    .put("actualDefaultApplication", true).put("graphReplaced", false)
                    .put("liveMatchesStoredSecret", true).put("privateEligibilityPassed", true)
                    .put("eligibilityControlsPassed", controls).put("pendingCount", 0).put("outgoingCount", 0)
                    .put("selfRoomCount", 0).put("secretOrDigestExported", false)
                    .put("laterSessionContinuityProved", false).put("nonAtomicReads", true),
            )
        } finally {
            expectedSecret.fill(0)
        }
    }

    private fun assertOwner(owner: ServiceContainer, checkpoints: AppBleColdCheckpoints, radioId: Uuid) {
        assertTrue("Default app container is unchanged", application.container === container)
        assertTrue("Expected service graph is current", manager.services === owner)
        assertTrue("Expected radio is Ready", manager.state.value == ConnectionState.Ready(checkpoints.handle))
        assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
        assertTrue("Graph partition is current", manager.radioId == radioId && owner.radioId == radioId)
        assertTrue("Graph monitoring is active", owner.isEventMonitoringActive)
        assertTrue("Graph send queue is active", !owner.sendQueue.isRetired)
        assertEquals(checkpoints.keyHash, digest(owner.selfPublicKey))
    }

    private suspend fun assertDevice(radioId: Uuid, checkpoints: AppBleColdCheckpoints): DeviceEntity {
        val device = container.database.deviceDao().listAll().single()
        assertTrue("Device partition matches the captured owner", device.radioID == radioId)
        assertEquals(checkpoints.keyHash, digest(device.publicKey))
        assertTrue("Saved device identity matches", container.lastConnectionStore.deviceId() == device.id)
        assertTrue("Saved radio identity matches", container.lastConnectionStore.radioId() == device.radioID)
        assertTrue(
            "Saved handle matches the authorized radio",
            container.lastConnectionStore.handle() == checkpoints.handle,
        )
        return device
    }

    private suspend fun selectedChannel(radioId: Uuid, maximum: Int): ChannelEntity {
        val rows = container.database.channelDao().listByRadio(radioId)
        val candidates = rows.filter { it.name == CHANNEL }
        assertTrue("Exactly one Test channel exists", candidates.size == 1)
        val row = candidates.single()
        assertTrue("Selected Test slot has one occupant", rows.count { it.index == row.index } == 1)
        assertTrue("Test channel is enabled and nonpublic", eligibleSlot(row.name, row.index, maximum, row.isEnabled))
        return row
    }

    private fun eligibleSlot(name: String, slot: Int, maximum: Int, enabled: Boolean): Boolean =
        name == CHANNEL && enabled && slot > ChannelSecret.PUBLIC_CHANNEL_SLOT && slot < maximum

    private fun eligibleSecret(secret: ByteArray): Boolean = ChannelSecret.validateSecret(secret) &&
        secret.any { it.toInt() != 0 } &&
        !secret.contentEquals(ChannelSecret.publicChannelSecret()) &&
        PUBLIC_DERIVATIONS.none { secret.contentEquals(ChannelSecret.hashSecret(it)) }

    private fun verifyControls(): Int {
        var count = 0
        for (
        secret in listOf(
            ByteArray(0),
            ByteArray(15),
            ByteArray(17),
            ByteArray(ChannelSecret.SIZE_BYTES),
            ChannelSecret.publicChannelSecret(),
        ) + PUBLIC_DERIVATIONS.map(ChannelSecret::hashSecret)
        ) {
            assertTrue("Ineligible synthetic secret is refused", !eligibleSecret(secret))
            count++
        }
        assertTrue(
            "Eligible synthetic private-shaped secret is accepted",
            eligibleSecret(
                ByteArray(16) {
                    (it + 1).toByte()
                },
            ),
        )
        count++
        for ((name, slot, maximum, enabled) in listOf(
            SlotControl("Public", 0, 8, true),
            SlotControl(CHANNEL, 0, 8, true),
            SlotControl(CHANNEL, 1, 8, false),
            SlotControl(CHANNEL, 8, 8, true),
            SlotControl("#Test", 1, 8, true),
            SlotControl(CHANNEL, -1, 8, true),
        )) {
            assertTrue("Ineligible synthetic slot is refused", !eligibleSlot(name, slot, maximum, enabled))
            count++
        }
        assertTrue("Eligible synthetic slot is accepted", eligibleSlot(CHANNEL, 1, 8, true))
        return count + 1
    }

    private suspend fun assertEmpty() {
        val outgoing = container.database.messageDao().listAll().count {
            it.directionRawValue ==
                MessageDirection.OUTGOING.raw
        }
        assertEquals(0, outgoing)
        container.database.useConnection(isReadOnly = true) { connection ->
            for (query in listOf(
                "SELECT COUNT(*) FROM pending_send",
                "SELECT COUNT(*) FROM room_message WHERE isFromSelf = 1",
            )) {
                connection.usePrepared(query) {
                    assertTrue(it.step())
                    assertEquals(0L, it.getLong(0))
                    assertTrue(!it.step())
                }
            }
        }
    }

    private fun publish(checkpoints: AppBleColdCheckpoints, value: JSONObject) {
        val directory = File(application.filesDir, "ble-cold-${checkpoints.nonce}")
        check(directory.isDirectory)
        val target = File(directory, "private-qualified.json")
        val temporary = File(directory, "private-qualified.writing")
        check(!target.exists() && !temporary.exists())
        FileOutputStream(temporary).use {
            it.write(value.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class SlotControl(val name: String, val slot: Int, val maximum: Int, val enabled: Boolean)

    companion object {
        const val ARGUMENT = "mc1PrivateQualification"
        private const val CHANNEL = "Test"
        private val PUBLIC_DERIVATIONS = listOf("Test", "test", "#Test", "#test")
    }
}
