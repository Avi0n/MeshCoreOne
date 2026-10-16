package one.meshcore.shared.channel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.contact.ui.categoryAvatarGlyphTint
import one.meshcore.shared.contact.ui.toComposeColor
import one.meshcore.shared.theme.LocalAppTheme
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.ui.LocalHighContrast
import one.meshcore.shared.ui.icons.AppIcons

/**
 * Category avatar for a channel: the theme's channel swatch with a type glyph (globe / number / lock),
 * mirroring the iOS `ChannelAvatar`.
 */
@Composable
fun ChannelAvatar(channel: ChannelDto, size: Dp) {
    val theme = LocalAppTheme.current
    val dark = LocalUseDarkTheme.current
    val highContrast = LocalHighContrast.current
    val fill = theme.categoryColor(AvatarCategory.CHANNEL, dark, highContrast)
    val icon = when (channelAvatarGlyph(channel)) {
        ChannelAvatarGlyph.PUBLIC -> AppIcons.Public
        ChannelAvatarGlyph.HASHTAG -> AppIcons.Tag
        ChannelAvatarGlyph.PRIVATE -> AppIcons.Lock
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(fill.toComposeColor()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = categoryAvatarGlyphTint(theme, fill),
            modifier = Modifier.size(size * CHANNEL_AVATAR_GLYPH_RATIO),
        )
    }
}

private const val CHANNEL_AVATAR_GLYPH_RATIO = 0.4f
