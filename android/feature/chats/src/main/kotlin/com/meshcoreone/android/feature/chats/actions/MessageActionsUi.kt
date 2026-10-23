// PortedFrom: MC1/Views/Chats/Reactions/MessageActionsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Reactions/ReactionBadgesView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Reactions/ReactionDetailsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/MessagePathContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/PathHopRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/MessagePathDetailBlock.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/MessagePathMapView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/RepeatRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/FallbackMatchIndicatorView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/BlockSenderSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.actions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapAvailability
import com.meshcoreone.android.core.maps.MapLibreMapSurface
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.maps.MapStyle
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.TimestampFormatting
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.delay

@Composable
fun MessageActionsRoute(
    message: MessageDTO,
    holder: MessageActionsStateHolder,
    localDevice: PathEndpoint?,
    userLocation: Coordinate?,
    recentEmojis: List<String>,
    onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone(),
    localDeviceName: String? = localDevice?.name,
) {
    val state by holder.state.collectAsStateWithLifecycle()
    LaunchedEffect(holder, message.id) { holder.loadDetails(message) }
    MessageActionsContent(
        message,
        state,
        localDevice,
        userLocation,
        recentEmojis,
        onAction,
        holder::loadReactions,
        holder::selectEmoji,
        holder::blockSender,
        holder::setMuted,
        modifier,
        clock,
        localDeviceName,
    )
}

