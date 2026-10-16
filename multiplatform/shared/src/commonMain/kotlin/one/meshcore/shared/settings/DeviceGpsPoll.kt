package one.meshcore.shared.settings

/**
 * Whether Settings → Location should keep asking the radio for a first fix.
 * Polling runs only while auto-update is fed by the device GPS and a refresh callback exists.
 */
fun shouldPollDeviceGps(
    autoUpdateLocation: Boolean,
    gpsSource: GpsSource,
    deviceGpsEnabled: Boolean,
    hasRefresh: Boolean,
): Boolean = autoUpdateLocation &&
    gpsSource == GpsSource.DEVICE &&
    deviceGpsEnabled &&
    hasRefresh
