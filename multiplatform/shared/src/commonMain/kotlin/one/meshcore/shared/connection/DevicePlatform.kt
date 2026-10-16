package one.meshcore.shared.connection

/**
 * The radio's chip family, derived from the model string the handshake reports. Mirrors the Swift
 * `DevicePlatform`.
 *
 * Detection matches model substrings rather than vendor prefixes, because Heltec, RAK, Seeed and Elecrow
 * all ship devices on both chip families. An unrecognized model stays [UNKNOWN], which every policy built
 * on it treats as the conservative case.
 */
enum class DevicePlatform {
    ESP32,
    NRF52,
    UNKNOWN,
}

/**
 * The chip family [model] names, or [DevicePlatform.UNKNOWN] when no rule matches.
 *
 * Ordering matters: the first match wins, so a more specific pattern has to precede a general one.
 */
fun detectDevicePlatform(model: String): DevicePlatform =
    PLATFORM_RULES.firstOrNull { (substring, _) -> model.contains(substring, ignoreCase = true) }?.second
        ?: DevicePlatform.UNKNOWN

/**
 * The chip family to assume for [model] reached over [transportType].
 *
 * A WiFi radio is always ESP32-class — no nRF52 part ships a WiFi radio and the WiFi companion firmware is
 * ESP32-only — so an unrecognized model over WiFi resolves to [DevicePlatform.ESP32]. Bluetooth keeps the
 * [DevicePlatform.UNKNOWN] fallback, which is the conservative half of every policy below.
 */
fun resolveDevicePlatform(model: String, transportType: TransportType?): DevicePlatform {
    val detected = detectDevicePlatform(model)
    if (transportType == TransportType.WIFI && detected == DevicePlatform.UNKNOWN) return DevicePlatform.ESP32
    return detected
}

fun blePlatformDetectedLine(model: String, platform: DevicePlatform, pacingMillis: Long): String =
    "[BLE] Platform detected: $model -> ${platform.name.lowercase()}, write pacing: " +
        "${pacingMillis / PACING_MILLIS_PER_SECOND}s"

fun wifiPlatformDetectedLine(model: String, platform: DevicePlatform): String =
    "[WiFi] Platform detected: $model -> ${platform.name.lowercase()}"

private const val PACING_MILLIS_PER_SECOND: Double = 1000.0

// 60ms is what the ESP32 BLE stack needs between acknowledged writes; below it the radio's RX queue
// overruns during a burst. nRF52 needs only light pacing. Ported from the Swift
// `DevicePlatform.recommendedWritePacing`.
private const val ESP32_WRITE_PACING_MILLIS = 60L
private const val NRF52_WRITE_PACING_MILLIS = 25L
private const val ESP32_CHANNEL_SYNC_SKIP_WINDOW_SECONDS = 30.0

/**
 * The delay to leave between acknowledged BLE writes to this chip family, in milliseconds.
 *
 * An unrecognized radio takes the ESP32-safe value: over-pacing an nRF52 costs throughput, while
 * under-pacing an ESP32 loses frames. Unacknowledged Write Commands skip pacing entirely — see
 * [one.meshcore.shared.transport.BleWriteThrottle].
 */
val DevicePlatform.recommendedWritePacingMillis: Long
    get() = when (this) {
        DevicePlatform.ESP32 -> ESP32_WRITE_PACING_MILLIS
        DevicePlatform.NRF52 -> NRF52_WRITE_PACING_MILLIS
        DevicePlatform.UNKNOWN -> ESP32_WRITE_PACING_MILLIS
    }

/** ESP32 only: skip a channel re-sync if a clean or attempted pass landed inside this window. */
val DevicePlatform.channelSyncSkipWindowSeconds: Double
    get() = when (this) {
        DevicePlatform.ESP32 -> ESP32_CHANNEL_SYNC_SKIP_WINDOW_SECONDS
        DevicePlatform.NRF52, DevicePlatform.UNKNOWN -> 0.0
    }

/**
 * Whether this channel phase may be skipped. A forced full sync and a zero window always run.
 * A recent clean pass or a recent attempt (even partial) skips, matching Swift `shouldSkipChannels`.
 */
fun shouldSkipChannelSync(
    forceFullSync: Boolean,
    skipWindowSeconds: Double,
    nowEpochSeconds: Double,
    lastCleanAt: Double?,
    lastAttemptedAt: Double?,
): Boolean {
    if (forceFullSync || skipWindowSeconds <= 0.0) return false
    if (lastCleanAt != null && nowEpochSeconds - lastCleanAt < skipWindowSeconds) return true
    if (lastAttemptedAt != null && nowEpochSeconds - lastAttemptedAt < skipWindowSeconds) return true
    return false
}

