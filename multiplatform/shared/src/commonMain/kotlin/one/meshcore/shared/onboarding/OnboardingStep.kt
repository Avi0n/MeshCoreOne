package one.meshcore.shared.onboarding

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The onboarding steps, in linear forward order, ported from the iOS `OnboardingStep`. The serialized
 * names are pinned to the Swift raw values (the lowercase case names) because the path may be persisted;
 * a rename must not change the on-disk token.
 */
@Serializable
enum class OnboardingStep {
    @SerialName("welcome")
    WELCOME,

    @SerialName("permissions")
    PERMISSIONS,

    @SerialName("pair")
    PAIR,

    @SerialName("region")
    REGION,

    @SerialName("preset")
    PRESET,
}
