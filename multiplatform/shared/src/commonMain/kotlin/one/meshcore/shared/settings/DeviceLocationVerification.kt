package one.meshcore.shared.settings

/** Thrown when the radio reads back a position other than the one just written. */
class DeviceWriteVerificationFailed(val expected: String, val actual: String) :
    Exception("Device write verification failed: expected $expected, got $actual")

/**
 * Thrown when the on-board GPS toggle does not stick. Two sentence variants (expected on vs off)
 * rather than interpolating On/Off, matching iOS `SettingsServiceError.deviceGPSVerificationFailed`.
 */
class DeviceGpsVerificationFailed(val expectedEnabled: Boolean, val actualEnabled: Boolean) :
    Exception(
        "Device GPS setting was not saved. Expected '${if (expectedEnabled) "On" else "Off"}' " +
            "but device reports '${if (actualEnabled) "On" else "Off"}'.",
    )
