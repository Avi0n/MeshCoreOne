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
import com.meshcoreone.android.feature.settings.app.backup.AppBackupStateHolder
import com.meshcoreone.android.feature.settings.app.backup.BackupConnectionGate
import com.meshcoreone.android.feature.settings.app.backup.BackupDiagnostics
import com.meshcoreone.android.feature.settings.app.backup.BackupDocumentPort
import com.meshcoreone.android.feature.settings.app.backup.BackupEngine
import com.meshcoreone.android.feature.settings.app.backup.BackupFeatureDependencies
import com.meshcoreone.android.feature.settings.app.backup.BackupImportEffects
import com.meshcoreone.android.feature.settings.app.backup.BackupPickOutcome
import com.meshcoreone.android.feature.settings.app.backup.BackupSaveOutcome
import com.meshcoreone.android.feature.settings.app.backup.BackupSource
import com.meshcoreone.android.feature.settings.app.backup.ImportHeroIcon
import com.meshcoreone.android.feature.settings.app.backup.ImportResultPresenter
import com.meshcoreone.android.feature.settings.app.backup.ImportState
import com.meshcoreone.android.feature.settings.app.backup.ParsedBackup
import com.meshcoreone.android.feature.settings.app.backup.ui.ResourceBackupStrings
import com.meshcoreone.android.feature.settings.app.backup.ImportResult as UiImport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.time.Instant
import java.util.UUID

