package one.meshcore.shared.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How a tab's content slot should render inside the adaptive shell.
 *
 * - [CompactStacked]: phone — list and pushed detail swap in one column.
 * - [WideList]: tablet/desktop content column — hub list only.
 * - [WideDetail]: tablet/desktop detail column — selection or empty state.
 *
 * Tools and Settings use this so the wide shell can list-detail without forking AppRoot wiring.
 */
enum class ShellContentRole {
    CompactStacked,
    WideList,
    WideDetail,
}

/** Defaults to phone stacking so screens outside the shell stay correct. */
val LocalShellContentRole = staticCompositionLocalOf { ShellContentRole.CompactStacked }
