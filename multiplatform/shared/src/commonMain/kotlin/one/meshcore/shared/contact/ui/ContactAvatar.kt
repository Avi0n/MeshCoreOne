package one.meshcore.shared.contact.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.avatar.IdentityGamut
import one.meshcore.shared.avatar.RgbColor
import one.meshcore.shared.avatar.decodeAvatarImage
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.avatarInitials
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.LocalAppTheme
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.ui.LocalHighContrast
import one.meshcore.shared.ui.icons.AppIcons
import androidx.compose.material3.Text as MaterialText

/**
 * Identity avatar for a chat contact: a name-colored circle with initials, using the active
 * theme's [IdentityGamut] so colors track Appearance. Respects system high-contrast (WCAG AAA floor).
 */
@Composable
fun ContactAvatar(contact: ContactDto, size: Dp) {
    ContactAvatar(contact.displayNameForAvatar, size, contact.avatarImageData)
}

/**
 * Identity avatar for a bare advertised name, for a node with no contact row yet (Discover). Mirrors the
 * iOS `ContactAvatar(name:size:)` initializer, which the discovery row uses for the same reason.
 */
@Composable
fun ContactAvatar(name: String, size: Dp, imageData: ByteArray? = null) {
    if (imageData != null && imageData.isNotEmpty()) {
        val image = rememberAvatarBitmap(imageData)
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
            return
        }
    }
    val theme = LocalAppTheme.current
    val dark = LocalUseDarkTheme.current
    val highContrast = LocalHighContrast.current
    val fill = theme.identityGamut.colorForName(
        name = name,
        backgroundLuminances = theme.identitySurfaceLuminances(dark),
        highContrast = highContrast,
    )
    AvatarCircle(
        fill = fill,
        glyph = avatarInitials(name),
        glyphColor = if (IdentityGamut.glyphIsWhite(fill.luminance)) Color.White else NEAR_BLACK_GLYPH,
        glyphFontSize = (size.value * IDENTITY_GLYPH_RATIO).sp,
        size = size,
    )
}

/** Category avatar for a repeater or room node: theme swatch with a monochrome Material glyph. */
@Composable
fun NodeAvatar(category: AvatarCategory, size: Dp) {
    val theme = LocalAppTheme.current
    val dark = LocalUseDarkTheme.current
    val highContrast = LocalHighContrast.current
    val fill = theme.categoryColor(category, dark, highContrast)
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(fill.toComposeColor()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = nodeAvatarIcon(category),
            contentDescription = null,
            tint = categoryAvatarGlyphTint(theme, fill),
            modifier = Modifier.size(size * CATEGORY_GLYPH_RATIO),
        )
    }
}

/**
 * Material analog of iOS `NodeAvatar` SF Symbols: repeater antenna, room door, channel number.
 */
internal fun nodeAvatarIcon(category: AvatarCategory): ImageVector = when (category) {
    AvatarCategory.REPEATER -> AppIcons.CellTower
    AvatarCategory.ROOM -> AppIcons.MeetingRoom
    AvatarCategory.CHANNEL -> AppIcons.Tag
}

/** White on System category swatches; luminance-adaptive on gamut themes. iOS `avatarGlyphColor`. */
internal fun categoryAvatarGlyphTint(theme: AppTheme, fill: RgbColor): Color =
    if (theme.avatarGlyphIsWhite(fill)) Color.White else NEAR_BLACK_GLYPH

@Composable
private fun AvatarCircle(fill: RgbColor, glyph: String, glyphColor: Color, glyphFontSize: TextUnit, size: Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(fill.toComposeColor()),
        contentAlignment = Alignment.Center,
    ) {
        MaterialText(
            text = glyph,
            color = glyphColor,
            fontSize = glyphFontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Maps the framework-neutral [RgbColor] to a Compose [Color] via its opaque `0xAARRGGBB` value. */
fun RgbColor.toComposeColor(): Color = Color(argb)

private val ContactDto.displayNameForAvatar: String get() = nickname ?: name

private val NEAR_BLACK_GLYPH = Color(red = 0.1f, green = 0.1f, blue = 0.1f)
private const val IDENTITY_GLYPH_RATIO = 0.4
private const val CATEGORY_GLYPH_RATIO = 0.45f

/** The avatar diameter used in list rows. */
val CONTACT_ROW_AVATAR_SIZE: Dp = 44.dp

/**
 * Decodes avatar bytes once and reuses the result across recompositions and re-fetches, keyed by the
 * bytes' content hash. Mirrors the iOS process-lifetime avatar cache so scrolling a contact list does
 * not re-run the JPEG decode every frame. Accessed only from composition (the main thread).
 */
@Composable
private fun rememberAvatarBitmap(bytes: ByteArray): ImageBitmap? {
    val key = remember(bytes) { bytes.contentHashCode() }
    return remember(key) {
        avatarBitmapCache[key] ?: decodeAvatarImage(bytes)?.also { cacheAvatarBitmap(key, it) }
    }
}

private const val AVATAR_CACHE_LIMIT = 64

// Insertion-ordered so the eldest key is evicted first once the cap is reached (bounded FIFO).
private val avatarBitmapCache = LinkedHashMap<Int, ImageBitmap>()

private fun cacheAvatarBitmap(key: Int, image: ImageBitmap) {
    if (avatarBitmapCache.size >= AVATAR_CACHE_LIMIT) {
        avatarBitmapCache.keys.firstOrNull()?.let(avatarBitmapCache::remove)
    }
    avatarBitmapCache[key] = image
}
