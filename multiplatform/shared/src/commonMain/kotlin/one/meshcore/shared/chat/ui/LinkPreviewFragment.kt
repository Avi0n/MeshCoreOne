package one.meshcore.shared.chat.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ImageReference
import one.meshcore.shared.chat.LinkPreviewFragmentState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_link_preview_accessibility_hint
import one.meshcore.shared.resources.chats_link_preview_accessibility_label
import one.meshcore.shared.resources.chats_preview_loading_accessibility
import one.meshcore.shared.resources.chats_preview_loading_hint
import one.meshcore.shared.resources.chats_preview_tap_accessibility
import one.meshcore.shared.resources.chats_preview_tap_hint
import one.meshcore.shared.resources.chats_preview_tap_to_load
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.rememberReduceMotion
import one.meshcore.shared.urlsafety.UrlComponents
import org.jetbrains.compose.resources.stringResource

/** Resolves a fragment's [ImageReference] to its decoded bitmap, or null while bytes are still loading. */
typealias PreviewImageResolver = (ImageReference) -> ImageBitmap?

/**
 * Renders the link-preview slot of a message bubble, the Compose port of the iOS `LinkPreviewFragmentView`.
 * A pure function of its [state] plus an [imageResolver] closure (kept free of view-model lookups so the
 * row stays value-driven): a loaded card whose hero bytes have not arrived falls back to the reserved-height
 * loading card, a scope-disabled URL shows the tap-to-load placeholder driven by [onManualPreviewFetch], and
 * the idle / no-preview modes render nothing. A card tap opens the URL through the platform browser, the
 * same seam the formatted-text Web-link tap uses.
 */
@Composable
fun LinkPreviewFragment(
    state: LinkPreviewFragmentState,
    imageResolver: PreviewImageResolver,
    onManualPreviewFetch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    when (val mode = state.mode) {
        is LinkPreviewFragmentState.Mode.Loaded -> {
            val url = state.primaryURL ?: return
            val heroImage = mode.image?.let(imageResolver)
            if (mode.image != null && heroImage == null) {
                LinkPreviewLoadingCard(state, modifier)
            } else {
                LinkPreviewCard(
                    url = url,
                    title = mode.preview.title,
                    image = heroImage,
                    icon = mode.icon?.let(imageResolver),
                    imageWidth = mode.preview.imageWidth,
                    imageHeight = mode.preview.imageHeight,
                    onTap = { uriHandler.openUri(url) },
                    modifier = modifier,
                )
            }
        }

        is LinkPreviewFragmentState.Mode.Loading -> LinkPreviewLoadingCard(state, modifier)

        is LinkPreviewFragmentState.Mode.Disabled ->
            TapToLoadPreview(mode.url, onTap = onManualPreviewFetch, modifier = modifier)

        is LinkPreviewFragmentState.Mode.Legacy -> {
            val url = state.primaryURL ?: return
            LinkPreviewCard(
                url = url,
                title = mode.title,
                image = mode.image?.let(imageResolver),
                icon = mode.icon?.let(imageResolver),
                imageWidth = null,
                imageHeight = null,
                onTap = { uriHandler.openUri(url) },
                modifier = modifier,
            )
        }

        LinkPreviewFragmentState.Mode.Idle, LinkPreviewFragmentState.Mode.NoPreview -> Unit
    }
}

/**
 * A resolved link preview: an optional hero image over an icon/title/domain header. The hero reserves an
 * aspect-derived frame clamped to [MIN_HERO_HEIGHT_DP]/[MAX_HERO_HEIGHT_DP] so the bubble does not jump when
 * bytes arrive after layout. Opaque by design (never translucent), per the preview-card readability rule.
 */
