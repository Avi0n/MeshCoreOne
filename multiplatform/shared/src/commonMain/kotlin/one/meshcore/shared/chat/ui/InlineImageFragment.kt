package one.meshcore.shared.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import one.meshcore.shared.chat.InlineImage
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_inline_image_animated_accessibility
import one.meshcore.shared.resources.chats_inline_image_failed_label
import one.meshcore.shared.resources.chats_inline_image_image_accessibility
import one.meshcore.shared.resources.chats_inline_image_retry_hint
import one.meshcore.shared.resources.chats_inline_image_tap_hint
import one.meshcore.shared.resources.chats_inline_image_tap_to_retry
import one.meshcore.shared.ui.IMAGE_OVERLAY_CONTENT_COLOR
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberReduceMotion
import org.jetbrains.compose.resources.stringResource

private const val IMAGE_CORNER_DP = 12
private const val DEFAULT_ASPECT = 16.0 / 9.0
private const val GIF_PLAY_ICON_DP = 44
private const val RETRY_ICON_SPACING_DP = 8
internal const val GIF_PAUSED_SCRIM_ALPHA = 0.3f

internal fun gifPausedScrim(scrim: Color) = scrim.copy(alpha = GIF_PAUSED_SCRIM_ALPHA)

/**
 * Renders an [InlineImage] fragment. Pending and Loading reserve the box (the gated cache fetches
 * bytes; malware/SSRF have not cleared a Pending URL). Disabled is the compact in-bubble
 * [TapToLoadPreview] chip, matching iOS `BubbleFragmentStack`. Failed is a reserved retry
 * placeholder; Loaded renders the cached bytes through [imageResolver]. Tap opens a full-screen viewer.
 */
@Composable
fun InlineImageFragment(
    image: InlineImage,
    onManualFetch: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryFetch: () -> Unit = {},
    onLoadFailed: () -> Unit = {},
    imageResolver: PreviewImageResolver = { null },
    gifModelResolver: (one.meshcore.shared.chat.ImageReference) -> Any? = { null },
) {
    val aspect = (image.cachedAspect ?: DEFAULT_ASPECT).toFloat().coerceIn(0.25f, 4f)
    var fullscreenBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    when (val state = image.state) {
        is InlineImage.LoadState.Disabled ->
            TapToLoadPreview(url = state.url, onTap = onManualFetch, modifier = modifier)

        is InlineImage.LoadState.Failed ->
            FailedImageRetry(aspect = aspect, onRetry = onRetryFetch, modifier = modifier)

        // The safety gates have not cleared the URL yet: reserve the box, request nothing.
        InlineImage.LoadState.Pending -> ReservedImageBox(aspect = aspect, modifier = modifier)

        is InlineImage.LoadState.Loading -> ReservedImageBox(aspect = aspect, modifier = modifier)

        is InlineImage.LoadState.Loaded -> {
            val bitmap = imageResolver(state.image)
            if (bitmap == null) {
                // The GIF model resolver exposes the same cached bytes for stills too. A null model
                // means bytes are temporarily unavailable; present bytes that fail decode need the
                // controller's page-preview fallback instead of an indefinitely reserved box.
                val bytesAvailable = gifModelResolver(state.image) != null
                LaunchedEffect(state.image, bytesAvailable) {
                    if (bytesAvailable) onLoadFailed()
                }
                ReservedImageBox(aspect = aspect, modifier = modifier)
            } else if (state.isGIF) {
                // A loaded GIF gets iOS's GIFContentView affordance: static first frame with a play-circle
                // overlay while paused, tap toggles playback, autoPlayGIFs seeds the initial state, and
                // Reduce Motion forces it paused. The animated frames render through [gifModelResolver]
                // (a Coil model built from the same cached bytes); a null model keeps the static frame.
                LoadedGif(
                    frame = bitmap,
                    autoPlayGIFs = image.autoPlayGIFs,
                    aspect = aspect,
                    gifModel = { gifModelResolver(state.image) },
                    modifier = modifier,
                )
            } else {
                val label = stringResource(Res.string.chats_inline_image_image_accessibility)
                val tapHint = stringResource(Res.string.chats_inline_image_tap_hint)
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(IMAGE_CORNER_DP.dp))
                        .clickable(onClick = { fullscreenBitmap = bitmap })
                        .semantics {
                            contentDescription = label
                            onClick(label = tapHint, action = null)
                        },
                )
            }
        }
    }
    fullscreenBitmap?.let { bitmap ->
        FullScreenBitmapViewer(bitmap = bitmap, onDismiss = { fullscreenBitmap = null })
    }
}

/** The aspect-reserving placeholder for a row with no renderable bytes and no permitted fetch. */
@Composable
private fun ReservedImageBox(aspect: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(IMAGE_CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * Full-frame tap-to-retry for an image whose fetch failed, so a transient failure is recoverable instead of
 * being permanent for the life of the row. Mirrors the iOS `retryLayer`.
 */
@Composable
private fun FailedImageRetry(aspect: Float, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val failedLabel = stringResource(Res.string.chats_inline_image_failed_label)
    val retryHint = stringResource(Res.string.chats_inline_image_retry_hint)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(IMAGE_CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onRetry)
            .semantics {
                contentDescription = failedLabel
                onClick(label = retryHint, action = null)
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RETRY_ICON_SPACING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.chats_inline_image_tap_to_retry),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Whether a loaded GIF should start playing: honor the autoplay preference unless Reduce Motion is on. */
internal fun shouldAutoPlayGif(autoPlayGIFs: Boolean, reduceMotion: Boolean): Boolean = autoPlayGIFs && !reduceMotion

@Composable
private fun LoadedGif(
    frame: ImageBitmap,
    autoPlayGIFs: Boolean,
    aspect: Float,
    gifModel: () -> Any?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val label = stringResource(Res.string.chats_inline_image_animated_accessibility)
    val tapHint = stringResource(inlineGifTapHintRes())
    // Null until the user taps; before that the autoplay preference (gated on Reduce Motion) decides.
    var userToggled by remember { mutableStateOf<Boolean?>(null) }
    val playing = userToggled ?: shouldAutoPlayGif(autoPlayGIFs, reduceMotion)
    // Reduce Motion turning on mid-view forces the animation paused, mirroring iOS onChange(reduceMotion).
    LaunchedEffect(reduceMotion) { if (reduceMotion) userToggled = false }
    val platformContext = LocalPlatformContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(IMAGE_CORNER_DP.dp))
            .clickable { userToggled = !playing }
            .semantics {
                contentDescription = label
                onClick(label = tapHint, action = null)
            },
        contentAlignment = Alignment.Center,
    ) {
        val model = gifModel()
        if (playing && model != null) {
            val request = remember(model) {
                ImageRequest.Builder(platformContext).data(model).applyGifAutoplay(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                bitmap = frame,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!playing) {
            Box(
                modifier = Modifier.fillMaxSize().background(gifPausedScrim(MaterialTheme.colorScheme.scrim)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.PlayArrow,
                    contentDescription = null,
                    tint = IMAGE_OVERLAY_CONTENT_COLOR,
                    modifier = Modifier.size(GIF_PLAY_ICON_DP.dp),
                )
            }
        }
    }
}

/** iOS `GIFContentView` VoiceOver hint — same tap-hint string as the static image. */
internal fun inlineGifTapHintRes() = Res.string.chats_inline_image_tap_hint
