package one.meshcore.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_connection_sync_failed_disconnecting
import one.meshcore.shared.resources.localizable_common_accessibility_connect_hint
import one.meshcore.shared.resources.localizable_common_status_connecting
import one.meshcore.shared.resources.localizable_common_status_disconnected
import one.meshcore.shared.resources.localizable_common_status_ready
import one.meshcore.shared.resources.localizable_common_status_syncing
import one.meshcore.shared.resources.localizable_status_pill_sync_failed
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The top-of-screen connection status pill, ported from the iOS `SyncingPillView`/`SyncingPillOverlay`.
 * It is safety chrome: a solid Material capsule (no translucency over content), sliding down from
 * off-screen when a non-hidden state appears and swapping its content with a short cross-fade. Under
 * reduce-motion every transition collapses to instant.
 *
 * The disconnected state is tappable (opens device selection); the others are static status. Every state
 * change is spoken through [PillAnnouncement], the analogue of the iOS VoiceOver `updatesFrequently`
 * trait and sync-failed announcement.
 */
@Composable
fun StatusPill(state: StatusPillState, onDisconnectedTap: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val slideDuration = if (reduceMotion) 0 else SLIDE_DURATION_MS
    val swapDuration = if (reduceMotion) 0 else CONTENT_SWAP_DURATION_MS

    Box(modifier = modifier) {
        PillAnnouncement(state)
        AnimatedVisibility(
            visible = state !is StatusPillState.Hidden,
            enter = slideInVertically(animationSpec = tween(slideDuration)) { -it - OFFSCREEN_EXTRA_PX } +
                fadeIn(tween(slideDuration)),
            exit = slideOutVertically(animationSpec = tween(slideDuration)) { -it - OFFSCREEN_EXTRA_PX } +
                fadeOut(tween(slideDuration)),
        ) {
            // Hold the last non-hidden state so the pill keeps rendering its content through the slide-out
            // instead of blanking the moment the state flips to Hidden (the iOS one-step display lag).
            val shown = rememberLastNonHidden(state)
            AnimatedContent(
                targetState = shown,
                transitionSpec = { fadeIn(tween(swapDuration)) togetherWith fadeOut(tween(swapDuration)) },
                label = "statusPillContent",
            ) { pill ->
                PillCapsule(pill, onDisconnectedTap)
            }
        }
    }
}

/**
 * The pill's spoken text, on a node composed for the pill's whole lifetime. A live region is announced
 * only when a node already in the semantics tree changes its description, and the enclosing
 * [AnimatedVisibility]/[AnimatedContent] build a fresh capsule node for every state — so a live region
 * on the capsule itself can never fire, and the tappable disconnected capsule carries none at all.
 * Hoisting the region here is what makes a state change audible in both branches.
 */
@Composable
private fun PillAnnouncement(state: StatusPillState) {
    val resource = state.announcementResource()
    val spoken = resource?.let { stringResource(it) }.orEmpty()
    PoliteLiveRegion(spoken)
}

/**
 * What the screen reader is interrupted with when the pill enters a state. Only [StatusPillState.Failed]
 * speaks, and it speaks the fuller iOS sentence ("Sync failed. Disconnecting.") rather than the capsule's
 * terse visible label. The progress rungs stay silent on purpose: iOS marks the pill `updatesFrequently`,
 * so VoiceOver reads them only when the pill is focused, and pushing each one at a TalkBack user would
 * talk over the screen they are actually working in. Losing and regaining the link is announced once, by
 * the connection announcer, exactly as iOS announces it.
 */
internal fun StatusPillState.announcementResource(): StringResource? = when (this) {
    is StatusPillState.Failed -> Res.string.localizable_accessibility_connection_sync_failed_disconnecting
    is StatusPillState.Connecting,
    is StatusPillState.Syncing,
    is StatusPillState.Ready,
    is StatusPillState.Disconnected,
    is StatusPillState.Hidden,
    -> null
}