@Composable
private fun LinkPreviewCard(
    url: String,
    title: String?,
    image: ImageBitmap?,
    icon: ImageBitmap?,
    imageWidth: Int?,
    imageHeight: Int?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val domain = UrlComponents.parse(url)?.host ?: url
    val label = formattedStringResource(Res.string.chats_link_preview_accessibility_label, title ?: domain, domain)
    val hint = stringResource(Res.string.chats_link_preview_accessibility_hint)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = null, role = Role.Button) { onTap() }
            .semantics(mergeDescendants = true) { contentDescription = "$label. $hint" },
    ) {
        if (image != null) {
            PreviewHero(aspect = heroAspect(imageWidth, imageHeight)) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(HERO_FILL_DP.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(CARD_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(HEADER_SPACING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaviconSlot(icon)
            Column {
                if (!title.isNullOrEmpty()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The reserved-height placeholder shown while a preview resolves or while its hero bytes download, ported
 * from the iOS `LinkPreviewLoadingCard`. A confirmed [LinkPreviewFragmentState.Mode.Loaded] awaiting bytes
 * reserves the eventual hero shape and shows the real title/host; the unknown idle/loading modes shimmer
 * both. Sharing the hero shape keeps the bubble height stable across the byte-arrival transition.
 */
@Composable
private fun LinkPreviewLoadingCard(state: LinkPreviewFragmentState, modifier: Modifier = Modifier) {
    val mode = state.mode
    val loadingUrl = (mode as? LinkPreviewFragmentState.Mode.Loading)?.url
    val loaded = mode as? LinkPreviewFragmentState.Mode.Loaded
    val host = loaded?.preview?.url?.let { UrlComponents.parse(it)?.host ?: it }
        ?: loadingUrl?.let { UrlComponents.parse(it)?.host }
        ?: ""
    val label = formattedStringResource(Res.string.chats_preview_loading_accessibility, host)
    val hint = stringResource(Res.string.chats_preview_loading_hint)
    val aspect = heroAspect(loaded?.preview?.imageWidth, loaded?.preview?.imageHeight)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $hint" },
    ) {
        PreviewHero(aspect = aspect) { PreviewShimmer(Modifier.fillMaxWidth().height(HERO_FILL_DP.dp)) }
        Column(
            modifier = Modifier.fillMaxWidth().padding(CARD_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(TEXT_ROW_SPACING_DP.dp),
        ) {
            val title = loaded?.preview?.title
            if (title != null && title.isNotEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (host.isNotEmpty()) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PreviewShimmer(Modifier.fillMaxWidth(TITLE_ROW_WIDTH_FRACTION).height(TITLE_ROW_HEIGHT_DP.dp))
                PreviewShimmer(Modifier.fillMaxWidth(DOMAIN_ROW_WIDTH_FRACTION).height(DOMAIN_ROW_HEIGHT_DP.dp))
            }
        }
    }
}

/**
 * Scope-off tap-to-load placeholder shown when previews are enabled but auto-resolve is off for this
 * conversation type, ported from the iOS `TapToLoadPreview`. Tapping requests a manual fetch; the fragment
 * moves to its loading mode from there.
 */
@Composable
internal fun TapToLoadPreview(url: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val host = UrlComponents.parse(url)?.host ?: url
    val label = formattedStringResource(Res.string.chats_preview_tap_accessibility, host)
    val hint = stringResource(Res.string.chats_preview_tap_hint)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = null, role = Role.Button) { onTap() }
            .padding(horizontal = TAP_PADDING_H_DP.dp, vertical = TAP_PADDING_V_DP.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $hint" },
        horizontalArrangement = Arrangement.spacedBy(HEADER_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FaviconSlot(icon = null)
        Text(
            text = stringResource(Res.string.chats_preview_tap_to_load),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The favicon square: the resolved icon when present, else an opaque placeholder box (no icon artifact). */
@Composable
private fun FaviconSlot(icon: ImageBitmap?) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(ICON_SIZE_DP.dp).clip(RoundedCornerShape(ICON_CORNER_DP.dp)),
        )
    } else {
        Box(
            Modifier.size(ICON_SIZE_DP.dp)
                .clip(RoundedCornerShape(ICON_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FAVICON_PLACEHOLDER_ALPHA)),
        )
    }
}

/**
 * The shared opaque hero chrome: an aspect-derived frame clamped to the min/max hero height with its top
 * corners rounded, overlaying [content]. The Compose analogue of the iOS `RichPreviewCard`; kept opaque so
 * a hero stays legible under glare.
 */
@Composable
private fun PreviewHero(aspect: Float, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .heightIn(min = MIN_HERO_HEIGHT_DP.dp, max = MAX_HERO_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(topStart = CARD_CORNER_DP.dp, topEnd = CARD_CORNER_DP.dp)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * A subtle animated shimmer sweep standing in for the iOS `PreviewSkeleton`. The animated progress is read
 * inside drawBehind so the sweep invalidates only the draw phase; a composition-time read would recompose
 * the skeleton every frame for as long as it is visible. Reduce Motion holds the static base fill instead of
 * the sweep, mirroring the iOS `PreviewSkeleton` shimmer gate.
 */
@Composable
private fun PreviewShimmer(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SHIMMER_BASE_ALPHA)
    if (rememberReduceMotion()) {
        Box(modifier.clip(RoundedCornerShape(SHIMMER_CORNER_DP.dp)).background(base))
        return
    }
    val transition = rememberInfiniteTransition(label = "previewShimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS), RepeatMode.Restart),
        label = "previewShimmerProgress",
    )
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SHIMMER_HIGHLIGHT_ALPHA)
    Box(
        modifier
            .clip(RoundedCornerShape(SHIMMER_CORNER_DP.dp))
            .drawBehind {
                val start = progress.value * SHIMMER_SWEEP_SPAN - SHIMMER_SWEEP_SPAN
                val brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        (start).coerceIn(0f, 1f) to base,
                        (start + SHIMMER_BAND).coerceIn(0f, 1f) to highlight,
                        (start + SHIMMER_BAND * 2).coerceIn(0f, 1f) to base,
                    ),
                )
                drawRect(brush)
            },
    )
}

/** Hero aspect from intrinsic dimensions, falling back to 16:9 when either is missing or non-positive. */
private fun heroAspect(imageWidth: Int?, imageHeight: Int?): Float {
    val width = imageWidth?.takeIf { it > 0 }
    val height = imageHeight?.takeIf { it > 0 }
    return if (width != null && height != null) width.toFloat() / height.toFloat() else FALLBACK_ASPECT
}

private const val FALLBACK_ASPECT = 16f / 9f
private const val MIN_HERO_HEIGHT_DP = 100
private const val MAX_HERO_HEIGHT_DP = 250
private const val HERO_FILL_DP = MAX_HERO_HEIGHT_DP
private const val CARD_CORNER_DP = 12
private const val CARD_PADDING_DP = 10
private const val HEADER_SPACING_DP = 8
private const val TEXT_ROW_SPACING_DP = 6
private const val ICON_SIZE_DP = 16
private const val ICON_CORNER_DP = 4
private const val FAVICON_PLACEHOLDER_ALPHA = 0.25f
private const val TAP_PADDING_H_DP = 12
private const val TAP_PADDING_V_DP = 8
private const val TITLE_ROW_HEIGHT_DP = 14
private const val DOMAIN_ROW_HEIGHT_DP = 10
private const val TITLE_ROW_WIDTH_FRACTION = 0.72f
private const val DOMAIN_ROW_WIDTH_FRACTION = 0.4f
private const val SHIMMER_PERIOD_MS = 1200
private const val SHIMMER_CORNER_DP = 4
private const val SHIMMER_BASE_ALPHA = 0.12f
private const val SHIMMER_HIGHLIGHT_ALPHA = 0.28f
private const val SHIMMER_SWEEP_SPAN = 2f
private const val SHIMMER_BAND = 0.2f
