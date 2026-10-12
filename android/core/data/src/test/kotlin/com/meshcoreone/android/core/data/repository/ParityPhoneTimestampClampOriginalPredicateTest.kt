// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.data.backup.envelope
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ParityPhoneTimestampClampOriginalPredicateTest : RepositoryTest() {
    @OriginalCase("PersistenceStoreTests::phone clock clamp agrees between touch and backup helpers()")
    @Test fun originalCurrentUnixUIntWrappingThirtyDaysAndSecondNowMatchRealBackupRestoreAndClampBelowFuture() = runTest {
        val farFuture = Instant.now().epochSecond.toUInt() + (60u * 60u * 24u * 30u)
        val now = Instant.now()
        clock.now = now
        val fromStatic = RoomPersistenceStore.clampedPhoneClockTimestamp(farFuture, now)
        val radio = RadioId(UUID.randomUUID())
        val device = DeviceDTO(
            id = UUID.randomUUID(), radioId = radio, publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "ClampRadio",
        )
        val contact = ContactDTO(
            id = UUID.randomUUID(), radioId = radio, publicKey = Bytes(ByteArray(32) { 0x55 }),
            name = "ClampContact", typeRawValue = 1u, lastHeardTimestamp = farFuture,
        )
        val storage = MeshCoreStorage.get(context)
        try {
            val service = AppBackupService(storage.backupPreferences, "1.0.0", "42", clock)
            service.importBackup(envelope(
                devices = listOf(device), contacts = listOf(contact), exportDate = now,
            ), store)
            val restored = assertNotNull(store.fetchContact(EntityKey(radio, contact.id)))
            val fromBackup = restored.lastHeardTimestamp

            assertEquals(fromStatic, fromBackup)
            assertTrue(fromStatic < farFuture)
            assertEquals(now.epochSecond.toUInt() + 300u, fromStatic)
            assertEquals(contact.id, restored.id)
            assertEquals(radio, restored.radioId)
            assertEquals(farFuture, contact.lastHeardTimestamp)
        } finally {
            storage.close()
        }
    }
}
