package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ChannelInfoController
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_title
import one.meshcore.shared.resources.chats_common_info
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** Channel or DM Info control for the thread top bar. */
@Composable
internal fun ThreadInfoAction(
    channel: ChannelDto?,
    contact: ContactDto?,
    channelInfoDeps: ChannelInfoController.Dependencies?,
    onShowChannelInfo: () -> Unit,
    onOpenContactInfo: (String) -> Unit,
) {
    when {
        channel != null && channelInfoDeps != null -> {
            AppIconButton(
                imageVector = AppIcons.Info,
                contentDescription = stringResource(Res.string.chats_channel_info_title),
                onClick = onShowChannelInfo,
            )
        }
        contact != null -> {
            AppIconButton(
                imageVector = AppIcons.Info,
                contentDescription = stringResource(Res.string.chats_common_info),
                onClick = { onOpenContactInfo(contact.id.value) },
            )
        }
    }
}
