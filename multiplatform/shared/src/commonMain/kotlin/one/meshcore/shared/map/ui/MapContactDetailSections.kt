package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ui.conversationTimestampLabel
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ui.PingResultRow
import one.meshcore.shared.map.formatFixed6
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_common_delete
import one.meshcore.shared.resources.contacts_common_error
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.resources.contacts_detail_ping
import one.meshcore.shared.resources.map_detail_action_join_room
import one.meshcore.shared.resources.map_detail_action_management
import one.meshcore.shared.resources.map_detail_action_send_message
import one.meshcore.shared.resources.map_detail_action_telemetry
import one.meshcore.shared.resources.map_detail_favorite
import one.meshcore.shared.resources.map_detail_latitude
import one.meshcore.shared.resources.map_detail_longitude
import one.meshcore.shared.resources.map_detail_section_location
import one.meshcore.shared.resources.map_detail_status
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.FAVORITE_STAR_ROLE
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

private val FAVORITE_ICON_SIZE = 18.dp
private val FAVORITE_STATUS_GAP = 4.dp
private val PING_PROGRESS_DP = 20.dp
private val PING_PROGRESS_STROKE = 2.dp

@Composable
internal fun MapFavoriteStatusRow(isFavorite: Boolean) {
    if (!isFavorite) return
    ListItem(
        headlineContent = { Text(stringResource(Res.string.map_detail_status)) },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FAVORITE_STATUS_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Star,
                    contentDescription = null,
                    tint = FAVORITE_STAR_ROLE.tint(),
                    modifier = Modifier.size(FAVORITE_ICON_SIZE),
                )
                Text(
                    text = stringResource(Res.string.map_detail_favorite),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FAVORITE_STAR_ROLE.tint(),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun MapTimestampRow(label: String, timestamp: Long?, nowEpochSeconds: Long, skipNonPositive: Boolean) {
    val seconds = mapTimestampSeconds(timestamp, skipNonPositive) ?: return
    PreferenceHubValueRow(label, conversationTimestampLabel(seconds, nowEpochSeconds))
}

@Composable
internal fun MapLocationSection(hasLocation: Boolean, latitude: Double, longitude: Double) {
    if (!hasLocation) return
    PreferenceHubSection(
        title = stringResource(Res.string.map_detail_section_location),
        applyHorizontalPadding = false,
    ) {
        PreferenceHubValueRow(stringResource(Res.string.map_detail_latitude), formatFixed6(latitude))
        PreferenceHubValueRow(stringResource(Res.string.map_detail_longitude), formatFixed6(longitude))
    }
}

@Composable
internal fun MapContactTypeActions(
    type: ContactType,
    isRadioReady: Boolean,
    radioHint: String?,
    isPinging: Boolean,
    pingResult: ContactPingResult?,
    onShowTelemetry: () -> Unit,
    onManagement: () -> Unit,
    onJoinRoom: () -> Unit,
    onMessage: () -> Unit,
    onPing: () -> Unit,
) {
    when (type) {
        ContactType.REPEATER -> {
            MapSheetActionButton(
                text = stringResource(Res.string.map_detail_action_telemetry),
                enabled = isRadioReady,
                radioHint = radioHint,
                onClick = onShowTelemetry,
            )
            MapSheetActionButton(
                text = stringResource(Res.string.map_detail_action_management),
                enabled = isRadioReady,
                radioHint = radioHint,
                onClick = onManagement,
                outlined = true,
            )
            PingBlock(
                isRadioReady = isRadioReady,
                radioHint = radioHint,
                isPinging = isPinging,
                pingResult = pingResult,
                onPing = onPing,
            )
        }
        ContactType.ROOM -> {
            MapSheetActionButton(
                text = stringResource(Res.string.map_detail_action_join_room),
                enabled = isRadioReady,
                radioHint = radioHint,
                onClick = onJoinRoom,
            )
            PingBlock(
                isRadioReady = isRadioReady,
                radioHint = radioHint,
                isPinging = isPinging,
                pingResult = pingResult,
                onPing = onPing,
            )
        }
        ContactType.CHAT -> MapSheetActionButton(
            text = stringResource(Res.string.map_detail_action_send_message),
            enabled = isRadioReady,
            radioHint = radioHint,
            onClick = onMessage,
        )
    }
}

@Composable
internal fun MapFavoriteTrailing(busy: Boolean): (@Composable () -> Unit)? = if (busy) {
    {
        CircularProgressIndicator(
            modifier = Modifier.size(PING_PROGRESS_DP),
            strokeWidth = PING_PROGRESS_STROKE,
        )
    }
} else {
    null
}

@Composable
internal fun MapDeleteContactButton(
    isVContact: Boolean,
    enabled: Boolean,
    radioHint: String?,
    isDeleting: Boolean,
    onClick: () -> Unit,
) {
    if (isVContact) return
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (radioHint != null) {
                    Modifier.semantics { stateDescription = radioHint }
                } else {
                    Modifier
                },
            ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        if (isDeleting) {
            CircularProgressIndicator(
                modifier = Modifier.size(PING_PROGRESS_DP),
                strokeWidth = PING_PROGRESS_STROKE,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(stringResource(Res.string.contacts_common_delete))
        }
    }
}

@Composable
internal fun MapDeleteConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!show) return
    ConfirmAlertDialog(
        title = title,
        message = message,
        confirmLabel = stringResource(Res.string.contacts_common_delete),
        dismissLabel = stringResource(Res.string.contacts_common_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun MapContactErrorDialog(message: String?, onDismiss: () -> Unit) {
    val text = message ?: return
    AcknowledgedAlertDialog(
        title = stringResource(Res.string.contacts_common_error),
        message = text,
        confirmLabel = stringResource(Res.string.contacts_common_ok),
        onDismiss = onDismiss,
    )
}

@Composable
private fun PingBlock(
    isRadioReady: Boolean,
    radioHint: String?,
    isPinging: Boolean,
    pingResult: ContactPingResult?,
    onPing: () -> Unit,
) {
    MapSheetActionButton(
        text = stringResource(Res.string.contacts_detail_ping),
        enabled = isRadioReady && !isPinging,
        radioHint = radioHint,
        outlined = true,
        trailing = if (isPinging) {
            {
                CircularProgressIndicator(
                    modifier = Modifier.size(PING_PROGRESS_DP),
                    strokeWidth = PING_PROGRESS_STROKE,
                )
            }
        } else {
            null
        },
        onClick = onPing,
    )
    pingResult?.let { result ->
        PingResultRow(result = result)
    }
}
