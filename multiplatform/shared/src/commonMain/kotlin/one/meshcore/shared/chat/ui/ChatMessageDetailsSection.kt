package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChatMessageActionDetails
import one.meshcore.shared.chat.RegionMatchResult
import one.meshcore.shared.chat.pathdetail.MessagePathDisplay
import one.meshcore.shared.map.snrQuality
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_collapsed
import one.meshcore.shared.resources.chats_message_action_details
import one.meshcore.shared.resources.chats_message_action_expanded
import one.meshcore.shared.resources.chats_message_action_path_details
import one.meshcore.shared.resources.chats_message_action_repeat_details
import one.meshcore.shared.resources.chats_message_hops_direct
import one.meshcore.shared.resources.chats_message_info_adjusted
import one.meshcore.shared.resources.chats_message_info_flooded_under
import one.meshcore.shared.resources.chats_message_info_heard_repeats
import one.meshcore.shared.resources.chats_message_info_hops
import one.meshcore.shared.resources.chats_message_info_original_send_time
import one.meshcore.shared.resources.chats_message_info_path_hash
import one.meshcore.shared.resources.chats_message_info_received
import one.meshcore.shared.resources.chats_message_info_region_ambiguous
import one.meshcore.shared.resources.chats_message_info_region_unresolved
import one.meshcore.shared.resources.chats_message_info_round_trip
import one.meshcore.shared.resources.chats_message_info_sent
import one.meshcore.shared.resources.chats_message_info_snr
import one.meshcore.shared.resources.chats_message_repeat_plural
import one.meshcore.shared.resources.chats_message_repeat_singular
import one.meshcore.shared.resources.chats_path_unavailable_description
import one.meshcore.shared.resources.chats_path_unavailable_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McActionSheetItem
import one.meshcore.shared.ui.menu.McActionSheetSectionHeader
import org.jetbrains.compose.resources.stringResource

/**
 * The "Details" block at the bottom of the DM/channel long-press sheet (iOS `ActionsDetailsSection`):
 * Path Details expands in place (capsules, compact map, hop list); Repeat Details opens a full-screen
 * overlay. Caption rows wait on [message] because the bubble footer does not carry every field.
 */
