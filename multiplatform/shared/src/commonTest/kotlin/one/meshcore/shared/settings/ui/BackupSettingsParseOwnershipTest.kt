package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.loadBackupFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsParseOwnershipTest {
    @Test
    fun anotherParseCannotConsumeThePendingPickerOrReplaceItsPreview() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        val picked = CompletableDeferred<String?>()
        var picks = 0
        state.beginParse({
            picks += 1
            picked.await()
        }, this)
        state.beginParse({
            picks += 1
            null
        }, this)
        assertEquals(1, picks)
        assertTrue(state.isParsing)
        picked.complete(loadBackupFixture("full-envelope.json"))
        assertFalse(state.isParsing)
        assertNotNull(state.preview)
        assertEquals(2, state.preview?.messageCount)
    }
}
