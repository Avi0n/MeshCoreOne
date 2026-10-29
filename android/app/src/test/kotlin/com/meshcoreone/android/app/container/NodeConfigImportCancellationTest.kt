// AndroidOnly: WP-210 Verify real import progress reaches the settings state holder on partial failure.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TelemetryModes
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.remote.NodeConfigContactSaver
import com.meshcoreone.android.core.services.remote.NodeConfigService
import com.meshcoreone.android.core.services.remote.NodeConfigSettingsPort
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigCodec
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigDocuments
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigMessages
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigProgress
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigSession
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigStateHolder
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeConfigImportCancellationTest {
    private val radioId = RadioId(UUID.randomUUID())
    private val contact = MeshCoreNodeConfig.ContactConfig(
        type = 1u, name = "Test", publicKey = "ab".repeat(32), flags = 0u,
        latitude = "0", longitude = "0", lastAdvert = 0u, lastModified = 0u,
    )
    private val config = MeshCoreNodeConfig(contacts = SnapshotList.of(contact))

    private class Radio : MeshCoreSessionProtocol by MeshCoreSession(MockTransport()) {
        val contacts = mutableListOf<MeshContact>()
        override suspend fun getContacts(since: Instant?): List<MeshContact> = contacts.toList()
        override suspend fun addContact(contact: MeshContact) { contacts += contact }
    }

    private val settings = object : NodeConfigSettingsPort {
        override suspend fun getSelfInfo() = SelfInfo(
            1u, 22, 30, Bytes(ByteArray(32) { 0x5a }), 0.0, 0.0, 0u, 0u, 0u, 0u, 0u, false,
            915.0, 125.0, 7u, 5u, "Radio",
        )
        override suspend fun queryDevice() = DeviceCapabilities(10u, 100, 8, 0u, "build", "model", "version")
        override suspend fun exportPrivateKey(): Bytes = error("not used")
        override suspend fun importPrivateKey(key: Bytes): Unit = error("not used")
        override suspend fun setNodeName(name: String): Unit = error("not used")
        override suspend fun setLocation(latitude: Double, longitude: Double): Unit = error("not used")
        override suspend fun setRadioParams(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte): Unit = error("not used")
        override suspend fun setTxPower(power: Byte): Unit = error("not used")
        override suspend fun setOtherParams(autoAddContacts: Boolean, telemetryModes: TelemetryModes, advertLocationPolicyRaw: UByte, multiAcks: UByte): Unit = error("not used")
    }

    private val messages = object : NodeConfigMessages {
        override fun error(failure: Throwable) = failure.javaClass.simpleName
        override fun cancelled(partial: Boolean) = "cancelled:$partial"
        override fun failed(failure: Throwable, partial: Boolean) = "failed:$partial:${error(failure)}"
    }
    private val documents = object : NodeConfigDocuments {
        override suspend fun pick() = byteArrayOf(1)
        override suspend fun save(name: String, bytes: ByteArray) = error("not used")
    }
    private val codec = object : NodeConfigCodec {
        override fun decode(bytes: ByteArray) = config
        override fun encode(config: MeshCoreNodeConfig): ByteArray = error("not used")
    }

    @Test
    fun cancellationAfterRadioAddReportsPartialImport() = runTest {
        val radio = Radio()
        val saveStarted = CompletableDeferred<Unit>()
        var saveCancelled = false
        val service = NodeConfigService(
            radio, settings, { _, _, _, _ -> error("not used") },
            NodeConfigContactSaver { _, _ ->
                saveStarted.complete(Unit)
                try { awaitCancellation() } finally { saveCancelled = true }
            },
            null,
        )
        val adapter = adapter(service)
        val holder = NodeConfigStateHolder(backgroundScope, { adapter }, codec, messages, StandardTestDispatcher(testScheduler))
        holder.pick(documents); runCurrent()
        holder.prepareConfirmation(); runCurrent()
        assertTrue(holder.importState.value.confirmation)
        holder.apply(); runCurrent()
        assertTrue(saveStarted.isCompleted)
        assertEquals(1, radio.contacts.size)
        assertEquals(1, holder.importState.value.progress?.current)

        holder.cancelImport(); runCurrent()

        assertTrue(saveCancelled)
        assertEquals("cancelled:true", holder.importState.value.error)
        assertFalse(holder.importState.value.complete)
        assertFalse(holder.importState.value.applying)
        assertEquals(1, radio.contacts.size)
    }

    @Test
    fun failedLocalSaveReportsPartialImport() = runTest {
        val radio = Radio()
        val service = NodeConfigService(
            radio, settings, { _, _, _, _ -> error("not used") },
            NodeConfigContactSaver { _, _ -> throw IllegalStateException("save failed") },
            null,
        )
        val adapter = adapter(service)
        val holder = NodeConfigStateHolder(backgroundScope, { adapter }, codec, messages, StandardTestDispatcher(testScheduler))
        holder.pick(documents); runCurrent()
        holder.prepareConfirmation(); runCurrent()
        holder.apply(); runCurrent()

        assertEquals(1, radio.contacts.size)
        assertEquals("failed:true:NodeConfigContactSaveFailure", holder.importState.value.error)
        assertFalse(holder.importState.value.complete)
        assertFalse(holder.importState.value.applying)
    }

    private fun adapter(service: NodeConfigService) = object : NodeConfigSession {
        override val deviceName = "Radio"
        override fun requireCurrent() = Unit
        override suspend fun export(sections: ConfigSections): MeshCoreNodeConfig = error("not used")
        override suspend fun current() = MeshCoreNodeConfig()
        override suspend fun preview(config: MeshCoreNodeConfig, sections: ConfigSections) =
            service.previewImport(config, sections).channelsOverwriteExisting
        override suspend fun apply(config: MeshCoreNodeConfig, sections: ConfigSections, progress: (NodeConfigProgress) -> Unit) =
            service.importConfig(config, sections, radioId) { progress(NodeConfigProgress(it.current, it.total, "contact")) }
        override suspend fun refresh(): Unit = error("not used")
    }
}