@Composable
private fun PillCapsule(state: StatusPillState, onDisconnectedTap: () -> Unit) {
    val spec = state.visualSpec()
    val text = state.label()
    val isTappable = state is StatusPillState.Disconnected
    val connectHint = stringResource(Res.string.localizable_common_accessibility_connect_hint)

    // A semantics `onClick` only publishes an accessibility action: TalkBack can activate the pill, but an
    // ordinary finger tap lands on nothing. The disconnected pill is the primary connect affordance, so it
    // needs a real pointer handler; `clickable` publishes the accessibility action too.
    val semanticsModifier = if (isTappable) {
        Modifier
            .clickable(onClickLabel = connectHint, onClick = onDisconnectedTap)
            .semantics { contentDescription = "$text. $connectHint" }
    } else {
        Modifier.semantics { contentDescription = text }
    }

    Surface(
        shape = CircleShape,
        color = spec.background(),
        tonalElevation = PILL_ELEVATION,
        shadowElevation = PILL_ELEVATION,
        modifier = semanticsModifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PILL_H_PADDING, vertical = PILL_V_PADDING),
            horizontalArrangement = Arrangement.spacedBy(PILL_ICON_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillGlyph(spec)
            Text(
                text = text,
                color = spec.contentColor(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (state is StatusPillState.Failed) {
                    FontWeight.Bold
                } else {
                    FontWeight.Medium
                },
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun PillGlyph(spec: PillVisual) {
    // Read the animated angle inside graphicsLayer so the infinite spin invalidates only the draw phase;
    // a composition-time read would recompose the glyph every frame for the whole connecting/syncing state.
    val rotation = if (spec.spins) {
        rememberInfiniteTransition(label = "pillSpin").animateFloat(
            initialValue = 0f,
            targetValue = FULL_ROTATION,
            animationSpec = infiniteRepeatable(tween(SPIN_PERIOD_MS, easing = LinearEasing)),
            label = "pillSpinAngle",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val icon = spec.icon ?: return
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = spec.iconColor(),
        modifier = Modifier.graphicsLayer { rotationZ = rotation.value }.clearAndSetSemantics { },
    )
}

/** The last non-hidden [StatusPillState], so the pill renders content while sliding out. */
@Composable
private fun rememberLastNonHidden(state: StatusPillState): StatusPillState {
    val holder = remember { mutableStateOf<StatusPillState>(StatusPillState.Connecting) }
    if (state !is StatusPillState.Hidden) holder.value = state
    return holder.value
}

@Composable
private fun StatusPillState.label(): String = when (this) {
    is StatusPillState.Connecting -> stringResource(Res.string.localizable_common_status_connecting)
    is StatusPillState.Syncing -> stringResource(Res.string.localizable_common_status_syncing)
    is StatusPillState.Ready -> stringResource(Res.string.localizable_common_status_ready)
    is StatusPillState.Disconnected -> stringResource(Res.string.localizable_common_status_disconnected)
    is StatusPillState.Failed -> stringResource(Res.string.localizable_status_pill_sync_failed)
    is StatusPillState.Hidden -> ""
}

private data class PillVisual(
    val icon: ImageVector?,
    val spins: Boolean,
    val iconColor: @Composable () -> Color,
    val contentColor: @Composable () -> Color,
    val background: @Composable () -> Color,
)

@Composable
private fun neutralBackground(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

private fun StatusPillState.visualSpec(): PillVisual = when (this) {
    is StatusPillState.Connecting, is StatusPillState.Syncing -> PillVisual(
        icon = AppIcons.Sync,
        spins = true,
        iconColor = { MaterialTheme.colorScheme.primary },
        contentColor = { MaterialTheme.colorScheme.onSurface },
        background = { neutralBackground() },
    )
    is StatusPillState.Ready -> PillVisual(
        icon = AppIcons.CheckCircle,
        spins = false,
        iconColor = { SAFETY_READY_GREEN },
        contentColor = { MaterialTheme.colorScheme.onSurface },
        background = { neutralBackground() },
    )
    is StatusPillState.Disconnected -> PillVisual(
        icon = AppIcons.Warning,
        spins = false,
        iconColor = { WARNING_ORANGE },
        contentColor = { WARNING_ORANGE },
        background = { neutralBackground() },
    )
    is StatusPillState.Failed -> PillVisual(
        icon = AppIcons.Warning,
        spins = false,
        iconColor = { MaterialTheme.colorScheme.error },
        contentColor = { MaterialTheme.colorScheme.error },
        background = { MaterialTheme.colorScheme.error.copy(alpha = FAILED_PILL_FILL_ALPHA) },
    )
    is StatusPillState.Hidden -> PillVisual(
        icon = null,
        spins = false,
        iconColor = { Color.Unspecified },
        contentColor = { Color.Unspecified },
        background = { neutralBackground() },
    )
}

// Disconnected warning stays a fixed orange so outdoor glare does not wash out a theme tint.
private val WARNING_ORANGE = Color(0xFFE65100)

/** Failed-sync pill fill opacity on theme error. */
internal const val FAILED_PILL_FILL_ALPHA = 0.15f

private const val SLIDE_DURATION_MS = 300
private const val CONTENT_SWAP_DURATION_MS = 300
private const val SPIN_PERIOD_MS = 1000
private const val FULL_ROTATION = 360f
private const val OFFSCREEN_EXTRA_PX = 100

private val PILL_ELEVATION = 3.dp
private val PILL_H_PADDING = 14.dp
private val PILL_V_PADDING = 8.dp
private val PILL_ICON_GAP = 6.dp
