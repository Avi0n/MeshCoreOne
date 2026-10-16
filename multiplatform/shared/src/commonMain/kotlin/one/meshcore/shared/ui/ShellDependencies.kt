package one.meshcore.shared.ui

/**
 * Everything the shared shell, status pill, battery indicator, BLE menu, and onboarding flow need from a
 * platform. The Android app builds a concrete instance backed by the foreground service's graph; the
 * desktop app supplies one with inert connection actions because desktop has no radio transport.
 *
 * Bundling the surface here keeps the composables platform-neutral: they read the flows and invoke the
 * callbacks, and never reach a `ConnectionManager`, permission API, or BLE scanner directly.
 * Construction takes six surfaces; each field is still read on this class.
 */
class ShellDependencies(
    val session: ShellSessionInputs,
    val services: ShellServiceInputs,
    val sharing: ShellSharingInputs,
    val preferences: ShellPreferenceInputs,
    val radio: ShellRadioInputs,
    val maintenance: ShellMaintenanceInputs,
) : ShellSessionHost by session,
    ShellServiceHost by services,
    ShellSharingHost by sharing,
    ShellPreferenceHost by preferences,
    ShellRadioHost by radio,
    ShellMaintenanceHost by maintenance {

    /** Contact QR share uses a subject (iOS `ShareLink` subject); other shares pass body only. */
    fun sharePlainText(text: String, subject: String? = null) {
        val withSubject = shareTextWithSubject
        if (subject != null && withSubject != null) {
            withSubject(text, subject)
        } else {
            shareText(text)
        }
    }
}
