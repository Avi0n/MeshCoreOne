package one.meshcore.shared.settings.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.settings.NodeConfigJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeConfigColdRecoveryInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()
    private val fixture = NodeConfigImportLifetimeFixture()
    private val selected = CompletableDeferred<String>()
    private var readJob: Job? = null
    private var readCalls = 0

    @After
    fun tearDown() {
        selected.cancel()
        try {
            compose.waitUntil(TIMEOUT_MS) { readJob?.isCompleted != false }
        } finally {
            fixture.cancelOwnerScope(compose)
        }
    }

    @Test
    fun disconnectedReturnedReadShowsProgressThenPreviewWithoutEnablingApply() {
        stageWithoutService { selected.await() }
        compose.waitUntil(TIMEOUT_MS) { readCalls == 1 }
        compose.onNodeWithText(fixture.labels.selectFile).assertIsDisplayed().assertIsNotEnabled()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(1)
        compose.runOnIdle {
            selected.complete(
                NodeConfigJson.encode(MeshCoreNodeConfig(name = NodeConfigImportLifetimeFixture.IMPORTED_NAME)),
            )
        }
        compose.waitUntil(TIMEOUT_MS) { readJob?.isCompleted == true }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.apply).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(fixture.labels.selectFile).assertDoesNotExist()
        assertReadOnly()
    }

    @Test
    fun disconnectedReadFailureShowsTheGeneratedErrorAndNoDeviceState() {
        stageWithoutService { throw IllegalStateException("Unmapped synthetic read fault") }
        assertOfflineFailure(fixture.labels.genericError)
    }

    @Test
    fun disconnectedMalformedFileShowsTheGeneratedInvalidFileError() {
        stageWithoutService { "{" }
        assertOfflineFailure(fixture.labels.invalidFile)
    }

    @Test
    fun aRetainedMountedPreviewHidesTheOldRadiosCurrentComparison() {
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.selectFile).performClick()
        compose.waitUntil(TIMEOUT_MS) { fixture.pickJob?.isCompleted == true }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.currentName).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.runOnIdle { fixture.currentService.value = fixture.replacementService }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.currentName).assertDoesNotExist()
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.runOnIdle { fixture.serviceAvailable.value = false }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.currentName).assertDoesNotExist()
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.apply).assertIsNotEnabled()
        compose.runOnIdle {
            fixture.currentRadio.value = NodeConfigImportLifetimeFixture.REPLACEMENT_RADIO
            fixture.serviceAvailable.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithText(fixture.labels.currentName).assertDoesNotExist()
        compose.onNodeWithText(fixture.labels.importedName).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, fixture.pickCalls)
            assertEquals(1, fixture.session.selfReads)
            assertEquals(0, fixture.replacementSession.selfReads)
            assertEquals(0, fixture.replacementSession.requestedNames.size)
            assertEquals(0, fixture.session.requestedNames.size)
            assertEquals(0, fixture.session.capabilityReads)
            assertEquals(0, fixture.refreshCalls)
        }
    }

    private fun stageWithoutService(readSelected: suspend () -> String) {
        compose.runOnUiThread {
            fixture.serviceAvailable.value = false
            assertTrue(
                fixture.workspace.recoverPickedConfig(
                    readSelected = {
                        readCalls += 1
                        readJob = currentCoroutineContext()[Job]
                        readSelected()
                    },
                    showReview = {},
                ),
            )
        }
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
    }

    private fun assertOfflineFailure(message: String) {
        compose.waitUntil(TIMEOUT_MS) { readJob?.isCompleted == true }
        compose.waitForIdle()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.noDevice).assertIsDisplayed()
        compose.onNodeWithText(fixture.labels.importedName).assertDoesNotExist()
        compose.onNodeWithText(fixture.labels.apply).assertDoesNotExist()
        assertReadOnly()
    }

    private fun assertReadOnly() {
        compose.runOnIdle {
            assertEquals(1, readCalls)
            assertEquals(0, fixture.pickCalls)
            assertEquals(0, fixture.session.selfReads)
            assertEquals(0, fixture.session.capabilityReads)
            assertEquals(0, fixture.session.requestedNames.size)
            assertEquals(0, fixture.refreshCalls)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
