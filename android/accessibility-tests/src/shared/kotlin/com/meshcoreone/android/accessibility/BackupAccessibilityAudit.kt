// AndroidOnly: WP-503 Share original backup UI assertions and explicit ports across JVM/native; not codec/restore evidence.
package com.meshcoreone.android.accessibility

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.style.TextAlign
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.feature.settings.app.backup.*
import com.meshcoreone.android.feature.settings.app.backup.ui.BackupRestoreScreen
import com.meshcoreone.android.feature.settings.app.backup.ui.ResourceBackupStrings
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

internal class BackupAccessibilityAudit(
    override val compose: ComposeTestRule,
    private val host: Supplier<ComponentActivity>,
    private val render: (@Composable () -> Unit) -> Unit,
    private val surface: @Composable (@Composable () -> Unit) -> Unit,
    private val dialogDecor: Supplier<View>,
) : NativeAccessibilityAssertions() {
    override val activity: ComponentActivity get() = host.get()

    override fun content(body: @Composable () -> Unit) = render(body)

    @Composable
    override fun AuditSurface(body: @Composable () -> Unit) = surface(body)

    fun connectedImportExplainsItsGateAndPickerCancellationAllowsAnotherAttempt() {
        val rig = Rig(connected = true)
        mount(rig)
        action(S.settingsBackupImportTitle).assertIsNotEnabled()
        reachableText(host.get().getString(S.settingsBackupImportDisabledWhenConnected), modal = false)
        assertEquals(0, rig.picks.get())
        compose.runOnIdle { rig.connected.value = false }
        mainList().performScrollToNode(hasText(host.get().getString(S.settingsBackupImportTitle)))
        assertAction(S.settingsBackupImportTitle).performClick()
        compose.waitUntil { rig.picks.get() == 1 }
        assertAction(S.settingsBackupImportTitle).performClick()
        compose.waitUntil { rig.picks.get() == 2 }
        assertEquals(0, rig.parses.get())
        assertTrue(rig.failures.isEmpty())
    }

    fun typedParseFailureIsReadableAndDismissalRestoresTheActualImportAction() {
        val rig = Rig().apply { picked = true; invalid = true }
        mount(rig)
        assertAction(S.settingsBackupImportTitle).performClick()
        val message = host.get().getString(S.settingsBackupErrorInvalidFile)
        awaitText(message)
        reachableText(message, modal = true, scroll = false)
        assertAction(S.settingsBackupImportErrorDismiss, modal = true).performClick()
        assertAction(S.settingsBackupImportTitle)
        assertEquals(1, rig.picks.get())
        assertEquals(1, rig.parses.get())
        assertEquals(0, rig.imports.get())
        assertEquals(1, rig.failures.size)
        assertEquals("Backup parse failed", rig.failures.single().first)
        val failure = rig.failures.single().second
        assertTrue(failure is AppBackupException && failure.error == AppBackupError.InvalidFile)
    }

    fun previewAndMixedResultExposeTheirActualCountsAndScrollableCompletion() {
        val rig = Rig().apply { picked = true }
        mount(rig)
        openPreview()
        checkCount(S.settingsBackupImportPreviewContacts, "17")
        checkCount(S.settingsBackupImportPreviewMessages, "23")
        checkCount(S.settingsBackupImportPreviewChannels, "3")
        checkCount(S.settingsBackupImportPreviewDiscoveredNodes, "5")
        modalList().performScrollToNode(hasText(host.get().getString(S.settingsBackupImportPreviewButton)))
        assertAction(S.settingsBackupImportPreviewButton, modal = true).performClick()
        awaitText(host.get().getString(S.settingsBackupImportSuccessTitle))
        reachableText(host.get().getString(S.settingsBackupImportSuccessTitle))
        reachableText(S.settingsBackupImportSuccessSubtitleAdded(host.get().resources, 17))
        checkCount(S.settingsBackupImportPreviewContacts, "17")
        reachableText(S.settingsBackupImportSuccessAlreadyHereSummary(host.get().resources, 23))
        checkCount(S.settingsBackupImportPreviewMessages, "23")
        reachableText(S.settingsBackupImportSuccessDroppedSummary(host.get().resources, 8))
        checkCount(S.settingsBackupImportPreviewChannels, "3")
        checkCount(S.settingsBackupImportPreviewDiscoveredNodes, "5")
        reachableText(S.settingsBackupImportSuccessDroppedFooterMixed(host.get().resources, 1000))
        modalList().performScrollToNode(hasText(host.get().getString(S.settingsBackupImportSuccessDone)))
        assertAction(S.settingsBackupImportSuccessDone, modal = true).performClick()
        assertAction(S.settingsBackupImportTitle)
        assertEquals(1, rig.imports.get())
        assertEquals(1, rig.changed.get())
        assertEquals(1, rig.refreshed.get())
        assertTrue(rig.failures.isEmpty())
    }

    fun pendingImportHasTextualProgressAndAnAccessibleCancellationOutcome() {
        val rig = Rig().apply { picked = true; pending = true }
        mount(rig)
        openPreview()
        modalList().performScrollToNode(hasText(host.get().getString(S.settingsBackupImportPreviewButton)))
        assertAction(S.settingsBackupImportPreviewButton, modal = true).performClick()
        val progress = host.get().getString(S.settingsBackupImportProgress)
        awaitText(progress)
        reachableText(progress, scroll = false)
        val progressNode = compose.onNodeWithText(progress).fetchSemanticsNode()
        assertTrue(generateSequence(progressNode) { it.parent }.any { it.config.contains(SemanticsProperties.LiveRegion) })
        assertAction(S.settingsBackupImportPreviewCancel, modal = true).performClick()
        awaitText(host.get().getString(S.settingsBackupImportCancelledTitle))
        reachableText(host.get().getString(S.settingsBackupImportCancelledTitle), scroll = false)
        reachableText(host.get().getString(S.settingsBackupImportCancelledMessage), scroll = false)
        assertAction(S.settingsBackupImportSuccessDone, modal = true).performClick()
        assertAction(S.settingsBackupImportTitle)
        assertEquals(1, rig.imports.get())
        assertEquals(0, rig.changed.get())
        assertEquals(0, rig.refreshed.get())
        assertTrue(rig.failures.isEmpty())
    }

    private fun mount(rig: Rig) {
        content { MeshCoreTheme(motionScale = 0f) { AuditSurface { BackupRestoreScreen(rig.dependencies) } } }
    }

    private fun openPreview() {
        assertAction(S.settingsBackupImportTitle).performClick()
        awaitText(host.get().getString(S.settingsBackupImportPreviewTitle))
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun mainList() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    private fun modalList() = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog()),
    )

    private fun action(id: Int) = compose.onNode(hasText(host.get().getString(id)) and hasClickAction())

    private fun assertAction(id: Int, modal: Boolean = false): SemanticsNodeInteraction {
        val action = action(id).assertIsDisplayed().assertIsEnabled()
        val node = action.fetchSemanticsNode()
        val root = generateSequence(node) { it.parent }.last()
        if (modal) assertTrue("Bind modal geometry to the actual dialog", generateSequence(node) { it.parent }.any {
            it.config.contains(SemanticsProperties.IsDialog)
        })
        val bounds = node.touchBoundsInRoot
        val density = host.get().resources.displayMetrics.density
        println("WP503_BACKUP_TARGET|label=${host.get().getString(id)}|bounds=$bounds|viewport=${root.boundsInRoot}")
        assertTrue("The actual backup action must be at least 48dp", bounds.width / density >= 48f && bounds.height / density >= 48f)
        assertTrue("The complete action must be contained in its own native window",
            bounds.left >= root.boundsInRoot.left && bounds.top >= root.boundsInRoot.top &&
                bounds.right <= root.boundsInRoot.right && bounds.bottom <= root.boundsInRoot.bottom)
        assertFont200Unclipped(compose.onNodeWithText(host.get().getString(id), useUnmergedTree = true))
        return action
    }

    private fun reachableText(text: String, modal: Boolean = true, scroll: Boolean = true) {
        if (scroll) (if (modal) modalList() else mainList()).performScrollToNode(hasText(text))
        val interaction = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layout = font200Layout(interaction)
        if (modal && layout.layoutInput.style.textAlign == TextAlign.Center && layout.multiParagraph.width > layout.size.width) {
            assertFont200PaintedUnclipped(interaction, dialogDecor.get())
        } else {
            assertUnclippedContent(layout)
        }
        val node = interaction.fetchSemanticsNode()
        val root = generateSequence(node) { it.parent }.last()
        val bounds = node.boundsInRoot
        println("WP503_BACKUP_TEXT|text=$text|bounds=$bounds|size=${node.size}|viewport=${root.boundsInRoot}")
        assertTrue("The actual backup text must not be clipped after reaching it",
            bounds.width == node.size.width.toFloat() && bounds.height == node.size.height.toFloat() &&
                bounds.left >= root.boundsInRoot.left && bounds.top >= root.boundsInRoot.top &&
                bounds.right <= root.boundsInRoot.right && bounds.bottom <= root.boundsInRoot.bottom)
    }

    private fun checkCount(labelId: Int, count: String) {
        val label = host.get().getString(labelId)
        modalList().performScrollToNode(hasText(label))
        reachableText(label, scroll = false)
        reachableText(count, scroll = false)
        val labelNode = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val countNode = compose.onNodeWithText(count, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("The count must accompany its real model label", labelNode.positionInRoot.y, countNode.positionInRoot.y, 0f)
        assertTrue("Label and value must not overpaint one another", !labelNode.boundsInRoot.overlaps(countNode.boundsInRoot))
    }

    private inner class Rig(connected: Boolean = false) {
        val connected = MutableStateFlow(connected)
        var picked = false
        var invalid = false
        var pending = false
        val picks = AtomicInteger()
        val parses = AtomicInteger()
        val imports = AtomicInteger()
        val changed = AtomicInteger()
        val refreshed = AtomicInteger()
        val failures = CopyOnWriteArrayList<Pair<String, Throwable?>>()
        private val parsed = object : ParsedBackup {
            override val exportDate = Instant.ofEpochSecond(1_700_000_000)
            override val appVersion = "1.2.3"
            override val appBuild = "456"
            override val manifest = BackupManifest.of(
                BackupModelKind.CONTACTS to 17, BackupModelKind.MESSAGES to 23,
                BackupModelKind.CHANNELS to 3, BackupModelKind.DISCOVERED_NODES to 5,
            )
        }
        val dependencies = BackupFeatureDependencies(
            engine = object : BackupEngine {
                override suspend fun export(): BackupExport = error("Import audit must not export")
                override suspend fun parse(data: ByteArray): ParsedBackup {
                    parses.incrementAndGet()
                    assertTrue(data.contentEquals(byteArrayOf(1, 2, 3, 4)))
                    if (invalid) throw AppBackupException(AppBackupError.InvalidFile)
                    return parsed
                }
                override suspend fun importBackup(backup: ParsedBackup): ImportResult {
                    imports.incrementAndGet()
                    assertSame(parsed, backup)
                    if (pending) awaitCancellation()
                    return ImportResult().record(BackupModelKind.CONTACTS, inserted = 17)
                        .record(BackupModelKind.MESSAGES, skipped = 23)
                        .record(BackupModelKind.CHANNELS, dropped = 3)
                        .record(BackupModelKind.DISCOVERED_NODES, dropped = 5)
                }
            },
            documents = object : BackupDocumentPort {
                override suspend fun save(suggestedName: String, data: ByteArray): BackupSaveOutcome = error("Import audit must not save")
                override suspend fun pick(): BackupPickOutcome {
                    picks.incrementAndGet()
                    return if (!picked) BackupPickOutcome.Cancelled else BackupPickOutcome.Picked(object : BackupSource {
                        override val sizeBytes = 4L
                        override suspend fun readBytes() = byteArrayOf(1, 2, 3, 4)
                    })
                }
            },
            connection = object : BackupConnectionGate { override val isRadioConnected = this@Rig.connected },
            effects = object : BackupImportEffects {
                override fun restoredDataChanged() { changed.incrementAndGet() }
                override fun channelSlotsAffected(slotsByRadio: Map<UUID, Set<Int>>) = error("Fixture restored no channel slots")
                override suspend fun refreshBlockedContactsCache() { refreshed.incrementAndGet() }
            },
            diagnostics = BackupDiagnostics { message, failure -> failures.add(message to failure) },
            strings = ResourceBackupStrings(host.get().resources),
        )
    }
}
