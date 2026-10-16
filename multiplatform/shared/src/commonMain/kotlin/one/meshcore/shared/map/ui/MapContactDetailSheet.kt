package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.contact.heardOnAirSeconds
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.pathHopCount
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_add_to_favorites
import one.meshcore.shared.resources.contacts_detail_alert_delete_message
import one.meshcore.shared.resources.contacts_detail_alert_delete_title
import one.meshcore.shared.resources.contacts_detail_ping_failure_announcement
import one.meshcore.shared.resources.contacts_detail_ping_success_announcement
import one.meshcore.shared.resources.contacts_detail_remove_from_favorites
import one.meshcore.shared.resources.localizable_accessibility_requires_radio_connection
import one.meshcore.shared.resources.map_detail_hop_singular
import one.meshcore.shared.resources.map_detail_hops
import one.meshcore.shared.resources.map_detail_last_advert
import one.meshcore.shared.resources.map_detail_last_heard
import one.meshcore.shared.resources.map_detail_name
import one.meshcore.shared.resources.map_detail_path_length
import one.meshcore.shared.resources.map_detail_public_key
import one.meshcore.shared.resources.map_detail_routing
import one.meshcore.shared.resources.map_detail_routing_flood
import one.meshcore.shared.resources.map_detail_section_contact_info
import one.meshcore.shared.resources.map_detail_section_outbound_path
import one.meshcore.shared.resources.map_detail_type
import one.meshcore.shared.resources.map_node_kind_chat_contact
import one.meshcore.shared.resources.map_node_kind_repeater
import one.meshcore.shared.resources.map_node_kind_room
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.mapFavoriteToggleIcon
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.rememberAccessibilityAnnouncer
import org.jetbrains.compose.resources.stringResource

private val SHEET_H_PADDING = PreferenceHubHorizontalPadding
private val SHEET_V_PADDING = 8.dp
private val ACTION_GAP = 8.dp

/**
 * Saved-contact map details, ported from iOS `ContactDetailSheet`. Distinct from full Contact Detail:
 * last heard, favorite status, flood vs hop-count outbound path, and type-specific Telemetry /
 * Management / Join Room / Send Message, plus add/remove favorites, ping, and delete.
 */