/**
 * Whether channel slots may be read through the bounded pipelined window instead of one serial read per
 * slot.
 *
 * nRF52 over Bluetooth amortizes the radio's per-write slave-latency penalty; ESP32 over WiFi avoids the
 * per-round-trip TCP stall that makes a serial slot read roughly 200ms. ESP32 over Bluetooth stays serial:
 * its write characteristic carries no Write Commands, so a pipelined burst can go unanswered and the whole
 * channel table comes back empty. This is the policy half of a two-gate design — the transport re-checks
 * the characteristic's own capability underneath it.
 */
fun usePipelinedChannelRead(platform: DevicePlatform, transportType: TransportType?): Boolean = when (platform) {
    DevicePlatform.NRF52 -> transportType == TransportType.BLUETOOTH
    DevicePlatform.ESP32 -> transportType == TransportType.WIFI
    DevicePlatform.UNKNOWN -> false
}

// Model substring → chip family, first match wins. Ported verbatim from the Swift `platformRules` table.
private val PLATFORM_RULES: List<Pair<String, DevicePlatform>> = listOf(
    // ESP32 — Heltec
    "Heltec V2" to DevicePlatform.ESP32,
    "Heltec V3" to DevicePlatform.ESP32,
    "Heltec V4" to DevicePlatform.ESP32,
    "Heltec Tracker" to DevicePlatform.ESP32,
    "Heltec E290" to DevicePlatform.ESP32,
    "Heltec E213" to DevicePlatform.ESP32,
    "Heltec T190" to DevicePlatform.ESP32,
    "Heltec CT62" to DevicePlatform.ESP32,
    // ESP32 — LilyGo
    "T-Beam" to DevicePlatform.ESP32,
    "T-Deck" to DevicePlatform.ESP32,
    "T-LoRa" to DevicePlatform.ESP32,
    "TLora" to DevicePlatform.ESP32,
    // ESP32 — Seeed
    "Xiao S3 WIO" to DevicePlatform.ESP32,
    "Xiao C3" to DevicePlatform.ESP32,
    "Xiao C6" to DevicePlatform.ESP32,
    // ESP32 — RAK
    "RAK 3112" to DevicePlatform.ESP32,
    // ESP32 — M5Stack
    "Unit C6L" to DevicePlatform.ESP32,
    // ESP32 — Other
    "Station G2" to DevicePlatform.ESP32,
    "Meshadventurer" to DevicePlatform.ESP32,
    "Generic ESP32" to DevicePlatform.ESP32,
    "ThinkNode M2" to DevicePlatform.ESP32,
    "ThinkNode M5" to DevicePlatform.ESP32,
    // nRF52 — Heltec
    "MeshPocket" to DevicePlatform.NRF52,
    "Mesh Pocket" to DevicePlatform.NRF52,
    "T114" to DevicePlatform.NRF52,
    "Mesh Solar" to DevicePlatform.NRF52,
    // nRF52 — Seeed
    "Xiao-nrf52" to DevicePlatform.NRF52,
    "Xiao_nrf52" to DevicePlatform.NRF52,
    "WM1110" to DevicePlatform.NRF52,
    "Wio Tracker" to DevicePlatform.NRF52,
    "T1000-E" to DevicePlatform.NRF52,
    "SenseCap Solar" to DevicePlatform.NRF52,
    // nRF52 — RAK
    "WisMesh Tag" to DevicePlatform.NRF52,
    "RAK 4631" to DevicePlatform.NRF52,
    "RAK 3401" to DevicePlatform.NRF52,
    // nRF52 — LilyGo
    "T-Echo" to DevicePlatform.NRF52,
    // nRF52 — Elecrow
    "ThinkNode-M1" to DevicePlatform.NRF52,
    "ThinkNode M3" to DevicePlatform.NRF52,
    "ThinkNode-M6" to DevicePlatform.NRF52,
    // nRF52 — GAT562
    "GAT562" to DevicePlatform.NRF52,
    // nRF52 — Other
    "Ikoka" to DevicePlatform.NRF52,
    "ProMicro" to DevicePlatform.NRF52,
    "Minewsemi" to DevicePlatform.NRF52,
    "Meshtiny" to DevicePlatform.NRF52,
    "Keepteen" to DevicePlatform.NRF52,
    "Nano G2 Ultra" to DevicePlatform.NRF52,
)