class BackupRegistrationTest : RoomProcessTest() {
    @Test fun callerCancellationAfterCommitReachesUiSuccessAndAppliesProcessEffectsOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val h = ContainerHarness(this, store)
        try {
            storage.preferences.remove(AppStorageKey.replyWithQuote)
            val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 8 }),
                name = "Cancellation synthetic peer", lastHeardTimestamp = null)
            val channel = ChannelDTO(radioId = contact.radioId, index = 2u, name = "Restored channel")
            val backup = AppBackupEnvelope(exportDate = Instant.ofEpochSecond(1_700_000_001), appVersion = "synthetic", appBuild = "1",
                contacts = listOf(contact).snapshot(), channels = listOf(channel).snapshot(),
                userDefaults = BackupUserDefaults(replyWithQuote = true)).withActualManifest()
            val affectedDraft = ChatConversationID.channel(contact.radioId, channel.index)
            val otherDraft = ChatConversationID.channel(contact.radioId, 3u)
            h.draftStore.setDraft("Old occupant", affectedDraft)
            h.draftStore.setDraft("Keep this draft", otherDraft)
            val contactsBefore = h.appState.contactsVersion.value
            val conversationsBefore = h.appState.conversationsVersion.value
            var effects = 0
            lateinit var holder: AppBackupStateHolder
            val engine = AppBackupFeature(AppBackupService(storage.backupPreferences, "synthetic", "1"), store) {
                effects++
                assertEquals(1, store.fetchContacts(contact.radioId).size)
                assertTrue(storage.preferences.get(AppStorageKey.replyWithQuote))
                it.channelSlotsAffectedByImport.forEach { (radio, slots) ->
                    h.appState.handleChannelSlotOccupantChanged(RadioId(radio), slots.map { index -> index.toUByte() }.toSet())
                }
                if (it.hasRestoredChanges) {
                    h.appState.bumpContactsVersion()
                    h.appState.refreshConversations()
                    h.appState.notifyDataRestored()
                }
                holder.cancelImport()
            }
            val reports = mutableListOf<Throwable?>()
            val dependencies = uiDependencies(context, engine, reports)
            holder = AppBackupStateHolder(dependencies, this)
            holder.preview(backup)
            val operation = requireNotNull(holder.performImport())
            operation.join()
            assertTrue(operation.isCancelled)
            val success = holder.state.value.import as? ImportState.Success
                ?: throw AssertionError("A committed restore must reach the actual UI success state")
            assertEquals(2, success.result.totalInserted)
            assertTrue(success.result.settingsRestored)
            assertEquals(mapOf(contact.radioId.value to setOf(2)), success.result.channelSlotsAffectedByImport)
            val presentation = ImportResultPresenter.present(success.result, dependencies.strings)
            assertEquals(ImportHeroIcon.SUCCESS, presentation.heroIcon)
            assertEquals(dependencies.strings.importSuccessTitle(), presentation.heroTitle)
            assertEquals(1, effects)
            assertEquals(1, store.fetchContacts(contact.radioId).size)
            assertTrue(storage.preferences.get(AppStorageKey.replyWithQuote))
            assertNull(h.draftStore.draft(affectedDraft))
            assertEquals("Keep this draft", h.draftStore.draft(otherDraft))
            assertEquals(1, h.appState.channelSlotGenerations.value[affectedDraft])
            assertEquals(contactsBefore + 2, h.appState.contactsVersion.value)
            assertEquals(conversationsBefore + 3, h.appState.conversationsVersion.value)
            assertNull(engine.committed.value)
            assertNull(holder.state.value.errorMessage)
            assertTrue(reports.isEmpty())
        } finally { h.container.close(); storage.close() }
    }

    @Test fun cancellationFromLaterUiEffectsPreservesCommittedSuccessAndStillPropagates() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        try {
            val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 9 }),
                name = "Later cancellation synthetic peer", lastHeardTimestamp = null)
            val backup = AppBackupEnvelope(exportDate = Instant.ofEpochSecond(1_700_000_002), appVersion = "synthetic", appBuild = "1",
                contacts = listOf(contact).snapshot()).withActualManifest()
            var processEffects = 0
            var uiChanges = 0
            var refreshes = 0
            lateinit var holder: AppBackupStateHolder
            val engine = AppBackupFeature(AppBackupService(storage.backupPreferences, "synthetic", "1"), store) { processEffects++ }
            val effects = object : BackupImportEffects {
                override fun restoredDataChanged() { uiChanges++ }
                override fun channelSlotsAffected(slotsByRadio: Map<UUID, Set<Int>>) = Unit
                override suspend fun refreshBlockedContactsCache() {
                    refreshes++
                    holder.cancelImport()
                    currentCoroutineContext().ensureActive()
                }
            }
            val reports = mutableListOf<Throwable?>()
            holder = AppBackupStateHolder(uiDependencies(context, engine, reports, effects), this)
            holder.preview(backup)
            val operation = requireNotNull(holder.performImport())
            operation.join()
            assertTrue(operation.isCancelled)
            assertEquals(1, (holder.state.value.import as ImportState.Success).result.totalInserted)
            assertEquals(1, processEffects)
            assertEquals(1, uiChanges)
            assertEquals(1, refreshes)
            assertEquals(1, store.fetchContacts(contact.radioId).size)
            assertNull(engine.committed.value)
            assertTrue(reports.isEmpty())
        } finally { storage.close() }
    }

    @Test fun cancellationBeforeRealServiceCommitIsRethrownAndUiReportsCancelledWithoutRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        try {
            storage.preferences.remove(AppStorageKey.replyWithQuote)
            val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 10 }),
                name = "Uncommitted synthetic peer", lastHeardTimestamp = null)
            val backup = AppBackupEnvelope(exportDate = Instant.ofEpochSecond(1_700_000_003), appVersion = "synthetic", appBuild = "1",
                contacts = listOf(contact).snapshot(), userDefaults = BackupUserDefaults(replyWithQuote = true)).withActualManifest()
            var effects = 0
            val engine = AppBackupFeature(AppBackupService(storage.backupPreferences, "synthetic", "1"), store) { effects++ }
            lateinit var holder: AppBackupStateHolder
            val cancelledCaller = object : BackupEngine by engine {
                override suspend fun importBackup(backup: ParsedBackup): UiImport {
                    holder.cancelImport()
                    return engine.importBackup(backup)
                }
            }
            val reports = mutableListOf<Throwable?>()
            holder = AppBackupStateHolder(uiDependencies(context, cancelledCaller, reports), this)
            holder.preview(backup)
            val operation = requireNotNull(holder.performImport())
            operation.join()
            assertTrue(operation.isCancelled)
            assertEquals(ImportState.Cancelled, holder.state.value.import)
            assertTrue(store.fetchContacts(contact.radioId).isEmpty())
            assertFalse(storage.preferences.snapshot().contains(AppStorageKey.replyWithQuote))
            assertEquals(0, effects)
            assertNull(engine.committed.value)
            assertTrue(reports.isEmpty())
        } finally { storage.close() }
    }

    @Test fun realPostCommitRefreshFailuresRetainReceiptWithoutRetryingPartialEffects() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        try {
            for (cause in listOf(IOException("Refresh failed"), CancellationException("Refresh cancelled"))) {
                val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 11 }),
                    name = "Refresh failure synthetic peer", lastHeardTimestamp = null)
                val backup = AppBackupEnvelope(exportDate = Instant.ofEpochSecond(1_700_000_004), appVersion = "synthetic", appBuild = "1",
                    contacts = listOf(contact).snapshot()).withActualManifest()
                var effects = 0
                val engine = AppBackupFeature(AppBackupService(storage.backupPreferences, "synthetic", "1"), store) {
                    effects++
                    throw cause
                }
                val failure = try {
                    engine.importBackup(ParsedAppBackup(backup))
                    throw AssertionError("A real refresh failure must stay explicit")
                } catch (expected: IllegalStateException) { expected }
                // Coroutine stack-trace recovery can copy the exception while retaining the original cause.
                val refreshFailure = requireNotNull(failure.cause)
                assertEquals(cause.javaClass, refreshFailure.javaClass)
                assertEquals(cause.message, refreshFailure.message)
                assertSame(cause, generateSequence(refreshFailure) { it.cause }.last())
                assertTrue(failure.message.orEmpty().contains("committed"))
                assertEquals(1, effects)
                assertEquals(1, store.fetchContacts(contact.radioId).size)
                val receipt = requireNotNull(engine.committed.value)
                assertSame(failure, receipt.failure)
                assertEquals(1, receipt.result.totalInserted)
                assertNull(receipt.preferences)
                try {
                    engine.importBackup(ParsedAppBackup(backup))
                    fail("A retained receipt must block another database import")
                } catch (_: IllegalStateException) {}
                assertEquals(1, effects)
                assertEquals(1, store.fetchContacts(contact.radioId).size)
                engine.acknowledge()
                assertNull(engine.committed.value)
            }
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
                assertSame(expected, requireNotNull(engine.committed.value).failure)
                assertSame(expected, requireNotNull(engine.committed.value).preferences)
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

    private fun uiDependencies(
        context: Context,
        engine: BackupEngine,
        reports: MutableList<Throwable?>,
        effects: BackupImportEffects = object : BackupImportEffects {
            override fun restoredDataChanged() = Unit
            override fun channelSlotsAffected(slotsByRadio: Map<UUID, Set<Int>>) = Unit
            override suspend fun refreshBlockedContactsCache() = Unit
        },
    ) = BackupFeatureDependencies(
        engine,
        object : BackupDocumentPort {
            override suspend fun save(suggestedName: String, data: ByteArray) = BackupSaveOutcome.Cancelled
            override suspend fun pick() = BackupPickOutcome.Cancelled
        },
        object : BackupConnectionGate { override val isRadioConnected = MutableStateFlow(false) },
        effects,
        BackupDiagnostics { _, failure -> reports += failure },
        ResourceBackupStrings(context.resources),
    )

    private suspend fun AppBackupStateHolder.preview(backup: AppBackupEnvelope) {
        val data = AppBackupCodec().encode(backup).toByteArray()
        loadAndParse(object : BackupSource {
            override val sizeBytes = data.size.toLong()
            override suspend fun readBytes() = data
        }).join()
        assertTrue(state.value.import is ImportState.Preview)
    }
}
