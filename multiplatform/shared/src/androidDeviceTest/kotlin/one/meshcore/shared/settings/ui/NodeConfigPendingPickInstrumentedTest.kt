package one.meshcore.shared.settings.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.settings.NodeConfigJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeConfigPendingPickInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()
    private val fixture = NodeConfigImportLifetimeFixture()
    private val selected = CompletableDeferred<String?>()

    @After
    fun tearDown() {
        selected.cancel()
        try {
            compose.waitUntil(TIMEOUT_MS) { fixture.pickJob?.isCompleted != false }
        } finally {
            fixture.cancelOwnerScope(compose)
        }
    }

    @Test
    fun originalSelectionOpensItsPreviewAfterPendingScreenRemount() {
        fixture.pendingPick = selected
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.selectFile).assertIsDisplayed().performClick()
        compose.waitUntil(TIMEOUT_MS) { fixture.pickCalls == 1 }
        val original = compose.runOnIdle { checkNotNull(fixture.pickJob) }
        compose.runOnIdle {
            assertTrue(original.isActive)
            fixture.shown.value = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertDoesNotExist()
        compose.runOnIdle {
            fixture.mount.value += 1
            fixture.shown.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertExists()
        compose.runOnIdle {
            selected.complete(
                NodeConfigJson.encode(MeshCoreNodeConfig(name = NodeConfigImportLifetimeFixture.IMPORTED_NAME)),
            )
        }
        compose.waitUntil(TIMEOUT_MS) { original.isCompleted }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, fixture.pickCalls)
            assertEquals(0, fixture.session.requestedNames.size)
            assertEquals(0, fixture.refreshCalls)
        }
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.selectFile).assertDoesNotExist()
        assertEquals(1, compose.onAllNodesWithText(fixture.labels.importedName).fetchSemanticsNodes().size)
    }

    @Test
    fun outgoingPresentationCannotEraseTheSurvivingPresentationsReturnedPreview() {
        val original = beginHeldSelection()
        compose.runOnIdle { fixture.replacementShown.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.REPLACEMENT_TAG).assertExists()
        completeSelection(original)
        assertEquals(2, compose.onAllNodesWithText(fixture.labels.importedName).fetchSemanticsNodes().size)
        compose.runOnIdle { fixture.shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.REPLACEMENT_TAG).assertExists()
        assertUnchangedReadOnlySelection()
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.selectFile).assertDoesNotExist()
    }

    @Test
    fun originalSelectionCompletedBetweenPresentationsOpensOnTheNextMount() {
        val original = beginHeldSelection()
        compose.runOnIdle { fixture.shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertDoesNotExist()
        completeSelection(original)
        compose.runOnIdle {
            fixture.mount.value += 1
            fixture.shown.value = true
        }
        compose.waitForIdle()
        assertUnchangedReadOnlySelection()
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.selectFile).assertDoesNotExist()
    }

    @Test
    fun disposingTheOnlyCompletedPreviewReturnsTheNextMountToFileSelection() {
        val original = beginHeldSelection()
        completeSelection(original)
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.runOnIdle { fixture.shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(NodeConfigImportLifetimeFixture.MOUNT_TAG).assertDoesNotExist()
        compose.runOnIdle {
            fixture.mount.value += 1
            fixture.shown.value = true
        }
        compose.waitForIdle()
        assertUnchangedReadOnlySelection()
        compose.onNodeWithText(fixture.labels.importedName).assertDoesNotExist()
        compose.onNodeWithText(fixture.labels.selectFile).assertIsDisplayed()
    }

    private fun beginHeldSelection(): Job {
        fixture.pendingPick = selected
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.selectFile).assertIsDisplayed().performClick()
        compose.waitUntil(TIMEOUT_MS) { fixture.pickCalls == 1 }
        return compose.runOnIdle { checkNotNull(fixture.pickJob).also { assertTrue(it.isActive) } }
    }

    private fun completeSelection(original: Job) {
        compose.runOnIdle {
            selected.complete(
                NodeConfigJson.encode(MeshCoreNodeConfig(name = NodeConfigImportLifetimeFixture.IMPORTED_NAME)),
            )
        }
        compose.waitUntil(TIMEOUT_MS) { original.isCompleted }
        compose.waitForIdle()
    }

    private fun assertUnchangedReadOnlySelection() {
        compose.runOnIdle {
            assertEquals(1, fixture.pickCalls)
            assertEquals(0, fixture.session.requestedNames.size)
            assertEquals(0, fixture.refreshCalls)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
