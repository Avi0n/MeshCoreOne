package one.meshcore.shared.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Post-commit import side effects must not surface cancel as "nothing was changed". */
class BackupImportFinishTest {
    @Test
    fun prefsCancelAfterCommitReturnsFalseInsteadOfThrowing() = runTest {
        val restored = restorePrefsAfterCommittedImport { throw CancellationException("cut after Room") }
        assertFalse(restored)
    }

    @Test
    fun prefsSuccessIsForwarded() = runTest {
        assertTrue(restorePrefsAfterCommittedImport { true })
        assertFalse(restorePrefsAfterCommittedImport { false })
    }

    @Test
    fun prefsFaultAfterCommitReturnsFalse() = runTest {
        assertFalse(restorePrefsAfterCommittedImport { error("datastore") })
    }

    @Test
    fun adoptCancelAfterCommitDoesNotThrow() = runTest {
        var ran = 0
        adoptRadioAfterCommittedImport {
            ran += 1
            throw CancellationException("cut after Room")
        }
        assertEquals(1, ran)
    }
}
