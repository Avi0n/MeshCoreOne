// AndroidOnly: WP-303 First-run gate: onboarding replaces the main shell until hasCompletedOnboarding is set (Swift ContentView rule).
package com.meshcoreone.android.app.container.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.feature.onboarding.OnboardingEntry
import kotlinx.coroutines.flow.first

/**
 * First-run gate: onboarding while the persisted completion flag is unset, the main shell afterwards. Completion
 * persists the flag (through the flag store, in the feature) and navigates to the Chats tab root here.
 * Without [onboarding] (container not built yet, or construction failed) the shell is shown, never a fake flow.
 */
@Composable
fun OnboardingGate(
    coordinator: NavigationCoordinator,
    onboarding: AppOnboarding?,
    shell: @Composable (AppOnboarding?) -> Unit,
) {
    onboarding?.pairingState?.let { SystemPairingSetupHost(it) }
    val completed by (onboarding?.hasCompleted ?: ALWAYS_COMPLETED).collectAsState()
    // Independent of the onboarding screens, which leave composition in the same frame the flag flips.
    LaunchedEffect(onboarding) {
        val flag = onboarding?.hasCompleted ?: return@LaunchedEffect
        if (flag.value) return@LaunchedEffect
        flag.first { it }
        coordinator.navigate(FeatureRoute(FeatureId.CHATS))
    }
    if (onboarding == null || completed) {
        shell(onboarding)
    } else {
        OnboardingEntry(
            route = FeatureRoute(FeatureId.ONBOARDING),
            onNavigate = coordinator::navigate,
            dependencies = onboarding,
        )
    }
}

private val ALWAYS_COMPLETED = kotlinx.coroutines.flow.MutableStateFlow(true)

/** What the app root learned about onboarding bindings: [bindings] is null when none exist (shell only). */
class OnboardingResolution(val bindings: AppOnboarding?)
