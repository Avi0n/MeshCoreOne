package one.meshcore.shared.onboarding

/**
 * The signals the onboarding resume decision ([suggestedStartingPath]) reads.
 *
 * @param pairedAccessoriesCount the system pairing-registry count (AccessorySetupKit on iOS,
 *   Companion Device Manager on Android); 0 before that registry activates and always 0 on desktop.
 * @param lastConnectedDeviceId the platform-independent "this install has actually connected a radio"
 *   signal: set only after a real successful connect and cleared only on forget, never minted by a
 *   backup import.
 * @param locationPermissionDetermined whether location authorization is no longer "not determined".
 * @param notificationPermissionDetermined whether notification authorization is no longer "not determined".
 * @param regionAlreadySet whether a region has already been chosen (lets a returning user skip the region step).
 */
data class OnboardingResumeInputs(
    val hasCompletedOnboarding: Boolean,
    val pairedAccessoriesCount: Int,
    val lastConnectedDeviceId: String?,
    val locationPermissionDetermined: Boolean,
    val notificationPermissionDetermined: Boolean,
    val regionAlreadySet: Boolean,
)
