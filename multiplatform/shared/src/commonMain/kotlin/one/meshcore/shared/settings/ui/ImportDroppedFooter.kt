package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.contact.MAX_DISCOVERED_NODES_PER_RADIO
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_import_success_dropped_footer
import one.meshcore.shared.resources.settings_backup_import_success_dropped_footer_discovered_nodes
import one.meshcore.shared.resources.settings_backup_import_success_dropped_footer_mixed
import org.jetbrains.compose.resources.StringResource

/** A footer explanation and the substitutions it expects, so the two cannot be decided apart. */
data class ImportDroppedFooter(val resource: StringResource, val args: List<Any>)

/**
 * Which explanation the dropped-items footer gives, chosen by what was actually dropped. A restore that
 * lost data has to name the right reason: a full discover list and a radio with no free channel slot are
 * different problems with different fixes. Mirrors the Swift `droppedFooterText`.
 */
fun droppedFooter(result: BackupImportResult): ImportDroppedFooter = when {
    result.channelsDropped > 0 && result.discoveredNodesDropped > 0 ->
        ImportDroppedFooter(Res.string.settings_backup_import_success_dropped_footer_mixed, discoverCap)
    result.discoveredNodesDropped > 0 ->
        ImportDroppedFooter(Res.string.settings_backup_import_success_dropped_footer_discovered_nodes, discoverCap)
    else -> ImportDroppedFooter(Res.string.settings_backup_import_success_dropped_footer, emptyList())
}

/** The two discover-list footers name the cap that stopped the import, so the number is the fix. */
private val discoverCap = listOf(MAX_DISCOVERED_NODES_PER_RADIO)
