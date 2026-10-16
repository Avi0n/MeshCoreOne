package one.meshcore.shared.settings.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NodeConfigImportCompletionInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()
    private val fixture = NodeConfigImportLifetimeFixture()
    private var evidenceName = "setup"

    @After
    fun tearDown() {
        val sessions = listOf(fixture.session, fixture.replacementSession)
        val jobs = sessions.flatMap { it.nameWriteJobs }
        jobs.forEach { it.cancel() }
        sessions.forEach { it.nameReply.cancel() }
        try {
            compose.waitUntil(TIMEOUT_MS) { jobs.all { it.isCompleted } }
            sessions.forEach {
                assertEquals(it.requestedNames.size, it.completedWrites + it.cancelledWrites)
            }
            File(evidenceDirectory(), "$evidenceName-teardown.txt").writeText(
                "jobs=${jobs.size} completedJobs=${jobs.count { it.isCompleted }} " +
                    "cancelledJobs=${jobs.count { it.isCancelled }} " +
                    "writes=${fixture.session.requestedNames.size} " +
                    "completed=${fixture.session.completedWrites} cancelled=${fixture.session.cancelledWrites}",
            )
        } finally {
            fixture.cancelOwnerScope(compose)
        }
    }

    @Test
    fun completedWriteShowsSuccessOnReplacementMountAndRefreshesOnce() {
        evidenceName = "success-remount"
        beginHeldApply()
        remount()
        compose.runOnIdle { fixture.session.nameReply.complete(Unit) }
        assertSuccess(expectedWrites = 1, expectedCancelled = 0)
        capture(evidenceName)
    }

    @Test
    fun replacementMountCancelsOriginalWriteAndRetryStartsOnlyOneFreshWrite() {
        evidenceName = "cancel-retry"
        beginHeldApply()
        val first = checkNotNull(fixture.session.nameWriteJob)
        remount()
        compose.onNodeWithText(fixture.labels.cancel).assertIsDisplayed().performClick()
        compose.waitUntil(TIMEOUT_MS) { first.isCompleted }
        awaitVisible(fixture.labels.cancelled)
        compose.runOnIdle {
            assertTrue(first.isCancelled)
            assertEquals(1, fixture.session.cancelledWrites)
            assertEquals(0, fixture.session.completedWrites)
            assertEquals(0, fixture.refreshCalls)
            fixture.session.prepareNextWrite()
        }
        confirmApply()
        compose.waitUntil(TIMEOUT_MS) { fixture.session.nameEntered.isCompleted }
        compose.runOnIdle {
            assertNotSame(first, fixture.session.nameWriteJob)
            assertEquals(2, fixture.session.requestedNames.size)
            assertEquals(1, fixture.pickCalls)
            assertEquals(0, fixture.refreshCalls)
        }
        remount()
        compose.onNodeWithText(fixture.labels.cancel).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(fixture.labels.selectFile).assertDoesNotExist()
        compose.runOnIdle { fixture.session.nameReply.complete(Unit) }
        assertSuccess(expectedWrites = 2, expectedCancelled = 1)
        capture(evidenceName)
    }

    @Test
    fun serviceReplacementBetweenPreviewAndConfirmationRefusesEveryWrite() {
        evidenceName = "stale-confirmation"
        renderAndPick()
        compose.onNodeWithText(fixture.labels.apply).assertIsEnabled().performClick()
        awaitVisible(fixture.labels.confirmTitle)
        compose.runOnIdle { fixture.currentService.value = fixture.replacementService }
        compose.waitForIdle()
        confirmDialog()
        awaitVisible(fixture.labels.genericError)
        compose.runOnIdle {
            assertEquals(0, fixture.session.requestedNames.size)
            assertEquals(0, fixture.replacementSession.requestedNames.size)
            assertEquals(0, fixture.replacementSession.selfReads)
            assertEquals(0, fixture.replacementSession.capabilityReads)
            assertEquals(0, fixture.refreshCalls)
        }
        compose.onNodeWithText(fixture.labels.success).assertDoesNotExist()
        capture(evidenceName)
    }

    @Test
    fun radioPartitionChangeAfterConfirmationKeepsSameServiceWriteValid() {
        evidenceName = "partition-change"
        beginHeldApply()
        compose.runOnIdle { fixture.currentRadio.value = NodeConfigImportLifetimeFixture.REPLACEMENT_RADIO }
        remount()
        compose.runOnIdle {
            assertEquals(0, fixture.session.cancelledWrites)
            assertTrue(checkNotNull(fixture.session.nameWriteJob).isActive)
            fixture.session.nameReply.complete(Unit)
        }
        assertSuccess(expectedWrites = 1, expectedCancelled = 0)
        capture(evidenceName)
    }

    @Test
    fun replacementAfterWriteBeginsReportsPartialCancellationWithoutReplacementWritesOrRefresh() {
        evidenceName = "replacement-after-write"
        beginHeldApply()
        val original = checkNotNull(fixture.session.nameWriteJob)
        compose.runOnIdle { fixture.currentService.value = fixture.replacementService }
        remount()
        compose.onNodeWithText(fixture.labels.cancel).assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertTrue(original.isActive)
            assertEquals(0, fixture.session.cancelledWrites)
            assertEquals(0, fixture.replacementSession.requestedNames.size)
            fixture.session.nameReply.complete(Unit)
        }
        compose.waitUntil(TIMEOUT_MS) { original.isCompleted }
        awaitVisible(fixture.labels.cancelledPartial)
        compose.runOnIdle {
            assertTrue(original.isCancelled)
            assertEquals(1, fixture.session.completedWrites)
            assertEquals(0, fixture.session.cancelledWrites)
            assertEquals(1, fixture.session.requestedNames.size)
            assertEquals(0, fixture.replacementSession.requestedNames.size)
            assertEquals(0, fixture.replacementSession.selfReads)
            assertEquals(0, fixture.replacementSession.capabilityReads)
            assertEquals(0, fixture.refreshCalls)
            assertFalse(checkNotNull(fixture.workspace.confirmedState).importComplete)
        }
        compose.onNodeWithText(fixture.labels.success).assertDoesNotExist()
        capture(evidenceName)
    }

    private fun beginHeldApply() {
        renderAndPick()
        confirmApply()
        compose.waitUntil(TIMEOUT_MS) { fixture.session.nameEntered.isCompleted }
        compose.onNodeWithText(fixture.labels.cancel).assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(listOf(NodeConfigImportLifetimeFixture.IMPORTED_NAME), fixture.session.requestedNames)
            assertEquals(0, fixture.refreshCalls)
            assertFalse(fixture.session.nameReply.isCompleted)
        }
    }

    private fun renderAndPick() {
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.selectFile).assertIsDisplayed().performClick()
        awaitVisible(fixture.labels.importedName)
        compose.runOnIdle {
            assertEquals(1, fixture.pickCalls)
            assertEquals(0, fixture.session.requestedNames.size)
        }
    }

    private fun confirmApply() {
        compose.onNodeWithText(fixture.labels.apply).assertIsEnabled().performClick()
        awaitVisible(fixture.labels.confirmTitle)
        confirmDialog()
    }

    private fun confirmDialog() =
        compose.onNode(hasText(fixture.labels.apply) and hasAnyAncestor(isDialog())).performClick()

    private fun remount() {
        compose.runOnIdle { fixture.shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertDoesNotExist()
        compose.runOnIdle {
            fixture.mount.value += 1
            fixture.shown.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertExists()
    }

    private fun assertSuccess(expectedWrites: Int, expectedCancelled: Int) {
        awaitVisible(fixture.labels.success)
        compose.runOnIdle {
            assertEquals(expectedWrites, fixture.session.requestedNames.size)
            assertEquals(expectedCancelled, fixture.session.cancelledWrites)
            assertEquals(1, fixture.session.completedWrites)
            assertEquals(1, fixture.refreshCalls)
            assertEquals(0, fixture.replacementSession.requestedNames.size)
            assertTrue(checkNotNull(fixture.workspace.confirmedState).importComplete)
        }
        compose.onNodeWithText(fixture.labels.cancel).assertDoesNotExist()
    }

    private fun awaitVisible(label: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val directory = evidenceDirectory()
        File(directory, "$name.txt").writeText(compose.onRoot().printToString())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it))
        }
    }

    private fun evidenceDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(checkNotNull(context.getExternalFilesDir(null)), EVIDENCE_DIRECTORY).apply { mkdirs() }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PNG_QUALITY = 100
        const val EVIDENCE_DIRECTORY = "node-config-import-lifetime"
    }
}
