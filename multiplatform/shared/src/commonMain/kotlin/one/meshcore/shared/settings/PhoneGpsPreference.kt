package one.meshcore.shared.settings

/**
 * Whether a Phone-GPS preference write should be blocked because location is already denied.
 *
 * Mirrors iOS `LocationSettingsSection`: turning auto-update on while the source is the phone, or
 * switching the source to the phone, is rejected so the node does not look like it is following a GPS
 * the OS will never supply. The caller keeps the previous value and shows the location-denied alert.
 */
fun shouldBlockPhoneGpsPreference(wantsPhoneGps: Boolean, locationDenied: Boolean): Boolean =
    wantsPhoneGps && locationDenied