@Composable
internal fun MapContactDetailSheet(
    contact: ContactDto,
    nowEpochSeconds: Long,
    isRadioReady: Boolean,
    isVContact: Boolean,
    contactService: () -> ContactService?,
    errorText: (Throwable) -> String,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onShowTelemetry: () -> Unit,
    onManagement: () -> Unit,
    onJoinRoom: () -> Unit,
    onZeroHopPing: (suspend (ContactDto) -> ContactPingResult)?,
) {
    val scope = rememberCoroutineScope()
    val typeLabel = mapNodeKindLabel(contact.contactType)
    val radioHint = mapContactRadioHint(
        isRadioReady = isRadioReady,
        hint = stringResource(Res.string.localizable_accessibility_requires_radio_connection),
    )
    val removeFavoriteLabel = stringResource(Res.string.contacts_detail_remove_from_favorites)
    val addFavoriteLabel = stringResource(Res.string.contacts_detail_add_to_favorites)
    var isFavorite by remember(contact.id) { mutableStateOf(contact.isFavorite) }
    var isTogglingFavorite by remember(contact.id) { mutableStateOf(false) }
    var isPinging by remember(contact.id) { mutableStateOf(false) }
    var pingResult by remember(contact.id) { mutableStateOf<ContactPingResult?>(null) }
    var isDeleting by remember(contact.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var errorMessage by remember(contact.id) { mutableStateOf<String?>(null) }
    val pingSuccessAnnouncement = stringResource(Res.string.contacts_detail_ping_success_announcement)
    val pingFailureAnnouncement = stringResource(Res.string.contacts_detail_ping_failure_announcement)
    val pingAnnouncer = rememberAccessibilityAnnouncer()
    LaunchedEffect(contact.isFavorite) { isFavorite = contact.isFavorite }
    LaunchedEffect(pingResult) {
        announcePingResult(
            result = pingResult,
            successTemplate = pingSuccessAnnouncement,
            failureMessage = pingFailureAnnouncement,
            announce = pingAnnouncer::announce,
        )
    }
    val onPing = {
        launchMapZeroHopPing(
            request = MapZeroHopPing(
                ping = onZeroHopPing,
                isPinging = isPinging,
                contact = contact,
                onStart = {
                    isPinging = true
                    pingResult = null
                },
                onResult = { result ->
                    pingResult = result
                    isPinging = false
                },
            ),
            scope = scope,
        )
        Unit
    }

    FullScreenOverlay(onDismiss = onDismiss, title = contact.displayName) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = SHEET_H_PADDING, vertical = SHEET_V_PADDING),
            verticalArrangement = Arrangement.spacedBy(ACTION_GAP),
        ) {
            Column(
                preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(ACTION_GAP),
            ) {
                PreferenceHubSection(
                    title = stringResource(Res.string.map_detail_section_contact_info),
                    applyHorizontalPadding = false,
                ) {
                    PreferenceHubValueRow(stringResource(Res.string.map_detail_name), contact.displayName)
                    PreferenceHubValueRow(stringResource(Res.string.map_detail_type), typeLabel)
                    MapFavoriteStatusRow(isFavorite = isFavorite)
                    MapTimestampRow(
                        label = stringResource(Res.string.map_detail_last_heard),
                        timestamp = contact.heardOnAirSeconds,
                        nowEpochSeconds = nowEpochSeconds,
                        skipNonPositive = false,
                    )
                    MapTimestampRow(
                        label = stringResource(Res.string.map_detail_last_advert),
                        timestamp = contact.lastAdvertTimestamp,
                        nowEpochSeconds = nowEpochSeconds,
                        skipNonPositive = true,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.map_detail_public_key)) },
                        supportingContent = {
                            Text(
                                text = spacedUppercaseHex(contact.publicKey),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                MapLocationSection(
                    hasLocation = contact.hasLocation,
                    latitude = contact.latitude,
                    longitude = contact.longitude,
                )

                val pathCopy = mapOutboundPathCopy(
                    isFloodRouted = contact.isFloodRouted,
                    hopCount = contact.pathHopCount,
                    labels = MapOutboundPathLabels(
                        routingLabel = stringResource(Res.string.map_detail_routing),
                        floodValue = stringResource(Res.string.map_detail_routing_flood),
                        pathLengthLabel = stringResource(Res.string.map_detail_path_length),
                        hopSingular = stringResource(Res.string.map_detail_hop_singular),
                        hopsTemplate = stringResource(Res.string.map_detail_hops),
                    ),
                )
                PreferenceHubSection(
                    title = stringResource(Res.string.map_detail_section_outbound_path),
                    applyHorizontalPadding = false,
                ) {
                    PreferenceHubValueRow(pathCopy.label, pathCopy.value)
                }
            }

            MapContactTypeActions(
                type = contact.contactType,
                isRadioReady = isRadioReady,
                radioHint = radioHint,
                isPinging = isPinging,
                pingResult = pingResult,
                onShowTelemetry = onShowTelemetry,
                onManagement = onManagement,
                onJoinRoom = onJoinRoom,
                onMessage = onMessage,
                onPing = onPing,
            )

            MapSheetActionButton(
                text = mapFavoriteActionText(
                    isFavorite = isFavorite,
                    removeLabel = removeFavoriteLabel,
                    addLabel = addFavoriteLabel,
                ),
                enabled = mapSheetActionEnabled(isRadioReady, isTogglingFavorite),
                radioHint = radioHint,
                outlined = true,
                leadingIcon = mapIconUnlessBusy(isTogglingFavorite, mapFavoriteToggleIcon(isFavorite)),
                trailing = MapFavoriteTrailing(isTogglingFavorite),
                onClick = {
                    val next = !isFavorite
                    launchMapContactAction(
                        apply = contactService()?.favoriteApply(contact.id, next),
                        scope = scope,
                        callbacks = MapActionCallbacks(
                            onStarted = { isTogglingFavorite = true },
                            onSuccess = { isFavorite = next },
                            onFailure = { error ->
                                errorMessage = errorText(error)
                            },
                            onFinished = { isTogglingFavorite = false },
                        ),
                    )
                },
            )

            MapDeleteContactButton(
                isVContact = isVContact,
                enabled = mapSheetActionEnabled(isRadioReady, isDeleting),
                radioHint = radioHint,
                isDeleting = isDeleting,
                onClick = { showDeleteConfirm = true },
            )
        }
    }

    MapDeleteConfirmDialog(
        show = showDeleteConfirm,
        title = formattedStringResource(Res.string.contacts_detail_alert_delete_title, typeLabel),
        message = formattedStringResource(
            Res.string.contacts_detail_alert_delete_message,
            contact.displayName,
        ),
        onDismiss = { showDeleteConfirm = false },
        onConfirm = {
            showDeleteConfirm = false
            launchMapContactAction(
                apply = contactService()?.deleteApply(contact.radioID, contact.publicKey, contact.id),
                scope = scope,
                callbacks = MapActionCallbacks(
                    onStarted = { isDeleting = true },
                    onSuccess = onDismiss,
                    onFailure = { error ->
                        errorMessage = errorText(error)
                    },
                    onFinished = { isDeleting = false },
                ),
            )
        },
    )
    MapContactErrorDialog(message = errorMessage, onDismiss = { errorMessage = null })
}

@Composable
private fun mapNodeKindLabel(type: ContactType): String = stringResource(
    when (type) {
        ContactType.CHAT -> Res.string.map_node_kind_chat_contact
        ContactType.REPEATER -> Res.string.map_node_kind_repeater
        ContactType.ROOM -> Res.string.map_node_kind_room
    },
)

@Composable
internal fun MapSheetActionButton(
    text: String,
    enabled: Boolean,
    radioHint: String?,
    onClick: () -> Unit,
    outlined: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .then(
            if (radioHint != null) {
                Modifier.semantics { stateDescription = radioHint }
            } else {
                Modifier
            },
        )
    val label: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ACTION_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(imageVector = leadingIcon, contentDescription = null)
            }
            Text(text)
            trailing?.invoke()
        }
    }
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, content = { label() })
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, content = { label() })
    }
}
