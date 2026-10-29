// AndroidOnly: WP-210 End-to-end NodeConfigService export/preview/import over fake session, settings, channel and contact ports.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ChannelConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ContactConfig
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.TestFactory

class NodeConfigServiceFlowTest {
    private val selfKey = nodeConfigBytes(0x5A, 32)
    private val secretA = Bytes.fromHex("00112233445566778899aabbccddeeff")

    private class Harness(
        val settings: NodeConfigFakeSettings,
        val session: NodeConfigFakeSession = NodeConfigFakeSession(),
        saveFailure: Exception? = null,
        cancelCallerOnSave: Boolean = false,
        saveFailureAfter: Int = 0,
        notificationFailure: Exception? = null,
    ) {
        private val lock = Any()
        private val channelWrites = mutableListOf<Pair<RadioId, String>>()
        private val saved = mutableListOf<Pair<RadioId, ContactFrame>>()
        var notifications = 0
        val writes get() = synchronized(lock) { channelWrites.toList() }
        val savedFrames get() = synchronized(lock) { saved.toList() }
        val service = NodeConfigService(
            session, settings,
            { radioId, index, name, _ -> synchronized(lock) { channelWrites += radioId to "$index:$name" } },
            NodeConfigContactSaver { radioId, frame ->
                if (synchronized(lock) { saved.size >= saveFailureAfter }) {
                    if (cancelCallerOnSave) kotlinx.coroutines.currentCoroutineContext().cancel()
                    saveFailure?.let { throw it }
                }
                synchronized(lock) { saved += radioId to frame }
            },
            { notifications++; notificationFailure?.let { throw it } },
            NodeConfigLogger.NONE,
        )
    }

    private fun deviceInfo() = nodeConfigSelfInfo(
        txPower = 22, maxTxPower = 30, publicKey = selfKey, latitude = 47.6062, longitude = -122.3321,
        advertisementLocationPolicy = 1u, manualAddContacts = true, name = "Device",
    )

    private val remote = nodeConfigMeshContact(
        publicKey = nodeConfigBytes(0x01, 32), flags = 2u, outPathLength = 2u, outPath = Bytes.of(0xAA, 0xBB, 0xCC),
        advertisedName = "Remote", lastAdvertisement = Instant.ofEpochSecond(1_700_000_000), latitude = 0.00001,
        longitude = -120.36, lastModified = Instant.ofEpochSecond(1_700_000_100),
    )

    private fun contactConfig(hex: String, name: String) = ContactConfig(
        type = 1u, name = name, publicKey = hex, flags = 0u, latitude = "0", longitude = "0", lastAdvert = 0u, lastModified = 0u,
    )

