package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_blocking_channel_senders
import one.meshcore.shared.resources.settings_blocking_contacts
import one.meshcore.shared.resources.settings_blocking_header
import one.meshcore.shared.resources.settings_inline_images_auto_play_gifs
import one.meshcore.shared.resources.settings_link_previews_footer
import one.meshcore.shared.resources.settings_link_previews_header
import one.meshcore.shared.resources.settings_link_previews_reduce_motion_note
import one.meshcore.shared.resources.settings_link_previews_show_in_channels
import one.meshcore.shared.resources.settings_link_previews_show_in_d_ms
import one.meshcore.shared.resources.settings_link_previews_toggle
import one.meshcore.shared.resources.settings_map_previews_footer
import one.meshcore.shared.resources.settings_map_previews_header
import one.meshcore.shared.resources.settings_map_previews_toggle
import one.meshcore.shared.resources.settings_messages_footer
import one.meshcore.shared.resources.settings_messages_header
import one.meshcore.shared.resources.settings_messages_show_incoming_heard_count
import one.meshcore.shared.resources.settings_messages_show_incoming_hop_count
import one.meshcore.shared.resources.settings_messages_show_incoming_path
import one.meshcore.shared.resources.settings_messages_show_incoming_region
import one.meshcore.shared.resources.settings_reply_with_quote_footer
import one.meshcore.shared.resources.settings_reply_with_quote_toggle
import one.meshcore.shared.settings.ChatDisplayPreferencesStore
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubScroll
import one.meshcore.shared.ui.rememberReduceMotion
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Chats: message bubble metadata toggles, link/inline-image previews, map thumbnails, and
 * the Blocking entry points (blocked contacts + blocked channel senders; iOS `BlockingSection`).
 * Backed by [store] so open threads see changes live.
 */
@Composable
fun ChatSettingsScreen(
    store: ChatDisplayPreferencesStore,
    onOpenBlockedContacts: () -> Unit = {},
    onOpenBlockedChannelSenders: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val prefs by store.state.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()

    Column(modifier.preferenceHubScroll()) {
        PreferenceHubSection {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_reply_with_quote_toggle),
                checked = prefs.replyWithQuote,
                onCheckedChange = store::setReplyWithQuote,
                leadingIcon = CHAT_SETTINGS_REPLY_WITH_QUOTE_ICON,
            )
        }
        PreferenceHubFooter(stringResource(Res.string.settings_reply_with_quote_footer))

        PreferenceHubSection(
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
            title = stringResource(Res.string.settings_link_previews_header),
        ) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_link_previews_toggle),
                checked = prefs.linkPreviewsEnabled,
                onCheckedChange = store::setLinkPreviewsEnabled,
                leadingIcon = CHAT_SETTINGS_LINK_PREVIEWS_ICON,
            )
            // GIF autoplay nests under the link-content master toggle, mirroring iOS LinkPreviewSettingsSection;
            // when Reduce Motion is on the note explains that GIFs will not auto-play.
            if (prefs.linkPreviewsEnabled) {
                SettingsToggleRow(
                    label = stringResource(Res.string.settings_link_previews_show_in_d_ms),
                    checked = prefs.linkPreviewsAutoResolveDM,
                    onCheckedChange = store::setLinkPreviewsAutoResolveDM,
                )
                SettingsToggleRow(
                    label = stringResource(Res.string.settings_link_previews_show_in_channels),
                    checked = prefs.linkPreviewsAutoResolveChannels,
                    onCheckedChange = store::setLinkPreviewsAutoResolveChannels,
                )
                SettingsToggleRow(
                    label = stringResource(Res.string.settings_inline_images_auto_play_gifs),
                    checked = prefs.autoPlayGIFs,
                    onCheckedChange = store::setAutoPlayGIFs,
                )
            }
        }
        PreferenceHubFooter(stringResource(Res.string.settings_link_previews_footer))
        if (prefs.linkPreviewsEnabled && reduceMotion) {
            PreferenceHubFooter(stringResource(Res.string.settings_link_previews_reduce_motion_note))
        }

        PreferenceHubSection(
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
            title = stringResource(Res.string.settings_map_previews_header),
        ) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_map_previews_toggle),
                checked = prefs.showMapPreviewThumbnails,
                onCheckedChange = store::setShowMapPreviewThumbnails,
                leadingIcon = CHAT_SETTINGS_MAP_PREVIEWS_ICON,
            )
        }
        PreferenceHubFooter(stringResource(Res.string.settings_map_previews_footer))

        PreferenceHubSection(
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
            title = stringResource(Res.string.settings_messages_header),
        ) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_messages_show_incoming_path),
                checked = prefs.showIncomingPath,
                onCheckedChange = store::setShowIncomingPath,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_messages_show_incoming_hop_count),
                checked = prefs.showIncomingHopCount,
                onCheckedChange = store::setShowIncomingHopCount,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_messages_show_incoming_region),
                checked = prefs.showIncomingRegion,
                onCheckedChange = store::setShowIncomingRegion,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_messages_show_incoming_heard_count),
                checked = prefs.showIncomingHeardCount,
                onCheckedChange = store::setShowIncomingHeardCount,
            )
        }
        PreferenceHubFooter(stringResource(Res.string.settings_messages_footer))

        PreferenceHubSection(
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
            title = stringResource(Res.string.settings_blocking_header),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.settings_blocking_channel_senders)) },
                leadingContent = { Icon(BLOCKING_CHANNEL_SENDERS_ICON, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenBlockedChannelSenders),
            )
            ListItem(
                headlineContent = { Text(stringResource(Res.string.settings_blocking_contacts)) },
                leadingContent = { Icon(BLOCKING_CONTACTS_ICON, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenBlockedContacts),
            )
        }
    }
}

/** iOS `BlockingSection` contacts: `hand.raised.slash`. */
internal val BLOCKING_CONTACTS_ICON get() = AppIcons.DoNotTouch

/** iOS `ChatSettingsView` reply-with-quote `text.quote`. */
internal val CHAT_SETTINGS_REPLY_WITH_QUOTE_ICON get() = AppIcons.FormatQuote

/** iOS `LinkPreviewSettingsSection` master toggle `link`. */
internal val CHAT_SETTINGS_LINK_PREVIEWS_ICON get() = AppIcons.Link

/** iOS `MapPreviewSettingsSection` toggle `map`. */
internal val CHAT_SETTINGS_MAP_PREVIEWS_ICON get() = AppIcons.MapSelected

/** iOS `BlockingSection` channel senders: `person.crop.circle.badge.xmark`. */
internal val BLOCKING_CHANNEL_SENDERS_ICON get() = AppIcons.NoAccounts
