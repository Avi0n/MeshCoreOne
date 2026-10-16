package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ui.ChannelAvatar
import one.meshcore.shared.chat.isEncryptedChannel
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.contact.ui.ContactAvatar
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_empty_state_no_messages
import one.meshcore.shared.resources.chats_channel_empty_state_private_description
import one.meshcore.shared.resources.chats_channel_empty_state_public_description
import one.meshcore.shared.resources.chats_contact_info_has_location
import one.meshcore.shared.resources.chats_empty_state_start_conversation
import one.meshcore.shared.resources.chats_row_no_messages
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun EmptyThread(title: String, channel: ChannelDto?, contact: ContactDto?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(EMPTY_THREAD_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EMPTY_THREAD_SPACING, Alignment.CenterVertically),
    ) {
        when {
            channel != null -> {
                ChannelAvatar(channel, EMPTY_THREAD_AVATAR_SIZE)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(Res.string.chats_channel_empty_state_no_messages),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        if (channel.isEncryptedChannel) {
                            Res.string.chats_channel_empty_state_private_description
                        } else {
                            Res.string.chats_channel_empty_state_public_description
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            contact != null -> {
                ContactAvatar(contact, EMPTY_THREAD_AVATAR_SIZE)
                Text(
                    contact.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(Res.string.chats_empty_state_start_conversation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (contact.hasLocation) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(EMPTY_THREAD_LOCATION_ICON_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            AppIcons.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(Res.string.chats_contact_info_has_location),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> Text(
                stringResource(Res.string.chats_row_no_messages),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val EMPTY_THREAD_AVATAR_SIZE = 80.dp
private val EMPTY_THREAD_PADDING = 16.dp
private val EMPTY_THREAD_SPACING = 16.dp
private val EMPTY_THREAD_LOCATION_ICON_GAP = 4.dp
