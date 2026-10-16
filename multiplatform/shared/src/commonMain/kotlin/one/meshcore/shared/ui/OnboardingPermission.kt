package one.meshcore.shared.ui

/** The runtime permissions the onboarding permission step manages. */
enum class OnboardingPermission {
    NOTIFICATIONS,
    LOCATION,

    /**
     * BLUETOOTH_SCAN + BLUETOOTH_CONNECT, treated as one card. Android-new: iOS surfaces BLE permission
     * through AccessorySetupKit at pair time and has no onboarding card.
     */
    BLUETOOTH,

    /**
     * CAMERA, requested at contact/channel QR-scan time rather than during onboarding, so there is no
     * onboarding card for it; the scan screen requests it and routes a denial to the scan controller.
     */
    CAMERA,
}
