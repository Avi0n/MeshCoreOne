// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Actual Room commit/rollback replaces SwiftData autosave/context bookkeeping.
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.model.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackupTransactionTest : BackupRoomTest() {
    @OriginalCase("BackupIntegrationTests::Successful import restores autosave on the destination store()")
    @Test fun successfulRestoreFinishesRealTransactionAndStoreRemainsWritable() = runBlocking {
        val result = service.importBackup(envelope(devices = listOf(device())), store)
        assertEquals(1L, result.count(BackupModelKind.DEVICES).inserted)
        assertFalse(db.inTransaction())
        assertEquals(1, db.devices().all().size)
        store.saveContact(contact())
        assertEquals(1, db.contacts().forRadio(RADIO.value).size)
    }

    @OriginalCase("BackupIntegrationTests::Failed import rolls back reconcile-phase mutations to pre-existing rows()")
    @Test fun preCommitFailureRollsBackRowsAndMetadataTogether() = runBlocking {
        val local = contact().copy(name = "Pre-existing")
        seed(envelope(contacts = listOf(local)))
        val value = envelope(devices = listOf(device()), contacts = listOf(local.copy(id = id(20), nickname = "Changed")),
            messages = listOf(message(text = "Reconcile target").copy(timestamp = 99999u)))
        val faulted = newService(BackupImportHooks(beforeCommit = { throw IOException("injected commit failure") }))
        val failure = expectImportFailure { faulted.importBackup(value, store) }
        assertTrue(failure.error is AppBackupError.ImportFailed)
        assertEquals(local, db.contacts().forRadio(RADIO.value).single().toDTO())
        assertTrue(db.devices().all().isEmpty())
        assertNull(db.messages().byId(RADIO.value, id(4)))
        assertFalse(db.inTransaction())
    }

    @OriginalCase("BackupIntegrationTests::Failed import clears pending data and restores autosave()")
    @Test fun failedRestoreLeavesNoStagedRowsAndNextStoreWriteWorks() = runBlocking {
        val importedDevice = device()
        val injected = IOException("injected commit failure")
        var beforeCommitCalls = 0
        val faulted = newService(BackupImportHooks(beforeCommit = {
            beforeCommitCalls++
            assertTrue(db.inTransaction())
            val staged = db.devices().all().single()
            assertEquals(importedDevice.radioId.value, staged.radioId)
            assertEquals(importedDevice.publicKey, staged.publicKey)
            assertEquals(importedDevice.nodeName, staged.nodeName)
            throw injected
        }))
        val failure = expectImportFailure {
            faulted.importBackup(envelope(devices = listOf(importedDevice)), store)
        }
        assertEquals(1, beforeCommitCalls)
        when (val error = failure.error) {
            is AppBackupError.ImportFailed ->
                assertSame(injected, generateSequence(error.underlying) { it.cause }.last())
            else -> fail("Expected ImportFailed, got ${error.javaClass.simpleName}")
        }
        assertFalse(db.inTransaction())
        assertTrue(db.devices().all().isEmpty())
        store.saveContact(contact(publicKey = key(0xC1)).copy(name = "Recovered Contact"))
        val recovered = store.fetchContacts(RADIO)
        assertEquals(1, recovered.size)
        assertEquals("Recovered Contact", recovered.single().name)
        assertEquals(key(0xC1), recovered.single().publicKey)
    }

    @OriginalCase("BackupIntegrationTests::Disk-backed container has zero partial state after faulted import is abandoned()")
    @Test fun faultedFileRestoreLeavesZeroPartialStateAfterReopen() = runBlocking {
        val file = File(context.cacheDir, "wp203-rollback-${UUID.randomUUID()}.db")
        val database = fileDatabase(file)
        val process = RoomPersistenceStore(database, owner, clock)
        try {
            val faulted = newService(BackupImportHooks(beforeCommit = { throw IOException("injected file failure") }))
            expectImportFailure { faulted.importBackup(envelope(devices = listOf(device()), contacts = listOf(contact().copy(name = "Should not survive"))), process) }
            process.close()
            database.close()
            val reopened = fileDatabase(file)
            try {
                assertTrue(reopened.devices().all().isEmpty())
                assertTrue(reopened.contacts().forRadio(RADIO.value).isEmpty())
                assertEquals(1, reopened.openHelper.readableDatabase.version)
            } finally { reopened.close() }
        } finally {
            process.close()
            database.close()
            context.deleteDatabase(file.absolutePath)
        }
    }

    @OriginalCase("BackupIntegrationTests::Task cancellation after DB commit does not throw and returns a successful result()")
    @Test fun actualPostCommitCallerCancellationStillDeliversCommittedResultWithoutPreferences() = runBlocking {
        var task: Job? = null
        var result: ImportResult? = null
        var failure: CancellationException? = null
        var commits = 0
        var laterCancellationPropagated = false
        val committed = newService(BackupImportHooks(afterCommit = {
            assertFalse(db.inTransaction())
            commits++
            requireNotNull(task).cancel()
        }))
        task = launch(start = CoroutineStart.LAZY) {
            try {
                result = committed.importBackup(envelope(devices = listOf(device())), store)
            } catch (cause: CancellationException) { failure = cause }
            try { currentCoroutineContext().ensureActive() }
            catch (_: CancellationException) { laterCancellationPropagated = true }
        }
        task.start()
        task.join()
        assertEquals(1, commits)
        assertTrue(task.isCancelled)
        assertNull(failure)
        assertEquals(1L, result?.count(BackupModelKind.DEVICES)?.inserted)
        assertEquals(false, result?.userDefaultsRestored)
        assertEquals(1, db.devices().all().size)
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.hasCompletedOnboarding))
        assertTrue(laterCancellationPropagated)
    }

    @Test fun actualPostCommitCallerCancellationStillDeliversCommittedResultAndPreferences() = runBlocking {
        var task: Job? = null
        var result: ImportResult? = null
        var failure: Throwable? = null
        val committed = newService(BackupImportHooks(afterCommit = { requireNotNull(task).cancel() }))
        task = launch(start = CoroutineStart.LAZY) {
            try {
                result = committed.importBackup(envelope(devices = listOf(device()),
                    preferences = BackupUserDefaults(hasCompletedOnboarding = true)), store)
            } catch (cause: CancellationException) { failure = cause }
        }
        task.start()
        task.join()
        assertTrue(task.isCancelled)
        assertNull(failure)
        assertEquals(1L, result?.count(BackupModelKind.DEVICES)?.inserted)
        assertEquals(1, db.devices().all().size)
        assertEquals(true, result?.userDefaultsRestored)
        assertTrue(storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
    }

    @Test fun actualCancellationBeforeFirstInsertDoesNotMutateRows() = runBlocking {
        val cancelled = newService(BackupImportHooks(beforeWrite = { currentCoroutineContext().cancel() }))
        var caught = false
        val task = launch {
            try { cancelled.importBackup(fullEnvelope(), store) }
            catch (cause: CancellationException) { caught = true }
        }
        task.join()
        assertTrue(caught)
        assertTrue(db.devices().all().isEmpty()); assertTrue(db.contacts().backupAll().isEmpty())
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.hasCompletedOnboarding))
    }

    @Test fun actualCancellationDuringParentChildInsertionRollsBackAllImportRows() = runBlocking {
        var writes = 0
        val cancelled = newService(BackupImportHooks(beforeWrite = { if (++writes == 4) currentCoroutineContext().cancel() }))
        var caught = false
        val task = launch {
            try { cancelled.importBackup(fullEnvelope(), store) }
            catch (cause: CancellationException) { caught = true }
        }
        task.join()
        assertTrue(caught); assertEquals(4, writes)
        assertTrue(db.devices().all().isEmpty()); assertTrue(db.contacts().backupAll().isEmpty()); assertTrue(db.channels().backupAll().isEmpty())
        assertTrue(db.sessions().backupAll().isEmpty()); assertNull(db.messages().byId(RADIO.value, id(4)))
    }

    @Test fun actualCancellationImmediatelyBeforeCommitRollsBackAndDoesNotRestorePreferences() = runBlocking {
        val cancelled = newService(BackupImportHooks(beforeCommit = { currentCoroutineContext().cancel() }))
        var caught = false
        val task = launch {
            try { cancelled.importBackup(fullEnvelope(), store) }
            catch (cause: CancellationException) { caught = true }
        }
        task.join()
        assertTrue(caught)
        assertTrue(db.devices().all().isEmpty()); assertTrue(db.traceRuns().backupAll().isEmpty())
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.hasCompletedOnboarding))
    }

    @Test fun actualRoomParentConstraintFailureDoesNotLeaveEarlierParentsCommitted() = runBlocking {
        val local = contact(publicKey = key(1))
        seed(envelope(contacts = listOf(local)))
        val collision = contact(publicKey = key(2))
        val failure = expectImportFailure { service.importBackup(envelope(devices = listOf(device()), contacts = listOf(collision)), store) }
        assertTrue(failure.error is AppBackupError.ImportFailed)
        assertTrue(db.devices().all().isEmpty())
        assertEquals(local, db.contacts().forRadio(RADIO.value).single().toDTO())
    }

    @Test fun postCommitStorageFailureIsExplicitAndNeverClaimsDatabaseRollback() = runBlocking {
        db.pendingSends().insert(com.meshcoreone.android.core.data.repository.pending(radio = RADIO).toEntity())
        val debug = DebugLogEntryDTO.create(DebugLogLevel.INFO, "controlled", "backup", "prior", id(80), AT)
        db.debugLogs().insert(debug.toEntity())
        storage.close()
        val failure = try {
            service.importBackup(envelope(devices = listOf(device()), channels = listOf(channel()),
                preferences = BackupUserDefaults(selectedThemeID = "ember")), store)
            fail("Expected explicit post-commit storage failure")
            throw AssertionError()
        } catch (cause: CommittedBackupPreferenceException) { cause }
        assertEquals(StorageProblem.OwnerClosed, failure.storageFailure.problem)
        assertEquals(1L, failure.result.count(BackupModelKind.DEVICES).inserted)
        assertEquals(1, db.devices().all().size)
        val marker: CommittedBackupPreferenceFailure = failure
        assertSame(failure.storageFailure, marker.preferenceFailure)
        assertSame(marker.preferenceFailure, failure.cause)
        assertEquals(BackupContract.modelArrayKeys.toSet(), marker.committedReceipt.counts.keys)
        for (kind in BackupModelKind.entries) {
            val actual = failure.result.count(kind)
            assertEquals(CommittedBackupCounts(actual.inserted, actual.merged, actual.skipped, actual.dropped),
                marker.committedReceipt.counts[kind.arrayKey])
        }
        assertEquals(failure.result.channelSlotsAffectedByImport, marker.committedReceipt.channelSlotsAffectedByImport)
        assertFalse(marker.committedReceipt.userDefaultsRestored)
        assertFalse(marker.committedReceipt.channelSlotsAffectedByImport.isEmpty())
        val slotsBefore = db.channels().backupAll().map { it.radioId to it.index }
        assertEquals(1, db.pendingSends().forRadio(RADIO.value).size)
        assertEquals(1L, db.debugLogs().count())
        assertFalse(failure.message.orEmpty().lowercase().contains("rollback"))
        storage = MeshCoreStorage.get(context)
        val recovered = newService().completePreferences(failure)
        assertTrue(recovered.userDefaultsRestored)
        assertEquals("ember", storage.preferences.get(AppearanceStorageKey.selectedThemeID))
        assertEquals(1, db.devices().all().size)
        assertEquals(slotsBefore, db.channels().backupAll().map { it.radioId to it.index })
        assertEquals(failure.result.counts, recovered.counts)
        assertEquals(1, db.pendingSends().forRadio(RADIO.value).size)
        assertEquals(1L, db.debugLogs().count())
        val repeated = newService().completePreferences(failure)
        assertEquals(recovered.counts, repeated.counts)
        assertEquals(1, db.devices().all().size)
        assertEquals(slotsBefore, db.channels().backupAll().map { it.radioId to it.index })
        assertEquals("ember", storage.preferences.get(AppearanceStorageKey.selectedThemeID))
    }

    @Test fun envelopeVersionCountsAndAmbiguousRelationshipsFailBeforeAnyWrite() = runBlocking {
        var writes = 0
        val observing = newService(BackupImportHooks(beforeWrite = { writes++ }))
        for (value in listOf(fullEnvelope().copy(version = 2), fullEnvelope().copy(manifest = BackupManifest()))) {
            expectImportFailure { observing.importBackup(value, store) }
        }
        try {
            observing.importBackup(envelope(messages = listOf(message(), message(OTHER_RADIO))), store)
            fail("Ambiguous wire identity must fail")
        } catch (cause: BackupValueException) { assertEquals(BackupValueProblem.IDENTITY, cause.problem) }
        assertEquals(0, writes); assertTrue(db.devices().all().isEmpty())
    }

    @Test fun fullPublicKeySuffixCollisionDoesNotMatchOrRewriteRadioUuidBits() = runBlocking {
        val first = ByteArray(32) { 0x42 }
        val second = first.copyOf().also { it[31] = 0x43 }
        seed(envelope(devices = listOf(device(OTHER_RADIO, com.meshcoreone.android.core.protocol.bytes.Bytes(first)))))
        val custom = RadioId(UUID.fromString("01234567-89AB-CDEF-FE01-23456789ABCD"))
        val result = service.importBackup(envelope(
            devices = listOf(device(custom, com.meshcoreone.android.core.protocol.bytes.Bytes(second), id(90))),
            contacts = listOf(contact(custom))), store)
        assertEquals(1L, result.count(BackupModelKind.DEVICES).inserted)
        assertEquals(2, db.devices().all().size)
        assertEquals(custom.value, db.contacts().forRadio(custom.value).single().radioId)
        assertTrue(db.contacts().forRadio(OTHER_RADIO.value).isEmpty())
        assertEquals("01234567-89AB-CDEF-FE01-23456789ABCD", db.devices().forRadio(custom.value).single().radioId.canonicalString())
    }

    @Test fun failedRestoreRetainsPreviouslyBufferedRxAndDoesNotTouchPendingOrDebugHistory() = runBlocking {
        val rx = com.meshcoreone.android.core.data.repository.rx(radio = RADIO)
        store.saveRxLogEntry(rx)
        db.pendingSends().insert(com.meshcoreone.android.core.data.repository.pending(radio = RADIO).toEntity())
        val debug = DebugLogEntryDTO.create(DebugLogLevel.INFO, "controlled", "backup", "prior", id(80), AT)
        db.debugLogs().insert(debug.toEntity())
        val faulted = newService(BackupImportHooks(beforeCommit = { throw IOException("controlled rollback") }))
        expectImportFailure { faulted.importBackup(envelope(devices = listOf(device()), contacts = listOf(contact())), store) }
        assertEquals(1L, db.rxLogs().count(rx.radioId.value))
        assertEquals(rx.id, db.rxLogs().newest(rx.radioId.value, 10).single().id)
        assertEquals(1, db.pendingSends().forRadio(RADIO.value).size)
        assertEquals(1L, db.debugLogs().count())
        assertTrue(db.devices().all().isEmpty()); assertTrue(db.contacts().backupAll().isEmpty())
        val backup = service.exportEnvelope(store)
        assertEquals(0L, BackupModelKind.entries.sumOf { backup.manifest.count(it) })
    }

    @Test fun sourcePreferenceKeysDoNotOverwriteSceneOrLastRadioProcessState() = runBlocking {
        val scene = UUID.randomUUID()
        storage.scenePreferences.set(scene, SceneStorageKey.MAP_CAMERA_REGION, "local-camera")
        val processKey = PreferenceKey.StringKey(PersistenceKeys.LAST_CONNECTED_RADIO_ID, null)
        storage.preferences.set(processKey, OTHER_RADIO.canonicalString)
        service.importBackup(envelope(preferences = BackupUserDefaults(selectedThemeID = "future-unmodeled-theme")), store)
        assertEquals("future-unmodeled-theme", storage.preferences.get(AppearanceStorageKey.selectedThemeID))
        assertEquals("local-camera", storage.scenePreferences.get(scene, SceneStorageKey.MAP_CAMERA_REGION))
        assertEquals(OTHER_RADIO.canonicalString, storage.preferences.get(processKey))
        val prefs = BackupUserDefaults.snapshot(storage.backupPreferences).encode()
        assertFalse(prefs.containsKey(PersistenceKeys.LAST_CONNECTED_RADIO_ID))
        assertFalse(prefs.keys.any { it.startsWith("scene.") })
    }

    private suspend fun expectImportFailure(operation: suspend () -> Unit): AppBackupException =
        try { operation(); throw AssertionError("Expected typed backup failure") } catch (cause: AppBackupException) { cause }
}
