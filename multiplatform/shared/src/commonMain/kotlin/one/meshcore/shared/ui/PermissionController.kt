package one.meshcore.shared.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * Requests runtime permissions and re-reads their state on resume (a user may toggle them in system
 * Settings). Mirrors the iOS `PermissionsCoordinator` re-check-on-`scenePhase`-active behavior.
 */
interface PermissionController {
    val state: StateFlow<PermissionUiState>

    /** Requests [permission] via the platform prompt; the result lands in [state]. */
    fun request(permission: OnboardingPermission)

    /** Re-reads current grant state (call on foreground/resume). */
    fun refresh()
}
