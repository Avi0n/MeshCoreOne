package one.meshcore.shared.settings.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.BackupImportResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupSettingsLifetimeInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fixture = BackupSettingsLifetimeFixture(scope)

    @After
    fun tearDown() {
        runBlocking { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
    }

    @Test
    fun pendingPickShowsPreviewOnReplacementMountAndMergesOnlyAfterConfirmation() {
        render()
        click(fixture.labels.importFile)
        await { fixture.pickCalls == 1 }
        remount()
        compose.runOnIdle {
            assertEquals(0, fixture.importCalls)
            fixture.pickReply.complete(fixture.json)
        }
        visible(fixture.labels.preview)
        compose.runOnIdle {
            assertEquals(1, fixture.pickCalls)
            assertEquals(0, fixture.importCalls)
        }
        click(fixture.labels.confirmImport)
        await { fixture.importCalls == 1 }
        compose.runOnIdle {
            assertEquals(listOf(fixture.json), fixture.importedJson)
            fixture.importReply.complete(BackupImportResult(devicesInserted = 1))
        }
        visible(fixture.labels.importSuccess)
        remount()
        visible(fixture.labels.importSuccess)
        compose.runOnIdle { assertEquals(1, fixture.importCalls) }
        click(fixture.labels.ok)
        visible(fixture.labels.importFile)
        remount()
        compose.onNodeWithText(fixture.labels.importSuccess).assertDoesNotExist()
    }

    @Test
    fun pendingExportShowsSuccessOnReplacementMountWithoutExportingTwice() {
        render()
        click(fixture.labels.export)
        click(fixture.labels.confirmExport)
        await { fixture.exportCalls == 1 }
        remount()
        compose.runOnIdle { fixture.exportReply.complete(fixture.summary) }
        visible(fixture.labels.exportSuccess)
        visible(BackupSettingsLifetimeFixture.FILENAME)
        remount()
        visible(fixture.labels.exportSuccess)
        compose.runOnIdle { assertEquals(1, fixture.exportCalls) }
        click(fixture.labels.ok)
        visible(fixture.labels.export)
        remount()
        compose.onNodeWithText(fixture.labels.exportSuccess).assertDoesNotExist()
    }

    @Test
    fun cancelledPickAfterRemountReturnsIdleAndFreshPickCanBePreviewed() {
        render()
        click(fixture.labels.importFile)
        await { fixture.pickCalls == 1 }
        remount()
        compose.runOnIdle { fixture.pickReply.complete(null) }
        visible(fixture.labels.importFile)
        compose.onNodeWithText(fixture.labels.preview).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, fixture.importCalls)
            fixture.pickReply = CompletableDeferred()
        }
        click(fixture.labels.importFile)
        await { fixture.pickCalls == 2 }
        remount()
        compose.runOnIdle { fixture.pickReply.complete(fixture.json) }
        visible(fixture.labels.preview)
        compose.runOnIdle { assertEquals(0, fixture.importCalls) }
    }

    @Test
    fun replacementMountCanCancelOriginalImportAndStartANewImport() {
        render()
        compose.runOnIdle { fixture.pickReply.complete(fixture.json) }
        click(fixture.labels.importFile)
        visible(fixture.labels.preview)
        click(fixture.labels.confirmImport)
        await { fixture.importCalls == 1 }
        remount()
        visible(fixture.labels.importing)
        compose.runOnIdle {
            assertEquals(0, fixture.cancelledImports)
            assertFalse(fixture.importReply.isCompleted)
        }
        click(fixture.labels.cancel)
        visible(fixture.labels.cancelled)
        await { fixture.cancelledImports == 1 }
        click(fixture.labels.ok)
        compose.runOnIdle {
            fixture.pickReply = CompletableDeferred<String?>().also { it.complete(fixture.json) }
            fixture.importReply = CompletableDeferred()
        }
        click(fixture.labels.importFile)
        visible(fixture.labels.preview)
        click(fixture.labels.confirmImport)
        await { fixture.importCalls == 2 }
        remount()
        compose.runOnIdle { fixture.importReply.complete(BackupImportResult(devicesInserted = 1)) }
        visible(fixture.labels.importSuccess)
        compose.runOnIdle {
            assertEquals(2, fixture.pickCalls)
            assertEquals(2, fixture.importCalls)
            assertEquals(1, fixture.cancelledImports)
        }
    }

    private fun render() {
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
    }

    private fun remount() {
        compose.runOnIdle { fixture.shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(BackupSettingsLifetimeFixture.MOUNT_TAG).assertDoesNotExist()
        compose.runOnIdle {
            fixture.mount.value += 1
            fixture.shown.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(BackupSettingsLifetimeFixture.MOUNT_TAG).assertExists()
    }

    private fun click(label: String) {
        visible(label)
        compose.onNodeWithText(label).performClick()
    }

    private fun visible(label: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS, condition)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
