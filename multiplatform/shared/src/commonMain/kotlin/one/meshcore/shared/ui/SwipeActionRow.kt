package one.meshcore.shared.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Reveals leading/trailing actions in a LazyColumn without dismissing or mutating the row on full swipe. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeActionRow(
    leadingActions: List<SwipeRowAction>,
    trailingActions: List<SwipeRowAction>,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier, consumeOpenTap: () -> Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val leadingWidth = with(density) { (SWIPE_ACTION_WIDTH * leadingActions.size).toPx() }
    val trailingWidth = with(density) { (SWIPE_ACTION_WIDTH * trailingActions.size).toPx() }
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offset, target) { value, _ -> offset = value }
        }
    }

    val dragState = rememberDraggableState { delta ->
        offset = (offset + delta).coerceIn(-trailingWidth, leadingWidth)
    }

    Box(modifier = modifier.fillMaxWidth().clipToBounds()) {
        Box(Modifier.matchParentSize()) {
            Row(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight()) {
                leadingActions.forEach { action -> SwipeActionButton(action) { settle(0f) } }
            }
            Row(modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                trailingActions.forEach { action -> SwipeActionButton(action) { settle(0f) } }
            }
        }
        content(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { settleJob?.cancel() },
                    onDragStopped = { settle(swipeRevealTarget(offset, leadingWidth, trailingWidth)) },
                ),
            {
                if (offset != 0f) {
                    settle(0f)
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun SwipeActionButton(action: SwipeRowAction, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            onClose()
            action.onClick()
        },
        enabled = action.enabled,
        shape = RectangleShape,
        color = if (action.destructive) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (action.destructive) scheme.onErrorContainer else scheme.onSecondaryContainer,
        modifier = Modifier.width(SWIPE_ACTION_WIDTH).fillMaxHeight(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                action.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A full swipe can open actions but never invokes one; the revealed button still requires a tap. */
internal fun swipeRevealTarget(offset: Float, leadingWidth: Float, trailingWidth: Float): Float = when {
    offset >= leadingWidth / 2 && leadingWidth > 0f -> leadingWidth
    offset <= -trailingWidth / 2 && trailingWidth > 0f -> -trailingWidth
    else -> 0f
}

private val SWIPE_ACTION_WIDTH = 80.dp
