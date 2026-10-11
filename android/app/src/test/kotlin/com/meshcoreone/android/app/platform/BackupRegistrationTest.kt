// AndroidOnly: WP-407 Native adapter receipt and preference-only retry over real Room/DataStore; not an iOS oracle.
package com.meshcoreone.android.app.platform

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.core.data.backup.*
import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.util.UUID

class BackupRegistrationTest : RoomProcessTest() {
    @Test fun cancellationAfterRowsCommitRetainsReceiptAndAppliesProcessEffectsOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        try {
            val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 8 }),
                name = "Cancellation synthetic peer", lastHeardTimestamp = null)
            val backup = AppBackupEnvelope(exportDate = Instant.ofEpochSecond(1_700_000_001), appVersion = "synthetic", appBuild = "1",
                contacts = listOf(contact).snapshot()).withActualManifest()
            var effects = 0
            val operation = Job()
            val engine = AppBackupFeature(AppBackupService(storage.backupPreferences, "synthetic", "1"), store) {
                effects++
                operation.cancel()
            }
            try { withContext(operation) { engine.importBackup(ParsedAppBackup(backup)) }; fail("Caller cancellation must surface") }
            catch (_: CancellationException) {}
            catch (expected: IllegalStateException) {
                assertTrue(generateSequence<Throwable>(expected) { it.cause }.any { it is CancellationException })
            }
            assertEquals(1, effects)
            assertEquals(1, store.fetchContacts(contact.radioId).size)
            assertEquals(1, requireNotNull(engine.committed.value).result.totalInserted)
            engine.acknowledge()
            assertNull(engine.committed.value)
        } finally { storage.close() }
    }

    @Test fun committedPreferenceFailureHasARetainedReceiptAndRetryDoesNotReimportRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.setUserUnlocked(true)
        val storage = MeshCoreStorage.get(context)
        try {
            storage.preferences.remove(AppStorageKey.replyWithQuote)
            val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 7 }),
                name = "Backup synthetic peer", lastHeardTimestamp = null)
            val backup = AppBackupEnvelope(exportDate = Instant.ofEpochSecond(1_700_000_000), appVersion = "synthetic", appBuild = "1",
                contacts = listOf(contact).snapshot(), userDefaults = BackupUserDefaults(replyWithQuote = true)).withActualManifest()
            val effects = mutableListOf<com.meshcoreone.android.feature.settings.app.backup.ImportResult>()
            val engine = AppBackupFeature(AppBackupService(storage.backupPreferences, "synthetic", "1"), store) { effects += it }
            users.setUserUnlocked(false)
            try { engine.importBackup(ParsedAppBackup(backup)); fail("Preference access must fail after rows commit") }
            catch (expected: CommittedBackupPreferenceException) {
                assertEquals(StorageProblem.LockedBeforeFirstUnlock, expected.storageFailure.problem)
            }
            assertEquals(contact.name, requireNotNull(store.fetchContact(com.meshcoreone.android.core.contracts.domain.EntityKey(contact.radioId, contact.id))).name)
            assertEquals(1, engine.committed.value!!.result.totalInserted)
            try { engine.acknowledge(); fail("Receipt must not discard pending preference completion") }
            catch (_: IllegalStateException) {}
            users.setUserUnlocked(true)
            engine.completePreferences()
            assertNull(engine.committed.value)
            assertTrue(storage.preferences.get(AppStorageKey.replyWithQuote))
            assertEquals(1, store.fetchContacts(contact.radioId).size)
            assertEquals(2, effects.size)
            assertTrue(effects.last().counts.isEmpty())
        } finally { users.setUserUnlocked(true); storage.close() }
    }
}
