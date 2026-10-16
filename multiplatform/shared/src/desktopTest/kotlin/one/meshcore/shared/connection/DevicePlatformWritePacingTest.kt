package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The BLE write pacing each chip family needs, against the Swift `DevicePlatform.recommendedWritePacing`
 * (`MC1Services/Sources/MC1Services/Connection/DevicePlatform.swift`): ESP32 60ms, nRF52 25ms, and
 * an unrecognized radio the ESP32-safe 60ms. Under-pacing an ESP32 overruns its BLE RX queue, so a zero
 * here is a dropped frame on a real radio.
 */
class DevicePlatformWritePacingTest {
    @Test
    fun esp32PacesAtSixtyMilliseconds() {
        assertEquals(60L, DevicePlatform.ESP32.recommendedWritePacingMillis)
    }

    @Test
    fun nrf52PacesAtTwentyFiveMilliseconds() {
        assertEquals(25L, DevicePlatform.NRF52.recommendedWritePacingMillis)
    }

    @Test
    fun unrecognizedRadioTakesTheEsp32SafeValue() {
        assertEquals(
            DevicePlatform.ESP32.recommendedWritePacingMillis,
            DevicePlatform.UNKNOWN.recommendedWritePacingMillis,
        )
    }

    @Test
    fun everyPlatformPaces() {
        DevicePlatform.entries.forEach { platform ->
            assertEquals(true, platform.recommendedWritePacingMillis > 0, "$platform must pace acknowledged writes")
        }
    }

    @Test
    fun detectedModelsCarryTheirFamilyPacing() {
        assertEquals(60L, resolveDevicePlatform("Heltec V3", TransportType.BLUETOOTH).recommendedWritePacingMillis)
        assertEquals(25L, resolveDevicePlatform("RAK 4631", TransportType.BLUETOOTH).recommendedWritePacingMillis)
        assertEquals(60L, resolveDevicePlatform("no such radio", TransportType.BLUETOOTH).recommendedWritePacingMillis)
    }

    @Test
    fun platformDetectLinesMatchIos() {
        assertEquals(
            "[BLE] Platform detected: Heltec V3 -> esp32, write pacing: 0.06s",
            blePlatformDetectedLine("Heltec V3", DevicePlatform.ESP32, 60L),
        )
        assertEquals(
            "[BLE] Platform detected: RAK 4631 -> nrf52, write pacing: 0.025s",
            blePlatformDetectedLine("RAK 4631", DevicePlatform.NRF52, 25L),
        )
        assertEquals(
            "[WiFi] Platform detected: Heltec V3 -> esp32",
            wifiPlatformDetectedLine("Heltec V3", DevicePlatform.ESP32),
        )
    }
}
