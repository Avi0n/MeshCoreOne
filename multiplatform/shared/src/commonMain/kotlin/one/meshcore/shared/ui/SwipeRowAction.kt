package one.meshcore.shared.ui

import androidx.compose.ui.graphics.vector.ImageVector

/** An explicit row shortcut; swiping only reveals it, so destructive actions always need a tap. */
data class SwipeRowAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)
