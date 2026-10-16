package one.meshcore.shared.settings.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NodeConfigImportLifetimeInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()
    private val fixture = NodeConfigImportLifetimeFixture()
    private var evidenceName = "setup"

    @After
    fun tearDown() {
        val original = fixture.session.nameWriteJob
        original?.cancel()
        fixture.session.nameReply.cancel()
        if (original == null) {
            fixture.cancelOwnerScope(compose)
            return
        }
        try {
            compose.waitUntil(TIMEOUT_MS) { original.isCompleted }
            assertTrue(original.isCancelled)
            assertEquals(1, fixture.session.cancelledWrites)
            assertEquals(0, fixture.session.completedWrites)
            assertEquals(1, fixture.session.requestedNames.size)
        } finally {
            writeTeardownReceipt(original)
            fixture.cancelOwnerScope(compose)
        }
    }

    @Test
    fun replacementMountKeepsOriginalApplyProgressAndCancelReachable() {
        evidenceName = "remount"
        beginHeldApply()
        capture("remount-before")
        compose.runOnIdle { fixture.shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertDoesNotExist()
        compose.runOnIdle {
            fixture.mount.value += 1
            fixture.shown.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertExists()
        capture("remount-after")
        assertHeldControls()
        cancelOriginalApply()
    }

    @Test
    fun missingServiceProviderKeepsOriginalApplyCancelReachable() {
        evidenceName = "provider"
        beginHeldApply()
        capture("provider-before")
        compose.runOnIdle { fixture.serviceAvailable.value = false }
        compose.waitForIdle()
        capture("provider-after")
        assertHeldControls()
        cancelOriginalApply()
    }

    private fun beginHeldApply() {
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.selectFile).assertIsDisplayed().performClick()
        awaitVisible(fixture.labels.importedName)
        compose.runOnIdle {
            assertEquals(1, fixture.pickCalls)
            assertEquals(0, fixture.session.requestedNames.size)
        }
        compose.onNodeWithText(fixture.labels.apply).assertIsEnabled().performClick()
        awaitVisible(fixture.labels.confirmTitle)
        compose.runOnIdle { assertEquals(0, fixture.session.requestedNames.size) }
        compose.onNode(hasText(fixture.labels.apply) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(TIMEOUT_MS) { fixture.session.nameEntered.isCompleted }
        compose.waitForIdle()
        assertHeldControls()
    }

    private fun assertHeldControls() {
        compose.runOnIdle {
            assertEquals(listOf(NodeConfigImportLifetimeFixture.IMPORTED_NAME), fixture.session.requestedNames)
            assertEquals(1, fixture.pickCalls)
            assertEquals(0, fixture.refreshCalls)
            assertEquals(0, fixture.session.cancelledWrites)
            assertEquals(0, fixture.session.completedWrites)
            assertTrue(checkNotNull(fixture.session.nameWriteJob).isActive)
            assertFalse(fixture.session.nameReply.isCompleted)
        }
        compose.onNodeWithText(fixture.labels.cancel).assertIsDisplayed().assertIsEnabled()
        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0f, 0f..1f),
            ),
        ).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.selectFile).assertDoesNotExist()
        compose.onNodeWithText(fixture.labels.apply).assertDoesNotExist()
    }

    private fun cancelOriginalApply() {
        val original = checkNotNull(fixture.session.nameWriteJob)
        compose.onNodeWithText(fixture.labels.cancel).performClick()
        compose.waitUntil(TIMEOUT_MS) { original.isCompleted }
        compose.runOnIdle {
            assertTrue(original.isCancelled)
            assertEquals(1, fixture.session.cancelledWrites)
            assertEquals(0, fixture.session.completedWrites)
            assertEquals(1, fixture.session.requestedNames.size)
            assertEquals(0, fixture.refreshCalls)
            fixture.serviceAvailable.value = true
        }
        awaitVisible(fixture.labels.cancelled)
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
        File(directory, "$name-counts.txt").writeText(
            "pick=${fixture.pickCalls} refresh=${fixture.refreshCalls} " +
                "writes=${fixture.session.requestedNames.size} cancelled=${fixture.session.cancelledWrites} " +
                "completed=${fixture.session.completedWrites} selfReads=${fixture.session.selfReads} " +
                "capabilityReads=${fixture.session.capabilityReads}",
        )
    }

    private fun writeTeardownReceipt(original: Job) {
        File(evidenceDirectory(), "$evidenceName-teardown.txt").writeText(
            "jobCompleted=${original.isCompleted} jobCancelled=${original.isCancelled} " +
                "writes=${fixture.session.requestedNames.size} cancelled=${fixture.session.cancelledWrites} " +
                "completed=${fixture.session.completedWrites}",
        )
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
