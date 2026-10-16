package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.tools_rx_log
import one.meshcore.shared.resources.tools_rx_log_decrypt_status
import one.meshcore.shared.resources.tools_rx_log_delete
import one.meshcore.shared.resources.tools_rx_log_delete_confirmation
import one.meshcore.shared.resources.tools_rx_log_delete_logs
import one.meshcore.shared.resources.tools_rx_log_filter
import one.meshcore.shared.resources.tools_rx_log_group_duplicates
import one.meshcore.shared.resources.tools_rx_log_listening
import one.meshcore.shared.resources.tools_rx_log_listening_description
import one.meshcore.shared.resources.tools_rx_log_more
import one.meshcore.shared.resources.tools_rx_log_not_connected
import one.meshcore.shared.resources.tools_rx_log_not_connected_description
import one.meshcore.shared.resources.tools_rx_log_packets_count
import one.meshcore.shared.resources.tools_rx_log_route_type
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.tools.RxLogDecryptFilter
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogRouteFilter
import one.meshcore.shared.tools.RxLogStore
import one.meshcore.shared.tools.clearRxLogEntriesGuarded
import one.meshcore.shared.tools.displayRxLogEntries
import one.meshcore.shared.tools.filterRxLogEntries
import one.meshcore.shared.tools.rxLogGroupCounts
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuDivider
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuRole
import one.meshcore.shared.ui.menu.McMenuSectionHeader
import org.jetbrains.compose.resources.stringResource

/** Live only when a session is Ready. A last-radio store is not Listening. */
fun rxLogIsLive(storePresent: Boolean, isRadioReady: Boolean): Boolean = storePresent && isRadioReady

/** True when route and decrypt filters are both All, so the filter icon stays unemphasized. */
internal fun rxLogFilterIsDefault(route: RxLogRouteFilter, decrypt: RxLogDecryptFilter): Boolean =
    route == RxLogRouteFilter.ALL && decrypt == RxLogDecryptFilter.ALL

/** iOS `liveStatusHeader` combines the pill and packet count into one VoiceOver label. */
internal fun rxLogLiveStatusAnnouncement(statusLabel: String, packetsLabel: String): String =
    "$statusLabel, $packetsLabel"