@Composable
fun MessageActionsContent(
    message: MessageDTO,
    state: MessageActionsState,
    localDevice: PathEndpoint?,
    userLocation: Coordinate?,
    recentEmojis: List<String>,
    onAction: (MessageAction) -> Unit,
    onLoadReactions: (UUID, String?) -> Unit,
    onSelectReaction: (String) -> Unit,
    onBlockSender: (MessageDTO, Set<UUID>) -> Unit,
    onSetMuted: (ContactDTO, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone(),
    localDeviceName: String? = localDevice?.name,
) {
    val availability = remember(message) { MessageActionAvailability.forMessage(message) }
    var selectedArrivalId by rememberSaveable(message.id) { mutableStateOf<UUID?>(null) }
    var showMap by rememberSaveable(message.id) { mutableStateOf(false) }
    var showReactionDetails by rememberSaveable(message.id) { mutableStateOf(false) }
    var preferredReaction by rememberSaveable(message.id) { mutableStateOf<String?>(null) }
    var showBlockConfirmation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(message.id, showReactionDetails) {
        if (showReactionDetails) onLoadReactions(message.id, preferredReaction)
    }
    val arrivals = remember(message, state.repeats) {
        MessagePathArrivals.assemble(message, state.repeats.orEmpty())
    }
    val senderContact = remember(message, state.directory) {
        state.directory.senderContact(message)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val expanded = maxWidth >= 600.dp
        val content: @Composable () -> Unit = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                ReactionBadges(
                    summary = message.reactionSummary,
                    onSelect = { emoji ->
                        preferredReaction = emoji
                        showReactionDetails = true
                    },
                )
                RecentReactionActions(recentEmojis) { onAction(MessageAction.React(it)) }
                HorizontalDivider()
                ActionButtons(availability, onAction)
                if (availability.showsPathDetail) {
                    HorizontalDivider()
                    PathDetails(
                        message,
                        arrivals,
                        selectedArrivalId,
                        { selectedArrivalId = it },
                        state,
                        localDevice,
                        userLocation,
                        showMap,
                        { showMap = it },
                        localDeviceName,
                    )
                }
                MessageMetadata(message, arrivals.size)
                if (availability.canBlockSender) {
                    TextButton(
                        onClick = { showBlockConfirmation = true },
                        modifier = Modifier.sharedTouchTarget(),
                    ) {
                        Text(
                            stringResource(AppChatsStrings.chatsMessageActionBlockSender),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                senderContact?.let { contact ->
                    val muted = contact.id in state.mutedContactIds
                    TextButton(
                        onClick = { onSetMuted(contact, !muted) },
                        modifier = Modifier.sharedTouchTarget(),
                    ) {
                        Text(
                            stringResource(
                                if (muted) AppChatsStrings.chatsActionUnmute
                                else AppChatsStrings.chatsActionMute,
                            ),
                        )
                    }
                }
                TextButton(
                    onClick = { onAction(MessageAction.Delete) },
                    modifier = Modifier.sharedTouchTarget(),
                ) {
                    Text(
                        stringResource(AppChatsStrings.chatsMessageActionDelete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (expanded && availability.showsPathDetail && showMap) {
            Row(Modifier.fillMaxWidth().heightIn(min = 480.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    PathMap(
                        message,
                        arrivals,
                        selectedArrivalId,
                        state.directory,
                        localDevice,
                        userLocation,
                    )
                }
            }
        } else {
            content()
        }
    }
    if (showReactionDetails) {
        ReactionDetailsDialog(
            state,
            onSelectReaction,
            onDismiss = { showReactionDetails = false },
            onRetry = { onLoadReactions(message.id, preferredReaction) },
            clock = clock,
        )
    }
    if (showBlockConfirmation) {
        BlockSenderDialog(
            senderName = message.senderNodeName.orEmpty(),
            contacts = state.directory.contacts,
            onConfirm = {
                onBlockSender(message, it)
                showBlockConfirmation = false
            },
            onDismiss = { showBlockConfirmation = false },
        )
    }
}

@Composable
fun ReactionBadges(
    summary: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reactions = remember(summary) { parseReactionSummary(summary) }
    if (reactions.isEmpty()) return
    Row(modifier.horizontalScroll(rememberScrollState())) {
        reactions.take(3).forEach { reaction ->
            val label = "${reaction.emoji}, ${reaction.count}"
            TextButton(
                onClick = { onSelect(reaction.emoji) },
                modifier = Modifier.sharedTouchTarget().semantics { contentDescription = label },
            ) {
                Text(if (reaction.count > 1) "${reaction.emoji} ${reaction.count}" else reaction.emoji)
            }
        }
        val overflow = reactions.size - 3
        if (overflow > 0) {
            TextButton(
                onClick = { onSelect(null) },
                modifier = Modifier.sharedTouchTarget(),
            ) {
                Text("+$overflow")
            }
        }
    }
}

@Composable
private fun RecentReactionActions(emojis: List<String>, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        emojis.forEach { emoji ->
            TextButton(
                onClick = { onSelect(emoji) },
                modifier = Modifier.sharedTouchTarget(),
            ) {
                Text(emoji, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun ActionButtons(availability: MessageActionAvailability, onAction: (MessageAction) -> Unit) {
    Column {
        if (availability.canReply) {
            MessageActionButton(stringResource(AppChatsStrings.chatsMessageActionReply)) { onAction(MessageAction.Reply) }
        }
        if (availability.canSendDirectMessage) {
            MessageActionButton(stringResource(AppChatsStrings.chatsMessageActionSendDM)) {
                onAction(MessageAction.SendDirectMessage)
            }
        }
        MessageActionButton(stringResource(AppChatsStrings.chatsMessageActionCopy)) { onAction(MessageAction.Copy) }
        if (availability.canSendAgain) {
            MessageActionButton(stringResource(AppChatsStrings.chatsMessageActionSendAgain)) {
                onAction(MessageAction.SendAgain)
            }
        }
    }
}

@Composable
private fun MessageActionButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().sharedTouchTarget(),
    ) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PathDetails(
    message: MessageDTO,
    arrivals: List<MessagePathArrival>,
    selectedArrivalId: UUID?,
    onSelectArrival: (UUID) -> Unit,
    state: MessageActionsState,
    localDevice: PathEndpoint?,
    userLocation: Coordinate?,
    showMap: Boolean,
    onShowMap: (Boolean) -> Unit,
    localDeviceName: String?,
) {
    Text(
        stringResource(AppChatsStrings.chatsMessageActionPathDetails),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    if (state.isLoadingDetails) {
        CircularProgressIndicator(Modifier.size(48.dp))
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onShowMap(false) }, modifier = Modifier.sharedTouchTarget()) {
            Text(stringResource(AppChatsStrings.chatsMessageActionDetails))
        }
        OutlinedButton(onClick = { onShowMap(true) }, modifier = Modifier.sharedTouchTarget()) {
            Text(stringResource(AppChatsStrings.chatsPathMap))
        }
    }
    if (arrivals.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            arrivals.forEachIndexed { index, arrival ->
                val selected = MessagePathArrivals.resolvedSelection(selectedArrivalId, arrivals) == arrival.id
                Text(
                    if (arrival.isFirst) stringResource(AppChatsStrings.chatsPathArrivalFirst) else "#${index + 1}",
                    modifier = Modifier
                        .selectable(selected, role = Role.Tab) { onSelectArrival(arrival.id) }
                        .sharedTouchTarget()
                        .padding(horizontal = 12.dp)
                        .semantics { this.selected = selected },
                )
            }
        }
    }
    val selected = arrivals.firstOrNull {
        it.id == MessagePathArrivals.resolvedSelection(selectedArrivalId, arrivals)
    }
    if (showMap) {
        Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
            PathMap(message, arrivals, selectedArrivalId, state.directory, localDevice, userLocation)
        }
    } else if (selected != null) {
        PathHopList(message, selected, state.directory, userLocation, localDeviceName)
    }
    if (state.repeats != null && state.repeats.isNotEmpty()) {
        Text(
            stringResource(AppChatsStrings.chatsMessageActionRepeatDetails),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        state.repeats.forEach { RepeatRow(it, state.directory, userLocation) }
    }
}

@Composable
private fun PathHopList(
    message: MessageDTO,
    arrival: MessagePathArrival,
    directory: MessagePathDirectory,
    userLocation: Coordinate?,
    localDeviceName: String?,
) {
    if (arrival.isPathUnavailable) {
        Text(stringResource(AppChatsStrings.chatsPathUnavailableDescription))
        return
    }
    if (arrival.isZeroHop) {
        Text(stringResource(AppChatsStrings.chatsMessagePathFlood))
    }
    val receiverName = localDeviceName ?: stringResource(AppChatsStrings.chatsPathReceiverYou)
    PathEndpointRow(
        label = stringResource(AppChatsStrings.chatsPathHopSender),
        resolution = directory.senderResolution(
            message, receiverName, stringResource(AppChatsStrings.chatsPathHopUnknown),
        ),
        nodeId = directory.senderNodeId(message),
    )
    arrival.pathHops.forEachIndexed { index, hop ->
        val resolution = PathNodeResolver.repeatResolution(
            hop.data,
            directory.repeaters,
            directory.discoveredRepeaters,
            userLocation,
            stringResource(AppChatsStrings.chatsPathHopUnknown),
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${index + 1}. ${hop.hex}", style = MaterialTheme.typography.bodyMedium)
            Text(resolution.displayName, style = MaterialTheme.typography.bodyMedium)
            if (resolution.isFallback) {
                Text(stringResource(AppChatsStrings.chatsPathHopPossibleMatch), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    PathEndpointRow(
        label = stringResource(AppChatsStrings.chatsPathReceiverLabel),
        resolution = NodeNameResolution(receiverName, NodeNameMatchKind.EXACT),
        snr = arrival.snr,
    )
    if (arrival.pathHops.isNotEmpty()) {
        val clipboard = LocalClipboardManager.current
        val copyLabel = stringResource(AppChatsStrings.chatsPathCopyAccessibility)
        val copyHint = stringResource(AppChatsStrings.chatsPathCopyHint)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(arrival.pathStringForClipboard)) },
                modifier = Modifier.sharedTouchTarget().semantics {
                    contentDescription = copyLabel
                    onClick(label = copyHint, action = null)
                },
            ) {
                Text(stringResource(AppChatsStrings.chatsPathCopyButton))
            }
            Text(
                arrival.pathString,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PathEndpointRow(
    label: String,
    resolution: NodeNameResolution,
    nodeId: String? = null,
    snr: Double? = null,
) {
    val resources = LocalResources.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(resolution.displayName, style = MaterialTheme.typography.bodyMedium)
        nodeId?.let {
            Text(AppChatsStrings.chatsPathHopNodeId(resources, it), style = MaterialTheme.typography.bodySmall)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        snr?.let {
            val quality = stringResource(
                when (MessageSignalQuality.of(it)) {
                    MessageSignalQuality.EXCELLENT -> AppChatsStrings.chatsSignalExcellent
                    MessageSignalQuality.GOOD -> AppChatsStrings.chatsSignalGood
                    MessageSignalQuality.FAIR -> AppChatsStrings.chatsSignalFair
                    MessageSignalQuality.POOR -> AppChatsStrings.chatsSignalPoor
                    MessageSignalQuality.UNKNOWN -> AppChatsStrings.chatsPathHopSignalUnknown
                },
            )
            val formatted = String.format(resources.configuration.locales[0], "%.1f", it)
            Text(
                AppChatsStrings.chatsPathHopSignalQuality(resources, quality, formatted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (resolution.isFallback) {
            var showExplanation by rememberSaveable(resolution) { mutableStateOf(false) }
            val explanation = stringResource(AppChatsStrings.chatsPathHopPossibleMatchExplanation)
            TextButton(
                onClick = { showExplanation = true },
                modifier = Modifier.sharedTouchTarget().semantics { stateDescription = explanation },
            ) {
                Text(stringResource(AppChatsStrings.chatsPathHopPossibleMatch))
            }
            if (showExplanation) {
                AlertDialog(
                    onDismissRequest = { showExplanation = false },
                    title = { Text(stringResource(AppChatsStrings.chatsPathHopPossibleMatchTitle)) },
                    text = { Text(explanation, Modifier.verticalScroll(rememberScrollState())) },
                    confirmButton = {
                        TextButton(onClick = { showExplanation = false }, modifier = Modifier.sharedTouchTarget()) {
                            Text(stringResource(AppLocalizableStrings.commonDone))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PathMap(
    message: MessageDTO,
    arrivals: List<MessagePathArrival>,
    selectedArrivalId: UUID?,
    directory: MessagePathDirectory,
    localDevice: PathEndpoint?,
    userLocation: Coordinate?,
) {
    val canvas = remember(message, arrivals, selectedArrivalId, directory, localDevice, userLocation) {
        MessagePathCanvasBuilder.build(
            message,
            arrivals,
            selectedArrivalId,
            directory,
            localDevice,
            userLocation,
        )
    }
    if (!canvas.showsPathMap) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(
                    if (canvas.hopCount == 0) AppChatsStrings.chatsPathUnavailableTitle
                    else AppChatsStrings.chatsPathUnplaceableTitle,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    if (canvas.hopCount == 0) AppChatsStrings.chatsPathUnavailableDescription
                    else AppChatsStrings.chatsPathUnplaceableDescription,
                ),
            )
        }
        return
    }
    val presentation = MapPresentationState(
        availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
        style = MapStyle.STANDARD,
        camera = canvas.camera,
        points = canvas.points,
        lines = canvas.lines,
        isInteractive = true,
    )
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapLibreMapSurface(
                presentation = presentation,
                clusteringEnabled = false,
                labelsEnabled = true,
                accessibilityLabel = stringResource(AppChatsStrings.chatsPathAccessibilityViewOnMap),
                onCameraChanged = {},
                onMarkerSelected = {},
            )
        }
        val distance = canvas.totalDistanceMeters?.let { "${it.toLong()} m" }
        if (distance != null) {
            Text(
                buildString {
                    append(distance)
                    if (canvas.isDistanceIncomplete) append(" · ").append(
                        stringResource(AppChatsStrings.chatsPathDistanceIncomplete),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Text(
            presentation.attribution.joinToString(" · ") { it.label },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun RepeatRow(
    repeat: MessageRepeatDTO,
    directory: MessagePathDirectory,
    userLocation: Coordinate?,
) {
    val resources = LocalResources.current
    val unknown = stringResource(AppChatsStrings.chatsRepeatsUnknownRepeater)
    val resolution = remember(repeat, directory, userLocation, unknown) {
        PathNodeResolver.repeatResolution(
            repeat.repeaterHash,
            directory.repeaters,
            directory.discoveredRepeaters,
            userLocation,
            unknown,
        )
    }
    val quality = MessageSignalQuality.of(repeat.snr)
    val hops = if (repeat.hopCount == 1L) {
        stringResource(AppChatsStrings.chatsRepeatsHopSingular)
    } else {
        AppChatsStrings.chatsRepeatsHopPlural(resources, repeat.hopCount.toInt())
    }
    val spoken = "${resolution.displayName}. ${quality.qualityLabel}. SNR ${repeat.snrFormatted()}. RSSI ${repeat.rssiFormatted}"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${repeat.repeaterHashFormatted}  ${resolution.displayName}")
            Text(hops, style = MaterialTheme.typography.labelSmall)
            if (resolution.isFallback) {
                var showExplanation by rememberSaveable(repeat.id) { mutableStateOf(false) }
                val label = stringResource(AppChatsStrings.chatsPathHopPossibleMatch)
                val explanation = stringResource(AppChatsStrings.chatsPathHopPossibleMatchExplanation)
                TextButton(
                    onClick = { showExplanation = true },
                    modifier = Modifier.sharedTouchTarget().semantics {
                        contentDescription = label
                        stateDescription = explanation
                    },
                ) {
                    Text(stringResource(AppChatsStrings.chatsPathHopPossibleMatchTitle))
                }
                if (showExplanation) {
                    AlertDialog(
                        onDismissRequest = { showExplanation = false },
                        title = { Text(stringResource(AppChatsStrings.chatsPathHopPossibleMatchTitle)) },
                        text = { Text(explanation, Modifier.verticalScroll(rememberScrollState())) },
                        confirmButton = {
                            TextButton(
                                onClick = { showExplanation = false },
                                modifier = Modifier.sharedTouchTarget(),
                            ) {
                                Text(stringResource(AppLocalizableStrings.commonDone))
                            }
                        },
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(quality.qualityLabel)
            Text("SNR ${repeat.snrFormatted()}", style = MaterialTheme.typography.labelSmall)
            Text("RSSI ${repeat.rssiFormatted}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MessageMetadata(message: MessageDTO, arrivals: Int) {
    Text(
        stringResource(AppChatsStrings.chatsMessageActionDetails),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    val timestamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(message.senderDate)
    Text(timestamp)
    if (!message.isOutgoing) {
        Text("${message.hopCount} hops")
        message.snr?.let {
            val quality = MessageSignalQuality.of(it)
            Text("SNR ${"%.1f".format(it)} dB (${quality.qualityLabel})${if (arrivals > 1) " · first" else ""}")
        }
    } else if (message.heardRepeats > 0) {
        Text("${message.heardRepeats} ${stringResource(AppChatsStrings.chatsMessageRepeatPlural)}")
    }
}

@Composable
private fun ReactionDetailsDialog(
    state: MessageActionsState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    clock: Clock,
) {
    val resources = LocalResources.current
    val now by produceState(clock.instant(), clock) {
        while (true) {
            delay(60_000L - Math.floorMod(clock.instant().toEpochMilli(), 60_000L))
            value = clock.instant()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppChatsStrings.reactionsTitle)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                if (state.isLoadingReactions) {
                    CircularProgressIndicator(Modifier.size(48.dp))
                } else if (state.reactionFailure != null || state.isReactionLoadCancelled) {
                    if (state.reactionFailure != null) {
                        Text(stringResource(AppLocalizableStrings.commonErrorFailedToLoad))
                    }
                    TextButton(onClick = onRetry, modifier = Modifier.sharedTouchTarget()) {
                        Text(stringResource(AppLocalizableStrings.commonTryAgain))
                    }
                } else if (state.reactionGroups.isEmpty()) {
                    Text(
                        stringResource(AppChatsStrings.reactionsEmptyStateTitle),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(AppChatsStrings.reactionsEmptyStateDescription), Modifier.padding(vertical = 16.dp))
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        state.reactionGroups.forEach { group ->
                            val selected = state.selectedEmoji == group.emoji
                            Text(
                                "${group.emoji} ${group.count}",
                                modifier = Modifier
                                    .selectable(selected, role = Role.Tab) { onSelect(group.emoji) }
                                    .sharedTouchTarget()
                                    .padding(horizontal = 12.dp)
                                    .semantics { this.selected = selected },
                            )
                        }
                    }
                    HorizontalDivider()
                    val selected = state.reactionGroups.firstOrNull { it.emoji == state.selectedEmoji }
                    if (selected != null) {
                        LazyColumn {
                            items(selected.reactions, key = { it.id }) { reaction ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                    Text(reaction.senderName)
                                    Text(
                                        reactionRelativeTime(reaction.receivedAt, now, resources.configuration.locales[0], clock.zone),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sharedTouchTarget()) {
                Text(stringResource(AppLocalizableStrings.commonDone))
            }
        },
    )
}

internal fun reactionRelativeTime(date: Instant, now: Instant, locale: java.util.Locale, zone: ZoneId): String {
    val component = TimestampFormatting(locale, zone).relativeComponent(date, now)
    return android.icu.text.RelativeDateTimeFormatter.getInstance(
        android.icu.util.ULocale.forLocale(locale), null,
        android.icu.text.RelativeDateTimeFormatter.Style.LONG,
        android.icu.text.DisplayContext.CAPITALIZATION_NONE,
    ).format(component.amount.toDouble(), component.unit)
}

@Composable
private fun BlockSenderDialog(
    senderName: String,
    contacts: List<ContactDTO>,
    onConfirm: (Set<UUID>) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    var selected by rememberSaveable { mutableStateOf(emptySet<UUID>()) }
    val matching = remember(contacts, senderName) {
        contacts.filter { !it.isBlocked && it.name.equals(senderName, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppChatsStrings.chatsBlockSenderTitle(resources, senderName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(AppChatsStrings.chatsBlockSenderLimitation))
                if (matching.isNotEmpty()) {
                    Text(
                        stringResource(AppChatsStrings.chatsBlockSenderMatchingContacts),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    matching.forEach { contact ->
                        val checked = contact.id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(checked, role = Role.Checkbox) {
                                    selected = if (it) selected + contact.id else selected - contact.id
                                }
                                .sharedTouchTarget()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (checked) "\u2611" else "\u2610")
                            Spacer(Modifier.width(12.dp))
                            Text(contact.displayName)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }, modifier = Modifier.sharedTouchTarget()) {
                Text(stringResource(AppChatsStrings.chatsBlockSenderBlockAnyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sharedTouchTarget()) {
                Text(stringResource(AppChatsStrings.chatsBlockSenderCancel))
            }
        },
    )
}

@Composable
fun DeliveryFeedbackText(message: MessageDTO, modifier: Modifier = Modifier) {
    val resource = when (DeliveryFeedback.from(message)) {
        DeliveryFeedback.SENDING -> AppChatsStrings.chatsMessageStatusSending
        DeliveryFeedback.SENT -> AppChatsStrings.chatsMessageStatusSent
        DeliveryFeedback.DELIVERED -> AppChatsStrings.chatsMessageStatusDelivered
        DeliveryFeedback.FAILED -> AppChatsStrings.chatsMessageStatusFailed
        DeliveryFeedback.RETRYING -> AppChatsStrings.chatsMessageStatusRetrying
    }
    Text(
        stringResource(resource),
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.semantics { contentDescription = "" },
    )
}
