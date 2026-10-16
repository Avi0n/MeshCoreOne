package one.meshcore.shared.contact.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import one.meshcore.shared.avatar.AvatarCropGeometry
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_avatar_crop_choose
import one.meshcore.shared.resources.contacts_detail_avatar_crop_move_down
import one.meshcore.shared.resources.contacts_detail_avatar_crop_move_left
import one.meshcore.shared.resources.contacts_detail_avatar_crop_move_right
import one.meshcore.shared.resources.contacts_detail_avatar_crop_move_up
import one.meshcore.shared.resources.contacts_detail_avatar_crop_preview
import one.meshcore.shared.resources.contacts_detail_avatar_crop_preview_hint
import one.meshcore.shared.resources.contacts_detail_avatar_crop_title
import one.meshcore.shared.ui.IMAGE_OVERLAY_CONTENT_COLOR
import one.meshcore.shared.ui.icons.CloseIconButton
import one.meshcore.shared.ui.icons.ConfirmIconButton
import org.jetbrains.compose.resources.stringResource

private val CROP_GUIDE_STROKE = 2.dp
private const val DIM_OPACITY = 0.5f
private val ZOOM_RANGE = AvatarCropGeometry.MIN_SCALE..AvatarCropGeometry.MAX_SCALE
private const val ZOOM_STEPS = 12

internal fun avatarCropMaskColor(scrim: Color) = scrim.copy(alpha = DIM_OPACITY)

/**
 * Full-screen pan/zoom crop for a picked contact photo, matching iOS `AvatarCropView`.
 * Confirm yields the live [AvatarCropGeometry] so the caller can render the JPEG off the UI thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarCropDialog(image: ImageBitmap, onCancel: () -> Unit, onComplete: (AvatarCropGeometry) -> Unit) {
    var geometry by remember(image) {
        mutableStateOf(
            AvatarCropGeometry(
                cropSize = AvatarCropGeometry.COMPACT_MAX_CROP_SIZE,
                imageWidth = image.width.toFloat(),
                imageHeight = image.height.toFloat(),
            ),
        )
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.scrim,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.contacts_detail_avatar_crop_title)) },
                    navigationIcon = {
                        CloseIconButton(onClick = onCancel, tint = IMAGE_OVERLAY_CONTENT_COLOR)
                    },
                    actions = {
                        ConfirmIconButton(
                            onClick = { onComplete(geometry) },
                            contentDescription = stringResource(Res.string.contacts_detail_avatar_crop_choose),
                            tint = IMAGE_OVERLAY_CONTENT_COLOR,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = IMAGE_OVERLAY_CONTENT_COLOR,
                        navigationIconContentColor = IMAGE_OVERLAY_CONTENT_COLOR,
                        actionIconContentColor = IMAGE_OVERLAY_CONTENT_COLOR,
                    ),
                )
            },
        ) { innerPadding ->
            AvatarCropCanvas(
                image = image,
                geometry = geometry,
                onGeometryChange = { geometry = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AvatarCropCanvas(
    image: ImageBitmap,
    geometry: AvatarCropGeometry,
    onGeometryChange: (AvatarCropGeometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val preview = stringResource(Res.string.contacts_detail_avatar_crop_preview)
    val previewHint = stringResource(Res.string.contacts_detail_avatar_crop_preview_hint)
    val moveUp = stringResource(Res.string.contacts_detail_avatar_crop_move_up)
    val moveDown = stringResource(Res.string.contacts_detail_avatar_crop_move_down)
    val moveLeft = stringResource(Res.string.contacts_detail_avatar_crop_move_left)
    val moveRight = stringResource(Res.string.contacts_detail_avatar_crop_move_right)
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier.background(scheme.scrim), contentAlignment = Alignment.Center) {
        val cropSize = with(density) {
            AvatarCropGeometry.cropSize(
                availableWidth = maxWidth.toPx(),
                availableHeight = maxHeight.toPx(),
                isRegularWidth = maxWidth >= AvatarCropGeometry.REGULAR_WIDTH_DP.dp,
            )
        }
        val resolved = if (geometry.cropSize == cropSize) geometry else geometry.withCropSize(cropSize)
        LaunchedEffect(cropSize) {
            if (geometry.cropSize != cropSize) {
                onGeometryChange(geometry.withCropSize(cropSize))
            }
        }
        val panStep = cropSize * AvatarCropGeometry.PAN_STEP_FRACTION
        val guideStroke = with(density) { CROP_GUIDE_STROKE.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(cropSize) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onGeometryChange(resolved.applyingLive(zoom, pan.x, pan.y))
                    }
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = "$preview. $previewHint"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = resolved.scale,
                        range = ZOOM_RANGE,
                        steps = ZOOM_STEPS,
                    )
                    setProgress { target ->
                        onGeometryChange(resolved.applyingZoomStep(target - resolved.scale))
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(moveUp) {
                            onGeometryChange(resolved.applyingPan(0f, -panStep))
                            true
                        },
                        CustomAccessibilityAction(moveDown) {
                            onGeometryChange(resolved.applyingPan(0f, panStep))
                            true
                        },
                        CustomAccessibilityAction(moveLeft) {
                            onGeometryChange(resolved.applyingPan(-panStep, 0f))
                            true
                        },
                        CustomAccessibilityAction(moveRight) {
                            onGeometryChange(resolved.applyingPan(panStep, 0f))
                            true
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .size(
                        width = with(density) { resolved.baseDisplayWidth.toDp() },
                        height = with(density) { resolved.baseDisplayHeight.toDp() },
                    )
                    .graphicsLayer {
                        scaleX = resolved.scale
                        scaleY = resolved.scale
                        translationX = resolved.offsetX
                        translationY = resolved.offsetY
                    },
            )
            Canvas(Modifier.fillMaxSize()) {
                val hole = Rect(
                    offset = Offset((size.width - cropSize) / 2f, (size.height - cropSize) / 2f),
                    size = Size(cropSize, cropSize),
                )
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(hole)
                }
                drawPath(path = mask, color = avatarCropMaskColor(scheme.scrim))
                drawCircle(
                    color = IMAGE_OVERLAY_CONTENT_COLOR,
                    radius = cropSize / 2f,
                    style = Stroke(width = guideStroke),
                )
            }
        }
    }
}