@Composable
fun ChatMessageDetailsSection(
    message: MessageDto?,
    modifier: Modifier = Modifier,
    pathPresentation: MessagePathPresentation? = null,
    selectedPathArrivalID: Uuid? = null,
    onSelectPathArrival: (Uuid) -> Unit = {},
    isNetworkOffline: Boolean = false,
    onOpenPathMap: ((Uuid?) -> Unit)? = null,
    onOpenRepeatDetails: (() -> Unit)? = null,
) {
    var pathExpanded by remember { mutableStateOf(false) }
    val pathIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(pathExpanded) {
        if (pathExpanded) {
            withFrameNanos { }
            pathIntoView.bringIntoView()
        }
    }
    Column(modifier) {
        if (onOpenPathMap != null) {
            McActionSheetItem(
                text = stringResource(Res.string.chats_message_action_path_details),
                leadingIcon = PATH_DETAILS_ICON,
                trailingIcon = AppIcons.ChevronRight,
                trailingRotationDegrees = if (pathExpanded) 90f else 0f,
                stateDescription = stringResource(
                    if (pathExpanded) {
                        Res.string.chats_message_action_expanded
                    } else {
                        Res.string.chats_message_action_collapsed
                    },
                ),
                onClick = { pathExpanded = !pathExpanded },
            )
            if (pathExpanded) {
                Column(Modifier.fillMaxWidth().bringIntoViewRequester(pathIntoView)) {
                    if (pathPresentation == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val selectedID = selectedPathArrivalID
                            ?: pathPresentation.arrivals.firstOrNull()?.arrival?.id
                        val display = pathPresentation.displayFor(selectedID)
                        MessagePathArrivalCapsules(
                            arrivals = pathPresentation.arrivals,
                            selectedID = selectedID,
                            onSelect = onSelectPathArrival,
                        )
                        MessagePathCompactPreview(
                            presentation = pathPresentation,
                            selectedArrivalID = selectedID,
                            isOffline = isNetworkOffline,
                            onExpand = { onOpenPathMap(selectedID) },
                        )
                        if (display.available) {
                            MessagePathHopList(
                                display = display,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.chats_path_unavailable_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(Res.string.chats_path_unavailable_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (onOpenRepeatDetails != null) {
            McActionSheetItem(
                text = stringResource(Res.string.chats_message_action_repeat_details),
                leadingIcon = REPEAT_DETAILS_ICON,
                onClick = onOpenRepeatDetails,
            )
        }
        if (message != null) {
            McActionSheetSectionHeader(stringResource(Res.string.chats_message_action_details))
            MessageDetailsCard {
                for (line in ChatMessageActionDetails.lines(message)) {
                    DetailRow(line)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(line: ChatMessageActionDetails.Line) {
    when (line) {
        is ChatMessageActionDetails.Line.Sent -> SentDetailRow(line)
        is ChatMessageActionDetails.Line.OriginalSendTime -> ActionInfoRow(
            text = formattedStringResource(
                Res.string.chats_message_info_original_send_time,
                formatMessageDetailDateTime(line.epochSeconds, formatter = LocalTimestampFormatter.current),
            ),
        )
        is ChatMessageActionDetails.Line.Received -> ActionInfoRow(
            text = formattedStringResource(
                Res.string.chats_message_info_received,
                formatMessageDetailDateTime(line.epochSeconds, formatter = LocalTimestampFormatter.current),
            ),
        )
        is ChatMessageActionDetails.Line.RoundTrip -> ActionInfoRow(
            text = formattedStringResource(Res.string.chats_message_info_round_trip, line.milliseconds.toInt()),
        )
        is ChatMessageActionDetails.Line.HeardRepeats -> HeardRepeatsDetailRow(line)
        is ChatMessageActionDetails.Line.Hops -> HopsDetailRow(line)
        is ChatMessageActionDetails.Line.PathHash -> ActionInfoRow(
            text = formattedStringResource(Res.string.chats_message_info_path_hash, line.hashSizeBytes),
        )
        is ChatMessageActionDetails.Line.FloodRegion -> FloodRegionDetailRow(line)
        is ChatMessageActionDetails.Line.Snr -> ActionInfoRow(
            text = formattedStringResource(Res.string.chats_message_info_snr, snrLabel(line.decibels)),
        )
    }
}

@Composable
private fun SentDetailRow(line: ChatMessageActionDetails.Line.Sent) {
    val sentText = formattedStringResource(
        Res.string.chats_message_info_sent,
        formatMessageDetailDateTime(line.epochSeconds, formatter = LocalTimestampFormatter.current),
    )
    val adjustedSuffix = if (line.adjusted) {
        " " + stringResource(Res.string.chats_message_info_adjusted)
    } else {
        ""
    }
    ActionInfoRow(text = sentText + adjustedSuffix)
}

@Composable
private fun HeardRepeatsDetailRow(line: ChatMessageActionDetails.Line.HeardRepeats) {
    val word = if (line.count == 1) {
        stringResource(Res.string.chats_message_repeat_singular)
    } else {
        stringResource(Res.string.chats_message_repeat_plural)
    }
    ActionInfoRow(
        text = formattedStringResource(Res.string.chats_message_info_heard_repeats, line.count, word),
    )
}

@Composable
private fun HopsDetailRow(line: ChatMessageActionDetails.Line.Hops) {
    val hopText = if (line.isDirect) {
        stringResource(Res.string.chats_message_hops_direct)
    } else {
        line.hopCount.toString()
    }
    ActionInfoRow(
        text = formattedStringResource(Res.string.chats_message_info_hops, hopText),
        icon = AppIcons.Route,
    )
}

@Composable
private fun FloodRegionDetailRow(line: ChatMessageActionDetails.Line.FloodRegion) {
    ActionInfoRow(
        text = when (val match = line.match) {
            RegionMatchResult.None -> stringResource(Res.string.chats_message_info_region_unresolved)
            is RegionMatchResult.Unique ->
                formattedStringResource(Res.string.chats_message_info_flooded_under, match.name)
            is RegionMatchResult.Ambiguous ->
                formattedStringResource(
                    Res.string.chats_message_info_region_ambiguous,
                    match.names.joinToString(", "),
                )
        },
        icon = AppIcons.Public,
    )
}

@Composable
private fun snrLabel(decibels: Double): String {
    val quality = snrQualityLabel(snrQuality(decibels))
    return messageDetailsSnrVisibleText(
        snrFormatted = formatSnrDb(decibels),
        unit = stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit),
        quality = quality,
    )
}

internal fun messageDetailsSnrVisibleText(snrFormatted: String, unit: String, quality: String): String =
    "$snrFormatted $unit ($quality)"

/** iOS Path Details disclosure: `point.topleft.down.to.point.bottomright.curvepath`. */
internal val PATH_DETAILS_ICON get() = AppIcons.Route

/** iOS Repeat Details title: `arrow.triangle.branch`. */
internal val REPEAT_DETAILS_ICON get() = AppIcons.Repeat

/** Path Map cannot stack over the actions sheet: the overlay is in-tree, the sheet is a dialog. */
internal fun shouldPresentPathMap(actionsSheetOpen: Boolean, display: MessagePathDisplay?): Boolean =
    !actionsSheetOpen && display != null
