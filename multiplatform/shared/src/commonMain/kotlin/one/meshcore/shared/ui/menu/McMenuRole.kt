package one.meshcore.shared.ui.menu

/**
 * Semantic role for a menu or action-sheet item. Call sites order items so [Destructive] is last
 * in its group; [Selected] implies a trailing check (never a text suffix).
 */
enum class McMenuRole {
    Default,
    Destructive,
    Selected,
}

/** Destructive keeps its color when the row is also selected; the check is chosen separately. */
internal fun mcMenuEffectiveRole(role: McMenuRole, selected: Boolean): McMenuRole = when {
    role == McMenuRole.Destructive -> McMenuRole.Destructive
    selected || role == McMenuRole.Selected -> McMenuRole.Selected
    else -> McMenuRole.Default
}

internal fun mcMenuShowsCheck(role: McMenuRole, selected: Boolean): Boolean = selected || role == McMenuRole.Selected