/**
 * Tools → RX Log: live list with route/decrypt filters, expand-per-row detail (RSSI/SNR/path/from-to/
 * raw hex + copy), group-duplicates, and live/offline status. Mirrors iOS `RxLogView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxLogScreen(
    store: RxLogStore?,
    radioId: () -> Uuid?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    localPublicKeyPrefix: ByteArray? = null,
    nodeNames: Map<String, String> = emptyMap(),
    isRadioReady: Boolean = false,
) {
    val emptySnapshot = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<RxLogEntry>()) }
    val all by (store?.snapshot ?: emptySnapshot).collectAsStateWithLifecycle()
    val radio = radioId()
    val radioEntries = remember(all, radio) {
        if (radio == null) emptyList() else all.filter { it.radioID == radio }
    }
    var routeFilter by remember { mutableStateOf(RxLogRouteFilter.ALL) }
    var decryptFilter by remember { mutableStateOf(RxLogDecryptFilter.ALL) }
    var groupDuplicates by remember { mutableStateOf(false) }
    var expandedHashes by remember { mutableStateOf(setOf<String>()) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val filtered = remember(radioEntries, routeFilter, decryptFilter) {
        filterRxLogEntries(radioEntries, routeFilter, decryptFilter)
    }
    val displayEntries = remember(filtered, groupDuplicates) {
        displayRxLogEntries(filtered, groupDuplicates)
    }
    // Counted across every loaded row, not the filtered view, so a decrypt filter cannot make a
    // packet look like it was heard fewer times than it was.
    val groupCounts = remember(radioEntries, groupDuplicates) {
        if (groupDuplicates) rxLogGroupCounts(radioEntries) else emptyMap()
    }
    val scope = rememberCoroutineScope()
    val packetsTemplate = stringResource(Res.string.tools_rx_log_packets_count)
    val isLive = rxLogIsLive(store != null, isRadioReady)

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.tools_rx_log),
                onBack = onBack,
                actions = {
                    Box {
                        AppIconButton(
                            imageVector = AppIcons.FilterList,
                            contentDescription = stringResource(Res.string.tools_rx_log_filter),
                            onClick = { filterMenuOpen = true },
                            tint = if (rxLogFilterIsDefault(routeFilter, decryptFilter)) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        McDropdownMenu(
                            expanded = filterMenuOpen,
                            onDismissRequest = { filterMenuOpen = false },
                        ) {
                            McMenuSectionHeader(text = stringResource(Res.string.tools_rx_log_route_type))
                            RouteFilterItem(RxLogRouteFilter.ALL, routeFilter) {
                                routeFilter = it
                                filterMenuOpen = false
                            }
                            RouteFilterItem(RxLogRouteFilter.FLOOD_ONLY, routeFilter) {
                                routeFilter = it
                                filterMenuOpen = false
                            }
                            RouteFilterItem(RxLogRouteFilter.DIRECT_ONLY, routeFilter) {
                                routeFilter = it
                                filterMenuOpen = false
                            }
                            McMenuDivider()
                            McMenuSectionHeader(text = stringResource(Res.string.tools_rx_log_decrypt_status))
                            DecryptFilterItem(RxLogDecryptFilter.ALL, decryptFilter) {
                                decryptFilter = it
                                filterMenuOpen = false
                            }
                            DecryptFilterItem(RxLogDecryptFilter.DECRYPTED, decryptFilter) {
                                decryptFilter = it
                                filterMenuOpen = false
                            }
                            DecryptFilterItem(RxLogDecryptFilter.FAILED, decryptFilter) {
                                decryptFilter = it
                                filterMenuOpen = false
                            }
                        }
                    }
                    Box {
                        AppIconButton(
                            imageVector = AppIcons.MoreVert,
                            contentDescription = stringResource(Res.string.tools_rx_log_more),
                            onClick = { moreMenuOpen = true },
                        )
                        McDropdownMenu(
                            expanded = moreMenuOpen,
                            onDismissRequest = { moreMenuOpen = false },
                        ) {
                            McMenuItem(
                                text = stringResource(Res.string.tools_rx_log_group_duplicates),
                                leadingIcon = AppIcons.ListAlt,
                                selected = groupDuplicates,
                                onClick = {
                                    groupDuplicates = !groupDuplicates
                                    moreMenuOpen = false
                                },
                            )
                            McMenuItem(
                                text = stringResource(Res.string.tools_rx_log_delete_logs),
                                leadingIcon = AppIcons.Delete,
                                role = McMenuRole.Destructive,
                                onClick = {
                                    moreMenuOpen = false
                                    showClearConfirm = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!isLive) {
            // No radio means no decrypt material and no live packets; saying "listening" here would be
            // a live-looking screen that can never fill.
            RxLogPlaceholder(
                title = stringResource(Res.string.tools_rx_log_not_connected),
                description = stringResource(Res.string.tools_rx_log_not_connected_description),
                icon = AppIcons.CellTowerOff,
                modifier = Modifier.padding(innerPadding),
            )
        } else if (radioEntries.isEmpty()) {
            RxLogPlaceholder(
                title = stringResource(Res.string.tools_rx_log_listening),
                description = stringResource(Res.string.tools_rx_log_listening_description),
                icon = AppIcons.CellTower,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                item {
                    // The count is every loaded packet, matching iOS; a filter narrows the list below it
                    // without rewriting the total the operator is tracking.
                    LiveStatusHeader(
                        isLive = isLive,
                        packetsLabel = formatTemplate(packetsTemplate, listOf(radioEntries.size)),
                    )
                }
                items(displayEntries, key = { it.id.value }) { entry ->
                    val expanded = entry.packetHash in expandedHashes
                    RxLogExpandableRow(
                        entry = entry,
                        expanded = expanded,
                        groupCount = groupCounts[entry.packetHash] ?: 1,
                        localPublicKeyPrefix = localPublicKeyPrefix,
                        nodeNames = nodeNames,
                        onToggle = {
                            expandedHashes = if (expanded) {
                                expandedHashes - entry.packetHash
                            } else {
                                expandedHashes + entry.packetHash
                            }
                        },
                    )
                }
            }
        }
    }

    if (showClearConfirm && store != null && radio != null) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(Res.string.tools_rx_log_delete_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        expandedHashes = emptySet()
                        scope.launch {
                            clearRxLogEntriesGuarded(
                                store = store,
                                radioID = radio,
                                logger = PersistentLogger(scope, category = "RxLogService"),
                            )
                        }
                    },
                ) {
                    Text(stringResource(Res.string.tools_rx_log_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(Res.string.contacts_common_cancel))
                }
            },
        )
    }
}

internal const val PATH_TRUNCATION_THRESHOLD = 6
internal const val PATH_TRUNCATION_ENDS = 3

/** Flood uses tertiary, Direct uses primary — same pairing as SNR fair vs excellent. */
internal enum class RxLogRouteRole {
    PRIMARY,
    TERTIARY,
}

internal fun rxLogRouteRole(isFlood: Boolean): RxLogRouteRole =
    if (isFlood) RxLogRouteRole.TERTIARY else RxLogRouteRole.PRIMARY

@Composable
internal fun rxLogRouteTint(isFlood: Boolean): Color = when (rxLogRouteRole(isFlood)) {
    RxLogRouteRole.PRIMARY -> MaterialTheme.colorScheme.primary
    RxLogRouteRole.TERTIARY -> MaterialTheme.colorScheme.tertiary
}

internal fun rxLogRssiVisibleText(rssi: Int, unit: String): String = "$rssi $unit"

internal fun rxLogPayloadTypePreview(typeName: String, version: Int, size: Int, bytesLabel: String): String {
    val versionSuffix = if (version > 0) " v$version" else ""
    return "$typeName$versionSuffix, $size $bytesLabel"
}

/** iOS `RawPayloadSection` icon-only Copy: `doc.on.doc`, then `checkmark`. */
internal fun rxLogCopyActionIcon(copied: Boolean) = if (copied) AppIcons.Check else AppIcons.ContentCopy
