package one.meshcore.shared.settings

/**
 * A radio that already has its GPS on is the source until the user says otherwise.
 * Overriding it with the phone default would silently fight the radio's own fix.
 * Once the user has stored a source, that choice stands even when the radio GPS is on.
 */
fun adoptRadioGpsAsSource(deviceGpsEnabled: Boolean, hasSetGpsSource: Boolean): Boolean =
    deviceGpsEnabled && !hasSetGpsSource
