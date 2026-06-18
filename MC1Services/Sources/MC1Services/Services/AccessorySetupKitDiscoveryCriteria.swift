import Foundation

struct AccessorySetupKitDiscoveryCriterion: Equatable, Sendable {
    let bluetoothServiceUUID: String
    let bluetoothNameSubstring: String
}

enum AccessorySetupKitDiscoveryCriteria {
    /// Opt into AccessorySetupKit filtered discovery (iOS 26.1+) so the picker can relabel
    /// each match with its advertised BLE name; older systems use the static picker label.
    static let usesFilteredDiscovery = true

    static let bluetoothNameSubstrings = [
        "MeshCore-",
        "Whisper-",
        "WisCore",
        "XIAO",
        "elecrow",
        "HT-n5262",
        "Seeed",
        "BQ",
        "ProMicro",
        "Keepteen",
        "Meshtiny",
        "T1000-E-BOOT",
        "me25ls01-BOOT",
        "NRF52 DK",
        "T-Impulse",
    ]

    static let supportedBluetoothCriteria = bluetoothNameSubstrings.map {
        AccessorySetupKitDiscoveryCriterion(
            bluetoothServiceUUID: BLEServiceUUID.nordicUART,
            bluetoothNameSubstring: $0
        )
    }

    static let diagnosticsSummary = supportedBluetoothCriteria
        .map { "\($0.bluetoothNameSubstring)->\($0.bluetoothServiceUUID)" }
        .joined(separator: ", ")
}
