package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.contact.MAX_DISCOVERED_NODES_PER_RADIO
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_import_success_dropped_footer
import one.meshcore.shared.resources.settings_backup_import_success_dropped_footer_discovered_nodes
import one.meshcore.shared.resources.settings_backup_import_success_dropped_footer_mixed
import one.meshcore.shared.ui.formatTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A restore that lost data has to name the right reason. A full discover list and a radio with no free
 * channel slot are different problems, and only one of them is something the user can act on by freeing a
 * slot. Mirrors the Swift `droppedFooterText`.
 */
class ImportDroppedFooterTest {
    @Test
    fun aChannelSlotShortageKeepsTheSlotExplanation() {
        val footer = droppedFooter(BackupImportResult(channelsDropped = 2))

        assertEquals(Res.string.settings_backup_import_success_dropped_footer, footer.resource)
        assertEquals(emptyList(), footer.args)
    }

    @Test
    fun afullDiscoverListIsExplainedAsSuch() {
        val footer = droppedFooter(BackupImportResult(discoveredNodesDropped = 5))

        assertEquals(Res.string.settings_backup_import_success_dropped_footer_discovered_nodes, footer.resource)
        assertEquals(listOf(MAX_DISCOVERED_NODES_PER_RADIO), footer.args)
    }

    @Test
    fun bothCausesTogetherGetTheMixedExplanation() {
        val footer = droppedFooter(BackupImportResult(channelsDropped = 1, discoveredNodesDropped = 5))

        assertEquals(Res.string.settings_backup_import_success_dropped_footer_mixed, footer.resource)
        assertEquals(listOf(MAX_DISCOVERED_NODES_PER_RADIO), footer.args)
    }

    /**
     * Messages and reactions are dropped as a consequence of their channel being dropped, so they do not
     * change the explanation on their own.
     */
    @Test
    fun collateralDropsDoNotChangeTheExplanation() {
        val footer = droppedFooter(BackupImportResult(messagesDropped = 9, reactionsDropped = 3))

        assertEquals(Res.string.settings_backup_import_success_dropped_footer, footer.resource)
    }

    /** A footer carrying its own arguments is what keeps a literal "%d" off the restore summary. */
    @Test
    fun aCapFooterSubstitutesTheCapInsteadOfTheSpecifier() {
        val footer = droppedFooter(BackupImportResult(discoveredNodesDropped = 5))
        val text = formatTemplate("Only the first %d fit.", footer.args)

        assertEquals("Only the first $MAX_DISCOVERED_NODES_PER_RADIO fit.", text)
        assertTrue("%d" !in text)
    }

    /** The summary counts discovered nodes, so a restore that drops only those must still show a section. */
    @Test
    fun discoveredNodesCountTowardTheSummary() {
        assertEquals(5, BackupImportResult(discoveredNodesDropped = 5).totalDropped)
    }
}
