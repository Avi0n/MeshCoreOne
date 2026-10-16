package one.meshcore.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.android.BackupPostcommitGate.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPostcommitCancellationInstrumentedTest {
    @get:Rule val testName = TestName()

    @Test fun draftReturnCancellationReportsCommittedSuccess() = checkPostcommit(Stage.DRAFT, cancel = true)

    @Test fun draftReturnLiveImportReportsSuccess() = checkPostcommit(Stage.DRAFT, cancel = false)

    @Test fun discoverReturnCancellationReportsCommittedSuccess() = checkPostcommit(Stage.DISCOVER, cancel = true)

    @Test fun discoverReturnLiveImportReportsSuccess() = checkPostcommit(Stage.DISCOVER, cancel = false)

    @Test
    fun beforeAppImportCancellationReportsNoChanges() {
        val fixture = BackupPostcommitFixture(Stage.BEFORE, testName.methodName)
        try {
            fixture.open()
            val before = runBlocking { fixture.graph.rows.snapshot() }
            fixture.start()
            assertFalse(fixture.enteredAppImport)
            fixture.cancel()
            fixture.settle()
            assertEquals(before, runBlocking { fixture.graph.rows.snapshot() })
            val outcome = fixture.ui.visible(fixture.ui.cancelled) &&
                !fixture.ui.visible(fixture.ui.success) &&
                fixture.result == null &&
                !fixture.enteredAppImport
            fixture.record(outcome, committed = false, published = false)
            assertTrue("LAST: pre-entry cancellation is truthful and does not import", outcome)
        } finally {
            fixture.close()
        }
    }

    private fun checkPostcommit(stage: Stage, cancel: Boolean) {
        val fixture = BackupPostcommitFixture(stage, testName.methodName)
        try {
            fixture.open()
            fixture.start()
            assertTrue(fixture.enteredAppImport)
            val committed = runBlocking {
                fixture.graph.rows.snapshot().also {
                    fixture.graph.rows.assertCounts(it, copies = 1)
                    fixture.graph.assertCommittedDraftSafety()
                }
            }
            if (cancel) fixture.cancel()
            fixture.settle()
            assertEquals(committed, runBlocking { fixture.graph.rows.snapshot() })
            runBlocking { fixture.graph.assertCommittedDraftSafety() }
            val published = fixture.graph.publishedDiscover()
            val outcome = fixture.ui.visible(fixture.ui.success) &&
                !fixture.ui.visible(fixture.ui.cancelled) &&
                fixture.result?.totalInserted == BackupPostcommitRows.INSERTED &&
                published
            fixture.record(outcome, committed = true, published = published)
            assertTrue("LAST: committed import reports success and completes publication", outcome)
        } finally {
            fixture.close()
        }
    }
}