    @TestFactory
    fun nativeCases() = nodeConfigNativeCases(
        "exportConfig emits every selected section with Swift-formatted coordinates and only configured channels" to {
            val harness = Harness(NodeConfigFakeSettings(deviceInfo()), NodeConfigFakeSession(
                contacts = listOf(remote),
                channels = listOf(ChannelInfo(0u, "General", secretA), ChannelInfo(2u, "", Bytes(ByteArray(16))), ChannelInfo(3u, "", secretA)),
            ))
            val config = harness.service.exportConfig(nodeConfigAllSections)
            assertEquals("Device", config.name)
            assertEquals(selfKey.hexString, config.publicKey)
            assertEquals("11".repeat(64), config.privateKey)
            assertEquals(MeshCoreNodeConfig.PositionSettings("47.6062", "-122.3321"), config.positionSettings)
            assertEquals(MeshCoreNodeConfig.OtherSettings(manualAddContacts = 1u, advertLocationPolicy = 1u), config.otherSettings)
            assertEquals(MeshCoreNodeConfig.RadioSettings(910_525u, 62_500u, 7u, 5u, 22), config.radioSettings)
            assertEquals(listOf("General" to secretA.hexString, "" to secretA.hexString), config.channels?.map { it.name to it.secret })
            val exported = assertNotNull(config.contacts).single()
            assertEquals("1e-05", exported.latitude)
            assertEquals("aabb", exported.outPath)
            assertEquals(8, harness.session.channelReads)
        },
        "exportConfig omits only the private key when firmware disables key export" to {
            val settings = NodeConfigFakeSettings(deviceInfo(), privateKey = { throw IllegalStateException("wrapped", MeshCoreException.FeatureDisabled()) })
            val config = Harness(settings).service.exportConfig(ConfigSections(nodeIdentity = true))
            assertEquals("Device", config.name)
            assertNull(config.privateKey)
            val failing = NodeConfigFakeSettings(deviceInfo(), privateKey = { throw MeshCoreException.Timeout() })
            assertFailsWith<MeshCoreException.Timeout> { Harness(failing).service.exportConfig(ConfigSections(nodeIdentity = true)) }
        },
        "previewImport flags channel overwrites and performs no writes" to {
            val harness = Harness(NodeConfigFakeSettings(deviceInfo()), NodeConfigFakeSession(channels = listOf(ChannelInfo(0u, "#old", secretA))))
            val config = MeshCoreNodeConfig(channels = SnapshotList.of(ChannelConfig("#old", "ffeeddccbbaa99887766554433221100")))
            assertTrue(harness.service.previewImport(config, ConfigSections(channels = true)).channelsOverwriteExisting)
            assertTrue(harness.writes.isEmpty())
            assertEquals(listOf("queryDevice"), harness.settings.calls)
        },
        "importConfig excludes the ZephCore V-contact from occupancy and never adds it" to {
            val vKey = assertNotNull(VContactIdentity.publicKey(selfKey))
            val existing = nodeConfigMeshContact(publicKey = nodeConfigBytes(0x07, 32), advertisedName = "Existing")
            val vContact = nodeConfigMeshContact(publicKey = vKey, advertisedName = "V")
            val harness = Harness(
                NodeConfigFakeSettings(deviceInfo(), capabilities = com.meshcoreone.android.core.protocol.model.DeviceCapabilities(10u, 2, 8, 0u, "b", "m", "v")),
                NodeConfigFakeSession(contacts = listOf(existing, vContact)),
            )
            val config = MeshCoreNodeConfig(contacts = SnapshotList.of(contactConfig(vKey.hexString.uppercase(), "V"), contactConfig("cd".repeat(32), "New")))
            val radioId = RadioId(UUID.randomUUID())
            val progress = mutableListOf<ImportProgress>()
            harness.service.importConfig(config, ConfigSections(contacts = true), radioId) { progress += it }
            assertEquals(listOf("New"), harness.session.addedContacts.map { it.advertisedName })
            assertEquals(listOf(radioId), harness.savedFrames.map { it.first })
            assertEquals("New", harness.savedFrames.single().second.name)
            assertEquals(listOf(ImportProgress(ImportStep.Contact("New"), 1, 1)), progress)
            assertEquals(1, harness.notifications)
        },
        "a private-key import reassigns the radio id used by channel and contact writes" to {
            val harness = Harness(NodeConfigFakeSettings(deviceInfo()))
            val reconciled = RadioId(UUID.randomUUID())
            harness.service.setOnPostIdentityImport { reconciled }
            val config = MeshCoreNodeConfig(
                name = "Device", privateKey = "ab".repeat(64),
                channels = SnapshotList.of(ChannelConfig("General", secretA.hexString)), contacts = SnapshotList.of(contactConfig("cd".repeat(32), "C")),
            )
            harness.service.importConfig(config, ConfigSections(nodeIdentity = true, channels = true, contacts = true), RadioId(UUID.randomUUID()))
            assertEquals(listOf(reconciled to "0:General"), harness.writes)
            assertEquals(listOf(reconciled), harness.savedFrames.map { it.first })
            assertTrue("importPrivateKey:64" in harness.settings.calls)
            assertFalse(harness.settings.calls.any { it.startsWith("setNodeName") }, "An unchanged name is not rewritten")
        },
        "a local contact-save failure stops after reporting the device add; caller cancellation propagates" to {
            val failing = Harness(NodeConfigFakeSettings(deviceInfo()), saveFailure = IllegalStateException("db"))
            val config = MeshCoreNodeConfig(contacts = SnapshotList.of(contactConfig("cd".repeat(32), "C")))
            val failedProgress = mutableListOf<ImportProgress>()
            val failure = assertFailsWith<NodeConfigContactSaveFailure> {
                failing.service.importConfig(config, ConfigSections(contacts = true), RadioId(UUID.randomUUID())) {
                    failedProgress += it
                }
            }
            assertIs<IllegalStateException>(failure.cause)
            assertEquals(1, failing.session.addedContacts.size)
            assertEquals(listOf(ImportProgress(ImportStep.Contact("C"), 1, 1)), failedProgress)
            assertEquals(0, failing.notifications)
            // A retry first reads the radio's acknowledged contact and must not add it again.
            failing.session.contacts = failing.session.addedContacts
            failing.service.importConfig(config, ConfigSections(contacts = true), RadioId(UUID.randomUUID()))
            assertEquals(1, failing.session.addedContacts.size)
            val timedOut = Harness(NodeConfigFakeSettings(deviceInfo()), saveFailure = CancellationException("store timeout"))
            val timeout = assertFailsWith<NodeConfigContactSaveFailure> {
                timedOut.service.importConfig(config, ConfigSections(contacts = true), RadioId(UUID.randomUUID()))
            }
            assertIs<CancellationException>(timeout.cause)
            assertEquals(1, timedOut.session.addedContacts.size)
            val cancelled = Harness(
                NodeConfigFakeSettings(deviceInfo()), saveFailure = CancellationException("stop"), cancelCallerOnSave = true,
            )
            val progress = mutableListOf<ImportProgress>()
            val importing = kotlinx.coroutines.coroutineScope {
                async {
                    cancelled.service.importConfig(config, ConfigSections(contacts = true), RadioId(UUID.randomUUID())) {
                        progress += it
                    }
                }
            }
            assertFailsWith<CancellationException> { importing.await() }
            assertEquals(listOf("C"), cancelled.session.addedContacts.map { it.advertisedName })
            assertEquals(listOf(ImportProgress(ImportStep.Contact("C"), 1, 1)), progress)
            assertTrue(cancelled.savedFrames.isEmpty())
        },
        "a failed local save stops before a second contact is added" to {
            val harness = Harness(NodeConfigFakeSettings(deviceInfo()), saveFailure = IllegalStateException("db"))
            val config = MeshCoreNodeConfig(
                contacts = SnapshotList.of(contactConfig("cd".repeat(32), "First"), contactConfig("ef".repeat(32), "Second")),
            )
            val progress = mutableListOf<ImportProgress>()
            assertFailsWith<NodeConfigContactSaveFailure> {
                harness.service.importConfig(config, ConfigSections(contacts = true), RadioId(UUID.randomUUID())) {
                    progress += it
                }
            }
            assertEquals(listOf("First"), harness.session.addedContacts.map { it.advertisedName })
            assertEquals(listOf(ImportProgress(ImportStep.Contact("First"), 1, 2)), progress)
        },
        "a later failed save notifies already committed contacts and preserves the failure" to {
            val harness = Harness(NodeConfigFakeSettings(deviceInfo()), saveFailure = IllegalStateException("db"), saveFailureAfter = 1)
            val config = MeshCoreNodeConfig(
                contacts = SnapshotList.of(contactConfig("cd".repeat(32), "First"), contactConfig("ef".repeat(32), "Second")),
            )
            val failure = assertFailsWith<NodeConfigContactSaveFailure> {
                harness.service.importConfig(config, ConfigSections(contacts = true), RadioId(UUID.randomUUID()))
            }
            assertIs<IllegalStateException>(failure.cause)
            assertEquals(listOf("First"), harness.savedFrames.map { it.second.name })
            assertEquals(listOf("First", "Second"), harness.session.addedContacts.map { it.advertisedName })
            assertEquals(1, harness.notifications)
        },
        "a notification failure is retained on the original partial import failure" to {
            val notificationError = IllegalStateException("notify")
            val harness = Harness(
                NodeConfigFakeSettings(deviceInfo()), saveFailure = IllegalStateException("db"),
                saveFailureAfter = 1, notificationFailure = notificationError,
            )
            val config = MeshCoreNodeConfig(
                contacts = SnapshotList.of(contactConfig("cd".repeat(32), "First"), contactConfig("ef".repeat(32), "Second")),
            )
            val failure = assertFailsWith<NodeConfigContactSaveFailure> {
                harness.service.importConfig(config, ConfigSections(contacts = true), RadioId(UUID.randomUUID()))
            }
            assertSame(notificationError, failure.suppressed.single())
            assertEquals(1, harness.notifications)
        },
        "caller cancellation after a saved contact still notifies it" to {
            val harness = Harness(
                NodeConfigFakeSettings(deviceInfo()), saveFailure = CancellationException("stop"),
                cancelCallerOnSave = true, saveFailureAfter = 1,
            )
            val config = MeshCoreNodeConfig(
                contacts = SnapshotList.of(contactConfig("cd".repeat(32), "First"), contactConfig("ef".repeat(32), "Second")),
            )
            val importing = kotlinx.coroutines.coroutineScope {
                async { harness.service.importConfig(config, ConfigSections(contacts = true), RadioId(UUID.randomUUID())) }
            }
            assertFailsWith<CancellationException> { importing.await() }
            assertEquals(listOf("First"), harness.savedFrames.map { it.second.name })
            assertEquals(1, harness.notifications)
        },
        "a malformed section rejects the whole import before any device write" to {
            val harness = Harness(NodeConfigFakeSettings(deviceInfo()))
            val config = MeshCoreNodeConfig(
                name = "Renamed", privateKey = "ab".repeat(64),
                radioSettings = MeshCoreNodeConfig.RadioSettings(910_525u, 62_500u, 7u, 5u, 31),
            )
            assertFailsWith<NodeConfigServiceError.InvalidRadioSettings> {
                harness.service.importConfig(config, ConfigSections(nodeIdentity = true, radioSettings = true), RadioId(UUID.randomUUID()))
            }
            assertEquals(listOf("queryDevice", "getSelfInfo"), harness.settings.calls)
        },
        "radio import writes Hz bandwidth verbatim, last, and other params merge into one packed write" to {
            val harness = Harness(NodeConfigFakeSettings(deviceInfo()))
            val config = MeshCoreNodeConfig(
                otherSettings = MeshCoreNodeConfig.OtherSettings(telemetryModeBase = 2u, telemetryModeEnvironment = 1u),
                radioSettings = MeshCoreNodeConfig.RadioSettings(869_525u, 250_000u, 11u, 8u, -9),
            )
            harness.service.importConfig(config, ConfigSections(otherSettings = true, radioSettings = true), RadioId(UUID.randomUUID()))
            val writes = harness.settings.calls.filter { it.startsWith("set") }
            assertEquals(listOf("setOtherParams:false,18,1,0", "setRadioParams:869525,250000,11,8", "setTxPower:-9"), writes)
        },
        "wiring reports whether a sync coordinator was injected" to {
            assertTrue(Harness(NodeConfigFakeSettings(deviceInfo())).service.hasSyncCoordinatorWired)
            val unwired = NodeConfigService(NodeConfigFakeSession(), NodeConfigFakeSettings(deviceInfo()), { _, _, _, _ -> }, NodeConfigContactSaver { _, _ -> }, null)
            assertFalse(unwired.hasSyncCoordinatorWired)
        },
    )
}
