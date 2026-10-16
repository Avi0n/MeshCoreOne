package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.notification.NotificationPreferencesSnapshot
import one.meshcore.shared.notification.StoredNotificationPreferences
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_notifications_channel_messages
import one.meshcore.shared.resources.settings_notifications_contact_messages
import one.meshcore.shared.resources.settings_notifications_disabled
import one.meshcore.shared.resources.settings_notifications_discovery_contact
import one.meshcore.shared.resources.settings_notifications_discovery_repeater
import one.meshcore.shared.resources.settings_notifications_discovery_room
import one.meshcore.shared.resources.settings_notifications_enable
import one.meshcore.shared.resources.settings_notifications_footer
import one.meshcore.shared.resources.settings_notifications_low_battery
import one.meshcore.shared.resources.settings_notifications_new_contact_discovered
import one.meshcore.shared.resources.settings_notifications_open_settings
import one.meshcore.shared.resources.settings_notifications_reactions
import one.meshcore.shared.resources.settings_notifications_room_messages
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionUiState
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Notifications: OS permission state, message-type toggles (contact/channel/room/reactions/
 * low battery/discovery), and sound/badge, over [StoredNotificationPreferences]. Mirrors iOS
 * `NotificationSettingsSection`. The permission row has three states, as iOS does: never asked offers an
 * in-app prompt, denied offers a trip to system settings, and granted shows the toggles. Collapsing the
 * first two sends a user who has simply never been asked out to system settings for no reason.
 */
