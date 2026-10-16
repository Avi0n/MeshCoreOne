package one.meshcore.shared.chat.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import one.meshcore.shared.ui.rememberAppHaptics
import one.meshcore.shared.ui.rememberReduceMotion
import kotlin.math.PI

/** iOS `MessageActionsPresentation.longPressInDelay`. */
internal const val MESSAGE_BUBBLE_LONG_PRESS_IN_DELAY_MS = 100L

/** iOS `MessageActionsPresentation.longPressPressedScale`. */
internal const val MESSAGE_BUBBLE_LONG_PRESS_SCALE = 0.95f

/** iOS `MessageActionsPresentation.longPressSpringResponse`. */
private const val MESSAGE_BUBBLE_LONG_PRESS_SPRING_RESPONSE_S = 0.7

/** SwiftUI spring `response` 0.7 as Compose stiffness `(2π / response)²`. */
internal val MESSAGE_BUBBLE_LONG_PRESS_SPRING_STIFFNESS =
    (2.0 * PI / MESSAGE_BUBBLE_LONG_PRESS_SPRING_RESPONSE_S).let { it * it }.toFloat()

/**
 * Whether Reduce Motion skips the 100 ms press-in delay so the scale tracks the finger immediately,
 * matching iOS `messageBubbleLongPressEffect` (`animation: nil`).
 */
internal fun messageBubblePressInDelayMs(isPressing: Boolean, reduceMotion: Boolean): Long =
    if (!reduceMotion && isPressing) MESSAGE_BUBBLE_LONG_PRESS_IN_DELAY_MS else 0L

/**
 * Press-in shrink plus the actions-sheet long-press, matching iOS `messageBubbleLongPressGesture`
 * / `messageBubbleLongPressEffect`. Chat and room bubbles share this so the hold feels identical.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.messageBubbleLongPress(onLongPress: () -> Unit, onClick: () -> Unit = {}, role: Role? = null): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    var visuallyPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isPressed, reduceMotion) {
        val delayMs = messageBubblePressInDelayMs(isPressing = isPressed, reduceMotion = reduceMotion)
        if (delayMs > 0L) delay(delayMs)
        visuallyPressed = isPressed
    }
    val scale by animateFloatAsState(
        targetValue = if (visuallyPressed) MESSAGE_BUBBLE_LONG_PRESS_SCALE else 1f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = 1f,
                stiffness = MESSAGE_BUBBLE_LONG_PRESS_SPRING_STIFFNESS,
            )
        },
        label = "messageBubblePressScale",
    )
    val haptics = rememberAppHaptics()
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            role = role,
            onClick = onClick,
            onLongClick = {
                haptics.tap()
                onLongPress()
            },
        )
}
