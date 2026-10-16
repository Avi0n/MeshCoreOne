package one.meshcore.shared.tools.ui

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_trace_jump_hint
import one.meshcore.shared.resources.contacts_trace_jump_label
import one.meshcore.shared.resources.contacts_trace_run_below
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Whether a child's window bounds overlap the window. Used to hide the Jump to Path control when the
 * Run Trace control is on screen, matching iOS `RunTraceSectionView` onAppear / onDisappear.
 */
internal fun isLaidOutInWindow(bounds: Rect, windowWidth: Float, windowHeight: Float): Boolean {
    if (bounds.width <= 0f || bounds.height <= 0f) return false
    return bounds.overlaps(Rect(left = 0f, top = 0f, right = windowWidth, bottom = windowHeight))
}

/** Extended FAB that scrolls to Run Trace when that button is off-screen. iOS `JumpToPathButton`. */
@Composable
internal fun JumpToPathButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.contacts_trace_jump_label)
    val hint = stringResource(Res.string.contacts_trace_jump_hint)
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(imageVector = AppIcons.KeyboardArrowDown, contentDescription = null) },
        text = { Text(stringResource(Res.string.contacts_trace_run_below)) },
        modifier = modifier.semantics { contentDescription = "$label. $hint" },
    )
}
