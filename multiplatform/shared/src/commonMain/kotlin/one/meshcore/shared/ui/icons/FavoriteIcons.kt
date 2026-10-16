package one.meshcore.shared.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** iOS context menus: `star.slash` when favorited, `star.fill` otherwise. */
internal fun favoriteToggleIcon(isFavorite: Boolean): ImageVector =
    if (isFavorite) AppIcons.StarSlash else AppIcons.Star

/** iOS map contact detail: `star.slash` when favorited, `star` otherwise. */
internal fun mapFavoriteToggleIcon(isFavorite: Boolean): ImageVector =
    if (isFavorite) AppIcons.StarSlash else AppIcons.StarBorder

/** Material push-pin glyph used for local chat pins. */
internal val PushPinGlyph: ImageVector
    get() {
        val cached = pushPinCache
        if (cached != null) return cached
        return ImageVector.Builder(
            name = "PushPin",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 9f)
                verticalLineTo(4f)
                horizontalLineTo(17f)
                verticalLineTo(2f)
                horizontalLineTo(7f)
                verticalLineTo(4f)
                horizontalLineTo(8f)
                verticalLineTo(9f)
                curveTo(8f, 10.66f, 6.66f, 12f, 5f, 12f)
                verticalLineTo(14f)
                horizontalLineTo(11f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
                verticalLineTo(14f)
                horizontalLineTo(19f)
                verticalLineTo(12f)
                curveTo(17.34f, 12f, 16f, 10.66f, 16f, 9f)
                close()
            }
        }.build().also { pushPinCache = it }
    }

/**
 * Material filled star with an even-odd slash cut, analog of SF Symbol `star.slash`.
 * Cached like the generated Material [ImageVector]s.
 */
internal val StarSlashGlyph: ImageVector
    get() {
        val cached = starSlashCache
        if (cached != null) return cached
        return ImageVector.Builder(
            name = "StarSlash",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(12f, 17.27f)
                lineTo(18.18f, 21f)
                lineTo(16.54f, 13.97f)
                lineTo(22f, 9.24f)
                lineTo(14.81f, 8.63f)
                lineTo(12f, 2f)
                lineTo(9.19f, 8.63f)
                lineTo(2f, 9.24f)
                lineTo(7.46f, 13.97f)
                lineTo(5.82f, 21f)
                close()
                moveTo(4.78f, 20.78f)
                lineTo(20.78f, 4.78f)
                lineTo(19.22f, 3.22f)
                lineTo(3.22f, 19.22f)
                close()
            }
        }.build().also { starSlashCache = it }
    }

private var starSlashCache: ImageVector? = null
private var pushPinCache: ImageVector? = null
