package one.meshcore.shared.onboarding

/**
 * Decides the onboarding `NavigationStack` starting path — the steps pushed above the always-present
 * Welcome root — ported from the iOS `OnboardingState.suggestedStartingPath`. An empty result starts a
 * brand-new user at Welcome (or means onboarding is done).
 *
 * The resume guard is the load-bearing part: resume only when the registry reports a device OR a real
 * connect happened ([OnboardingResumeInputs.lastConnectedDeviceId]). Resuming on a raw saved-device
 * count would wrongly resume for backup-restored shadow rows and demoted ghosts.
 */
fun suggestedStartingPath(inputs: OnboardingResumeInputs): List<OnboardingStep> {
    if (inputs.hasCompletedOnboarding) return emptyList()
    if (inputs.pairedAccessoriesCount == 0 && inputs.lastConnectedDeviceId == null) return emptyList()

    val permissionsHandled = inputs.locationPermissionDetermined && inputs.notificationPermissionDetermined
    if (!permissionsHandled) return listOf(OnboardingStep.PERMISSIONS)

    return if (inputs.regionAlreadySet) {
        listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.PRESET)
    } else {
        listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION)
    }
}
