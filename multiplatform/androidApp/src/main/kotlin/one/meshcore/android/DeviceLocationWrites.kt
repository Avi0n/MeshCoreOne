package one.meshcore.android

import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.DeviceGpsVerificationFailed
import one.meshcore.shared.settings.DeviceWriteVerificationFailed
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.settings.SelfAdvertRadio
import kotlin.math.abs

/** The scaled-integer resolution the radio stores coordinates at; the read-back compares at this scale. */
private const val COORDINATE_SCALE = 1_000_000.0

/** Two scaled units (~0.2 m) absorbs the floating-point conversion, matching the iOS tolerance. */
private const val COORDINATE_TOLERANCE_SCALED = 2L

private const val DEVICE_GPS_VAR = "gps"
private const val DEVICE_GPS_ON = "1"
private const val DEVICE_GPS_OFF = "0"

/**
 * Sets a manual position and proves it stuck, mirroring iOS `SettingsService.setManualLocationVerified`.
 * A radio with its on-board GPS switched on overwrites the written coordinate with its own fix within
 * seconds, so the GPS is turned off first; without that the node advertises a position other than the one
 * the picker showed.
 */
internal suspend fun MeshSession.setManualCoordinatesVerified(
    latitude: Double,
    longitude: Double,
    logger: PersistentLogger? = null,
) {
    if (getCustomVars()[DEVICE_GPS_VAR] == DEVICE_GPS_ON) {
        setDeviceGpsEnabledVerified(false)
    }

    setCoordinatesVerified(latitude, longitude, logger)
}

/**
 * Writes a position and proves it stuck, mirroring iOS `SettingsService.setLocationVerified`. The
 * on-board GPS is left as it is: a pre-advert refresh from the phone must not silently switch off a GPS
 * the user chose, which is what separates this from [setManualCoordinatesVerified].
 */
internal suspend fun MeshSession.setCoordinatesVerified(
    latitude: Double,
    longitude: Double,
    logger: PersistentLogger? = null,
) {
    setCoordinates(latitude, longitude)

    val info = getSelfInfo()
    val scaledLatSent = latitude.scaledCoordinate()
    val scaledLonSent = longitude.scaledCoordinate()
    val scaledLatReceived = info.latitude.scaledCoordinate()
    val scaledLonReceived = info.longitude.scaledCoordinate()
    val latDiff = abs(scaledLatSent - scaledLatReceived)
    val lonDiff = abs(scaledLonSent - scaledLonReceived)
    if (latDiff > COORDINATE_TOLERANCE_SCALED || lonDiff > COORDINATE_TOLERANCE_SCALED) {
        logger?.error(
            "[Location] Verification failed - sent: ($scaledLatSent, $scaledLonSent), " +
                "received: ($scaledLatReceived, $scaledLonReceived), diff: (lat=$latDiff, lon=$lonDiff)",
        )
        if (scaledLatSent == 0L && scaledLonSent == 0L) {
            logger?.warning(
                "[Location] Clear location failed - device reports non-zero coordinates. " +
                    "Device may have active GPS or firmware doesn't support (0,0).",
            )
        }
        throw DeviceWriteVerificationFailed(
            expected = "($latitude, $longitude)",
            actual = "(${info.latitude}, ${info.longitude})",
        )
    }
}

/**
 * The radio's on-board GPS capability and state, mirroring iOS `SettingsService.getDeviceGPSState`: a
 * radio without the custom var reports no GPS at all, which is what hides the device-GPS controls.
 */
internal suspend fun MeshSession.deviceGpsState(): DeviceGpsState {
    val value = getCustomVars()[DEVICE_GPS_VAR] ?: return DeviceGpsState.unsupported
    return DeviceGpsState(isSupported = true, isEnabled = value == DEVICE_GPS_ON)
}

/**
 * The connected radio as [SelfAdvertSender] sees it: the Device row supplies the identity the per-radio
 * preferences are keyed by and the advert policy that authorizes sharing, the live session performs the
 * writes and the transmit.
 */
internal class SessionSelfAdvertRadio(
    private val session: MeshSession,
    private val resolveDeviceId: suspend () -> String?,
    private val readLocationSettings: suspend () -> LocationSettingsSnapshot?,
    private val persistCoordinates: suspend (Double, Double) -> Unit = { _, _ -> },
    private val logger: PersistentLogger? = null,
) : SelfAdvertRadio {
    override suspend fun deviceId(): String? = resolveDeviceId()

    override suspend fun sharesLocationPublicly(): Boolean = readLocationSettings()?.shareLocationPublicly == true

    override suspend fun writeCoordinate(latitude: Double, longitude: Double) {
        session.setCoordinatesVerified(latitude, longitude, logger)
        persistCoordinates(latitude, longitude)
    }

    override suspend fun deviceGpsState(): DeviceGpsState = session.deviceGpsState()

    override suspend fun enableDeviceGps() = session.setDeviceGpsEnabledVerified(true)

    override suspend fun sendAdvertisement(flood: Boolean) = session.sendAdvertisement(flood)
}

/** Writes the on-board GPS toggle and confirms it, mirroring iOS `setDeviceGPSEnabledVerified`. */
internal suspend fun MeshSession.setDeviceGpsEnabledVerified(enabled: Boolean) {
    val expected = if (enabled) DEVICE_GPS_ON else DEVICE_GPS_OFF
    setCustomVar(DEVICE_GPS_VAR, expected)
    val actual = getCustomVars()[DEVICE_GPS_VAR]
    if (actual != expected) {
        throw DeviceGpsVerificationFailed(
            expectedEnabled = enabled,
            actualEnabled = actual == DEVICE_GPS_ON,
        )
    }
}

/** Truncates toward zero at [COORDINATE_SCALE], the same conversion the firmware applies on the wire. */
private fun Double.scaledCoordinate(): Long = (this * COORDINATE_SCALE).toLong()