@Composable
fun NotificationSettingsScreen(
    preferences: StoredNotificationPreferences,
    isNotificationsAuthorized: () -> Boolean = { true },
    isNotificationPermissionDetermined: () -> Boolean = { true },
    onRequestNotificationPermission: () -> Unit = {},
    onOpenSystemNotificationSettings: () -> Unit = {},
    permissionState: StateFlow<PermissionUiState>? = null,
    modifier: Modifier = Modifier,
) {
    var snapshot by remember { mutableStateOf(NotificationPreferencesSnapshot.AllEnabled) }
    val scope = rememberCoroutineScope()
    // Collect so a resume refresh of the OS grant recomposes this screen (iOS scenePhase re-read).
    val osPermission = permissionState?.collectAsStateWithLifecycle()?.value
    val authorized = isNotificationsAuthorized()
    val determined = if (osPermission != null) {
        OnboardingPermission.NOTIFICATIONS in osPermission.determined
    } else {
        isNotificationPermissionDetermined()
    }

    LaunchedEffect(preferences) {
        snapshot = preferences.snapshot()
    }

    Column(modifier.preferenceHubScroll()) {
        val permission = notificationPermission(authorized, determined)
        if (permission != NotificationPermissionState.GRANTED) {
            PreferenceHubSection {
                Column(Modifier.padding(PreferenceHubCardPadding)) {
                    NotificationPermissionPrompt(
                        state = permission,
                        onRequest = onRequestNotificationPermission,
                        onOpenSystemSettings = onOpenSystemNotificationSettings,
                    )
                }
            }
            PreferenceHubFooter(stringResource(Res.string.settings_notifications_footer))
            return
        }

        PreferenceHubSection {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_notifications_contact_messages),
                checked = snapshot.contactMessagesEnabled,
                onCheckedChange = { value ->
                    snapshot = snapshot.copy(contactMessagesEnabled = value)
                    scope.launch { preferences.setContactMessagesEnabled(value) }
                },
                leadingIcon = AppIcons.Chat,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_notifications_channel_messages),
                checked = snapshot.channelMessagesEnabled,
                onCheckedChange = { value ->
                    snapshot = snapshot.copy(channelMessagesEnabled = value)
                    scope.launch { preferences.setChannelMessagesEnabled(value) }
                },
                leadingIcon = AppIcons.Groups,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_notifications_room_messages),
                checked = snapshot.roomMessagesEnabled,
                onCheckedChange = { value ->
                    snapshot = snapshot.copy(roomMessagesEnabled = value)
                    scope.launch { preferences.setRoomMessagesEnabled(value) }
                },
                leadingIcon = AppIcons.ChatsSelected,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_notifications_reactions),
                checked = snapshot.reactionsEnabled,
                onCheckedChange = { value ->
                    snapshot = snapshot.copy(reactionsEnabled = value)
                    scope.launch { preferences.setReactionsEnabled(value) }
                },
                leadingIcon = AppIcons.SentimentSatisfied,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_notifications_low_battery),
                checked = snapshot.lowBatteryEnabled,
                onCheckedChange = { value ->
                    snapshot = snapshot.copy(lowBatteryEnabled = value)
                    scope.launch { preferences.setLowBatteryEnabled(value) }
                },
                leadingIcon = AppIcons.BatteryStd,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_notifications_new_contact_discovered),
                checked = snapshot.newContactDiscoveredEnabled,
                onCheckedChange = { value ->
                    snapshot = snapshot.copy(newContactDiscoveredEnabled = value)
                    scope.launch {
                        preferences.setNewContactDiscoveredEnabled(value)
                        // A first activation may auto-enable the three children in the store; reload to show it.
                        snapshot = preferences.snapshot()
                    }
                },
                leadingIcon = AppIcons.PersonAdd,
            )
            if (snapshot.newContactDiscoveredEnabled) {
                SettingsToggleRow(
                    label = stringResource(Res.string.settings_notifications_discovery_contact),
                    checked = snapshot.discoveryContactEnabled,
                    onCheckedChange = { value ->
                        snapshot = snapshot.copy(discoveryContactEnabled = value)
                        scope.launch { preferences.setDiscoveryContactEnabled(value) }
                    },
                    leadingIcon = AppIcons.Person,
                )
                SettingsToggleRow(
                    label = stringResource(Res.string.settings_notifications_discovery_repeater),
                    checked = snapshot.discoveryRepeaterEnabled,
                    onCheckedChange = { value ->
                        snapshot = snapshot.copy(discoveryRepeaterEnabled = value)
                        scope.launch { preferences.setDiscoveryRepeaterEnabled(value) }
                    },
                    leadingIcon = AppIcons.CellTower,
                )
                SettingsToggleRow(
                    label = stringResource(Res.string.settings_notifications_discovery_room),
                    checked = snapshot.discoveryRoomEnabled,
                    onCheckedChange = { value ->
                        snapshot = snapshot.copy(discoveryRoomEnabled = value)
                        scope.launch { preferences.setDiscoveryRoomEnabled(value) }
                    },
                    leadingIcon = AppIcons.MeetingRoom,
                )
            }
        }
        PreferenceHubFooter(stringResource(Res.string.settings_notifications_footer))
    }
}

/**
 * The permission row before the toggles are usable. Never asked offers the system prompt in-app; denied
 * can only be undone in system settings, so that is the only route offered there.
 */
@Composable
private fun NotificationPermissionPrompt(
    state: NotificationPermissionState,
    onRequest: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (state == NotificationPermissionState.NOT_ASKED) {
            Button(
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(AppIcons.Notifications, contentDescription = null)
                Text(
                    text = stringResource(Res.string.settings_notifications_enable),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.NotificationsOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.settings_notifications_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Button(
            onClick = onOpenSystemSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.settings_notifications_open_settings))
        }
    }
}

/** What the notification permission row offers, mirroring the three iOS `UNAuthorizationStatus` arms. */
internal enum class NotificationPermissionState {
    /** Granted: the message-type toggles are usable. */
    GRANTED,

    /** Never asked: the system prompt can still be raised from inside the app. */
    NOT_ASKED,

    /** Asked and refused: only system settings can undo it. */
    DENIED,
}

/**
 * Never asked and denied are different states. Treating them alike sends a user who has simply not been
 * asked yet out to system settings, when one in-app tap would do.
 */
internal fun notificationPermission(authorized: Boolean, determined: Boolean): NotificationPermissionState = when {
    authorized -> NotificationPermissionState.GRANTED
    determined -> NotificationPermissionState.DENIED
    else -> NotificationPermissionState.NOT_ASKED
}
