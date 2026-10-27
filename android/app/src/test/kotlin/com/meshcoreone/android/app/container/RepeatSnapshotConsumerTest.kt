// AndroidOnly: WP-201 Supplemental pinned repeat-snapshot consumer proof through real App settings, verified radio writes and Room.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.DiagnosticsPort
import com.meshcoreone.android.feature.settings.device.LocationAuthorization
import com.meshcoreone.android.feature.settings.device.LocationPermissionPort
import com.meshcoreone.android.feature.settings.device.NotificationPermissionPort
import com.meshcoreone.android.feature.settings.device.RadioPresetStateHolder
import com.meshcoreone.android.feature.settings.device.RadioWriteGate
import com.meshcoreone.android.feature.settings.device.SettingsEnvironment
import com.meshcoreone.android.feature.settings.device.SettingsNavigator
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class RepeatSnapshotConsumerTest : RoomProcessTest() {
    @Test
    @OriginalCase("DeviceDTOClientRepeatTests::hasPreRepeatSettings requires all four fields()", "native-equivalent")
    fun partialSnapshotRestoresSavedFrequencyWithCurrentCompanionsThroughRealSettingsAndRoom() = runTest {
        restoreSnapshot(SavedSettings(frequency = 915_000u), RadioParameters(915_000u, 250_000u, 10u, 5u),
            "0b38f60d0090d003000a0500")
    }

    @Test
    @OriginalCase("DeviceDTOClientRepeatTests::hasPreRepeatSettings requires all four fields()", "native-equivalent")
    fun completeSnapshotRestoresAllSavedFieldsThroughRealSettingsAndRoom() = runTest {
        restoreSnapshot(SavedSettings(868_000u, 125_000u, 9u, 6u), RadioParameters(868_000u, 125_000u, 9u, 6u),
            "0ba03e0d0048e80100090600", expectedComplete = true)
    }

    @Test
    @OriginalCase("DeviceDTOClientRepeatTests::hasPreRepeatSettings returns false when all nil()", "native-equivalent")
    fun absentSnapshotRestoresCurrentFieldsThroughRealSettingsAndRoom() = runTest {
        restoreSnapshot(SavedSettings(), RadioParameters(910_525u, 250_000u, 10u, 5u),
            "0bbde40d0090d003000a0500")
    }

    @Test
    fun failedRepeatVerificationKeepsPartialSnapshotAndReportsErrorWithoutClaimingRollback() = runTest {
        restoreSnapshot(SavedSettings(frequency = 915_000u), RadioParameters(915_000u, 250_000u, 10u, 5u),
            "0b38f60d0090d003000a0500", failVerification = true)
    }

    private suspend fun TestScope.restoreSnapshot(
        saved: SavedSettings,
        expected: RadioParameters,
        expectedWireHex: String,
        failVerification: Boolean = false,
        expectedComplete: Boolean = false,
    ) {
        val h = ContainerHarness(this, store)
        val firmware = RepeatFirmware(failVerification)
        h.links.configureRadio = { it.respond = firmware::respond }
        h.container.foreground.activityStarted()
        val id = UUID.fromString("20100000-0000-0000-0000-000000000009")
        val radioId = RadioId(UUID.fromString("20100000-0000-0000-0000-000000000109"))
        val seed = DeviceDTO(
            id = id, radioId = radioId, publicKey = key(7), nodeName = "Repeat snapshot",
            firmwareVersion = 9u, frequency = 910_525u, bandwidth = 250_000u, spreadingFactor = 10u, codingRate = 5u,
            clientRepeat = true, preRepeatFrequency = saved.frequency, preRepeatBandwidth = saved.bandwidth,
            preRepeatSpreadingFactor = saved.sf, preRepeatCodingRate = saved.cr,
            defaultFloodScopeName = "keep-scope", knownRegions = listOf("US", "PT").snapshot(),
        )
        val other = seed.copy(
            id = UUID.fromString("20100000-0000-0000-0000-000000000209"),
            radioId = RadioId(UUID.fromString("20100000-0000-0000-0000-000000000309")),
            publicKey = key(8), nodeName = "Other radio",
        )
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        var holder: RadioPresetStateHolder? = null
        try {
            store.saveDevice(seed)
            store.saveDevice(other)
            h.manager.connect(h.target(id))
            h.eventually("foreground sync completed") {
                h.sessions.current?.syncCoordinator?.syncState?.value == SyncState.Synced &&
                    h.appState.canRunSettingsStartupReads
            }
            h.assertReady()
            val radio = h.links.radios.single()
            assertEquals((0..7).toList(), radio.sent.filter { it[0] == 0x1f.toUByte() }.map { it[1].toInt() })
            assertTrue(firmware.writes.isEmpty())
            val before = assertNotNull(store.fetchDevice(id))
            val rawBefore = assertNotNull(db.devices().byId(id))
            val otherBefore = assertNotNull(db.devices().byId(other.id))
            assertEquals(id, before.id)
            assertEquals(radioId, before.radioId)
            assertEquals(key(7), before.publicKey)
            assertEquals(saved.frequency, before.preRepeatFrequency)
            assertEquals(saved.bandwidth, before.preRepeatBandwidth)
            assertEquals(saved.sf, before.preRepeatSpreadingFactor)
            assertEquals(saved.cr, before.preRepeatCodingRate)
            assertEquals(expectedComplete, before.hasPreRepeatSettings)
            repeat(2) {
                assertEquals(before, store.fetchDevice(id))
                assertEquals(rawBefore, db.devices().byId(id))
            }
            store.saveContact(ContactDTO(
                radioId = radioId, publicKey = key(9), name = "Unrelated contact", lastHeardTimestamp = 123u,
                nickname = "Keep nickname", isFavorite = true,
            ))
            val contactsBefore = db.contacts().forRadio(radioId.value)
            assertEquals(1, contactsBefore.size)

            val feature = AppSettingsFeature.create(h.container, storage)
            val deps = feature.dependencies(
                notifications = object : NotificationPermissionPort {
                    override suspend fun status() = error("No notification request in repeat restore")
                    override suspend fun request() = error("No notification request in repeat restore")
                },
                location = object : LocationPermissionPort {
                    override val authorization = MutableStateFlow(LocationAuthorization.DENIED)
                    override val isRequestingLocation = MutableStateFlow(false)
                    override fun requestPermissionIfNeeded() = error("No location request in repeat restore")
                    override suspend fun requestCurrentLocation() = error("No location request in repeat restore")
                },
                navigator = SettingsNavigator { error("No navigation in repeat restore") },
                diagnostics = object : DiagnosticsPort {
                    override suspend fun createExportFile() = error("No log export in repeat restore")
                    override suspend fun clearDebugLogs() = error("No log deletion in repeat restore")
                },
            )
            val described = mutableListOf<Throwable>()
            val gate = RadioWriteGate()
            val preset = RadioPresetStateHolder(
                SettingsEnvironment(backgroundScope, describe = { described += it; UiText.Verbatim("Verification error") }),
                deps.connection, deps.regions, deps.radioCatalog, deps.settingsService, deps.deviceStore, gate,
            )
            holder = preset
            preset.start()
            h.eventually("production settings observed the persisted snapshot") { preset.state.value.device == before }
            assertTrue(preset.state.value.isRepeatEnabled)
            val actionStart = radio.sent.size
            preset.onRepeatToggled(false)
            h.eventually("repeat restore finished") { firmware.writes.isNotEmpty() && !preset.state.value.isApplyingRepeat }
            assertEquals(listOf(Bytes.fromHex(expectedWireHex)), firmware.writes)
            assertEquals(expected, firmware.parameters)
            val actionCommands = radio.sent.drop(actionStart).map { it[0].toInt() }
            val writeIndex = actionCommands.indexOf(0x0b)
            assertTrue(writeIndex >= 0)
            assertTrue(actionCommands.drop(writeIndex + 1).contains(0x01), "RF must actually be read back")
            assertTrue(actionCommands.drop(writeIndex + 1).contains(0x16), "Repeat mode must actually be read back")
            assertFalse(gate.inFlight.value)

            if (failVerification) {
                val error = assertIs<SettingsServiceException>(described.single())
                assertEquals(SettingsServiceError.VerificationFailed("clientRepeat=false", "clientRepeat=true"), error.error)
                assertEquals(UiText.Verbatim("Verification error"), preset.state.value.errorMessage)
                assertTrue(preset.state.value.isRepeatEnabled)
                assertFalse(preset.retryAlertState.value.isPresented)
                assertEquals(before, h.manager.connectedDevice)
                assertEquals(rawBefore, db.devices().byId(id))
                assertEquals(before, store.fetchDevice(id))
                assertTrue(firmware.clientRepeat)
            } else {
                val expectedRow = rawBefore.copy(
                    frequency = expected.frequency.toLong(), bandwidth = expected.bandwidth.toLong(),
                    spreadingFactor = expected.sf.toLong(), codingRate = expected.cr.toLong(), clientRepeat = false,
                    preRepeatFrequency = null, preRepeatBandwidth = null,
                    preRepeatSpreadingFactor = null, preRepeatCodingRate = null,
                )
                withTimeout(5_000) {
                    while (db.devices().byId(id) != expectedRow) delay(1)
                }
                assertTrue(described.isEmpty())
                assertFalse(preset.state.value.isRepeatEnabled)
                assertNull(preset.state.value.errorMessage)
                assertFalse(firmware.clientRepeat)
                val restored = assertNotNull(store.fetchDevice(id))
                assertEquals(before.copy(
                    frequency = expected.frequency, bandwidth = expected.bandwidth,
                    spreadingFactor = expected.sf, codingRate = expected.cr, clientRepeat = false,
                    preRepeatFrequency = null, preRepeatBandwidth = null,
                    preRepeatSpreadingFactor = null, preRepeatCodingRate = null,
                ), restored)
                assertEquals(restored, h.manager.connectedDevice)
                assertFalse(restored.hasPreRepeatSettings)
                repeat(2) { assertEquals(expectedRow, db.devices().byId(id)) }
            }
            assertEquals(otherBefore, db.devices().byId(other.id))
            assertEquals(contactsBefore, db.contacts().forRadio(radioId.value))
            assertNull(feature.failure.value)
        } finally {
            holder?.stop()
            h.container.close()
            storage.close()
        }
    }

    private data class SavedSettings(
        val frequency: UInt? = null,
        val bandwidth: UInt? = null,
        val sf: UByte? = null,
        val cr: UByte? = null,
    )

    private data class RadioParameters(val frequency: UInt, val bandwidth: UInt, val sf: UByte, val cr: UByte)

    private class RepeatFirmware(private val failVerification: Boolean) {
        var parameters = RadioParameters(910_525u, 250_000u, 10u, 5u)
            private set
        var clientRepeat = true
            private set
        val writes = mutableListOf<Bytes>()

        fun respond(command: Bytes): List<Bytes>? = when (command[0].toInt()) {
            0x1f -> listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
            0x01 -> listOf(
                Bytes.of(5, 1, 22, 22) + key(7) + le32(0) + le32(0) + Bytes.of(0, 0, 2, 0) +
                    le32(parameters.frequency.toLong()) + le32(parameters.bandwidth.toLong()) +
                    Bytes.of(parameters.sf.toInt(), parameters.cr.toInt()) + Bytes.utf8("Repeat snapshot"),
            )
            0x16 -> listOf(
                Bytes.of(13, 9, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                    Bytes.utf8("T-Deck").paddedOrTruncated(40) + Bytes.utf8("v1.13.0").paddedOrTruncated(20) +
                    Bytes.of(if (clientRepeat) 1 else 0),
            )
            0x0b -> {
                assertEquals(12, command.size)
                writes += command
                parameters = RadioParameters(command.readUInt32LE(1), command.readUInt32LE(5), command[9], command[10])
                clientRepeat = failVerification || command[11] != 0.toUByte()
                listOf(Bytes.of(0))
            }
            else -> null
        }
    }
}
