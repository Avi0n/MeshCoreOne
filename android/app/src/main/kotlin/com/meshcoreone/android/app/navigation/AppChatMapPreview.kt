// PortedFrom: MC1/Views/Chats/Components/Fragments/MapPreviewFragmentView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapSnapshotLayout.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.container.AppChatMapPreviews
import com.meshcoreone.android.app.container.ChatMapNetwork
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppMapStrings
import com.meshcoreone.android.core.maps.MapSnapshotRequest
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.composer.MessageLinkTokenizer
import com.meshcoreone.android.feature.chats.composer.MessageTextFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun chatMapPreviewCoordinate(text: String, enabled: Boolean): Coordinate? =
    if (enabled) MessageTextFormatter.format(text).mapCoordinate else null

private data class ChatMapImage(val bitmap: Bitmap?, val resolved: Boolean)

@Composable
internal fun AppChatMapPreview(
    previews: AppChatMapPreviews,
    coordinate: Coordinate,
    onMap: (String) -> Unit,
) {
    val network by previews.network.collectAsStateWithLifecycle()
    val dark = LocalMeshTheme.current.frame.colorScheme == ColorScheme.DARK
    val request = remember(coordinate, dark, network) {
        MapSnapshotRequest(coordinate.latitude, coordinate.longitude, dark, network != ChatMapNetwork.Online)
    }
    fun readImage() = ChatMapImage(previews.snapshots.value(request), previews.snapshots.isResolved(request))
    val image by produceState(readImage(), previews, request) {
        coroutineScope {
            fun refresh() {
                value = readImage()
                if (!value.resolved && request.isOffline == (previews.network.value != ChatMapNetwork.Online)) {
                    previews.request(request)
                }
            }
            // Subscribe before requesting: an immediate cached/fake render must not strand the loading state.
            launch(start = CoroutineStart.UNDISPATCHED) {
                previews.snapshots.resolutions.collect { if (it == request) refresh() }
            }
            refresh()
            awaitCancellation()
        }
    }
    val label = stringResource(AppMapStrings.mapPreviewAccessibilityLabel)
    val hint = stringResource(AppMapStrings.mapPreviewAccessibilityHint)
    val retry = stringResource(AppMapStrings.mapPreviewRetryButtonAccessibilityLabel)
    val coordinateText = String.format(Locale.getDefault(), "%.5f, %.5f", coordinate.latitude, coordinate.longitude)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(
            Modifier.width(250.dp).height(150.dp)
                .clickable(onClickLabel = hint, role = Role.Button) { onMap(MessageLinkTokenizer.mapUrl(coordinate)) }
                .semantics { contentDescription = label; stateDescription = coordinateText },
        ) {
            image.bitmap?.let { bitmap ->
                Image(bitmap.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                val color = MaterialTheme.colorScheme.error
                Canvas(Modifier.size(24.dp).align(Alignment.Center).offset(y = (-12).dp).testTag("chat-map-pin")) {
                    val scale = size.width / 24
                    val pin = Path().apply {
                        moveTo(12 * scale, 24 * scale)
                        cubicTo(10 * scale, 20 * scale, 3 * scale, 13 * scale, 3 * scale, 9 * scale)
                        cubicTo(3 * scale, -3 * scale, 21 * scale, -3 * scale, 21 * scale, 9 * scale)
                        cubicTo(21 * scale, 13 * scale, 14 * scale, 20 * scale, 12 * scale, 24 * scale)
                        close()
                    }
                    drawPath(pin, color)
                    drawCircle(Color.White, 3 * scale, androidx.compose.ui.geometry.Offset(12 * scale, 9 * scale))
                }
            }
            if (!image.resolved) CircularProgressIndicator(Modifier.size(32.dp).align(Alignment.Center).testTag("chat-map-loading"))
            else if (image.bitmap == null) {
                Icon(MeshSymbol.MAP.vector, null, Modifier.size(48.dp).align(Alignment.Center))
                IconButton(onClick = { previews.retry(request) },
                    modifier = Modifier.align(Alignment.TopEnd).sharedTouchTarget().semantics { contentDescription = retry }) {
                    Icon(MeshSymbol.REPEAT.vector, null)
                }
            }
        }
    }
}
