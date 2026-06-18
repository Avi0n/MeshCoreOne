import Foundation

/// Device platform type for BLE write pacing configuration
enum DevicePlatform: Sendable {
    case esp32
    case nrf52
    case unknown

    /// Recommended write pacing delay for this platform
    var recommendedWritePacing: TimeInterval {
        switch self {
        case .esp32: return 0.060  // 60ms required by ESP32 BLE stack
        case .nrf52: return 0.025  // Light pacing to avoid RX queue pressure
        case .unknown: return 0.060  // Conservative ESP32-safe default for unrecognized devices
        }
    }

    /// Detects the device platform from the model string for BLE write pacing.
    ///
    /// Uses specific model substrings rather than vendor prefixes, because vendors like
    /// Heltec, RAK, Seeed, and Elecrow ship devices on multiple chip families.
    /// Unrecognized devices fall to `.unknown` (conservative 60ms pacing).
    static func detect(from model: String) -> DevicePlatform {
        for rule in platformRules {
            if model.localizedStandardContains(rule.substring) {
                return rule.platform
            }
        }
        return .unknown
    }

    // Ordering matters: first match wins in detect(from:). More specific patterns must precede general ones within each platform group.
    private static let platformRules: [(substring: String, platform: DevicePlatform)] = [
        // ESP32 — Heltec
        ("Heltec V2", .esp32),
        ("Heltec V3", .esp32),
        ("Heltec V4", .esp32),
        ("Heltec Tracker", .esp32),
        ("Heltec E290", .esp32),
        ("Heltec E213", .esp32),
        ("Heltec T190", .esp32),
        ("Heltec CT62", .esp32),
        // ESP32 — LilyGo
        ("T-Beam", .esp32),
        ("T-Deck", .esp32),
        ("T-LoRa", .esp32),
        ("TLora", .esp32),
        // ESP32 — Seeed
        ("Xiao S3 WIO", .esp32),
        ("Xiao C3", .esp32),
        ("Xiao C6", .esp32),
        // ESP32 — RAK
        ("RAK 3112", .esp32),
        // ESP32 — M5Stack
        ("Unit C6L", .esp32),
        // ESP32 — Other
        ("Station G2", .esp32),
        ("Meshadventurer", .esp32),
        ("Generic ESP32", .esp32),
        ("ThinkNode M2", .esp32),
        ("ThinkNode M5", .esp32),
        // nRF52 — Heltec
        ("MeshPocket", .nrf52),
        ("Mesh Pocket", .nrf52),
        ("T114", .nrf52),
        ("Mesh Solar", .nrf52),
        // nRF52 — Seeed
        ("Xiao-nrf52", .nrf52),
        ("Xiao_nrf52", .nrf52),
        ("WM1110", .nrf52),
        ("Wio Tracker", .nrf52),
        ("T1000-E", .nrf52),
        ("SenseCap Solar", .nrf52),
        // nRF52 — RAK
        ("WisMesh Tag", .nrf52),
        ("RAK 4631", .nrf52),
        ("RAK 3401", .nrf52),
        // nRF52 — LilyGo
        ("T-Echo", .nrf52),
        // nRF52 — Elecrow
        ("ThinkNode-M1", .nrf52),
        ("ThinkNode M3", .nrf52),
        ("ThinkNode-M6", .nrf52),
        // nRF52 — GAT562
        ("GAT562", .nrf52),
        // nRF52 — Other
        ("Ikoka", .nrf52),
        ("ProMicro", .nrf52),
        ("Minewsemi", .nrf52),
        ("Meshtiny", .nrf52),
        ("Keepteen", .nrf52),
        ("Nano G2 Ultra", .nrf52),
    ]
}
