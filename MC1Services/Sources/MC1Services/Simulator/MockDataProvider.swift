import Foundation

/// Mock data provider for the iOS Simulator and demo mode. Demo mode is what App
/// Store reviewers see, so seeded conversations must read as realistic. Per-feature
/// seed data lives in `MockDataProvider+…` extensions; this file holds the shared
/// identity constants, the simulated device, and the offline demo image.
public enum MockDataProvider {
    // MARK: - Deterministic IDs

    /// Simulator device UUID
    public static let simulatorDeviceID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!

    /// Contact UUIDs
    public static let aliceChenID = UUID(uuidString: "00000000-0000-0000-0000-000000000010")!
    public static let bobMartinezID = UUID(uuidString: "00000000-0000-0000-0000-000000000020")!
    public static let charlieNodeID = UUID(uuidString: "00000000-0000-0000-0000-000000000030")!
    public static let dianasRoomID = UUID(uuidString: "00000000-0000-0000-0000-000000000040")!
    public static let eveThompsonID = UUID(uuidString: "00000000-0000-0000-0000-000000000050")!
    public static let frankWilsonID = UUID(uuidString: "00000000-0000-0000-0000-000000000060")!
    public static let ghostNodeID = UUID(uuidString: "00000000-0000-0000-0000-000000000070")!
    public static let hannahLeeID = UUID(uuidString: "00000000-0000-0000-0000-000000000080")!

    /// Channel UUIDs
    public static let publicChannelID = UUID(uuidString: "000000C0-0000-0000-0000-000000000000")!
    public static let bayAreaChannelID = UUID(uuidString: "000000C0-0000-0000-0000-000000000001")!
    public static let trailCrewChannelID = UUID(uuidString: "000000C0-0000-0000-0000-000000000002")!

    /// Channel slot indices (mirror firmware slot positions)
    public static let publicChannelIndex: UInt8 = 0
    public static let bayAreaChannelIndex: UInt8 = 1
    public static let trailCrewChannelIndex: UInt8 = 2

    /// Message IDs referenced by more than one seed helper (reaction / repeat /
    /// link-preview targets) so the builders and the post-save mutators agree.
    static let aliceReactedMessageID = UUID(uuidString: "10000000-0000-0000-0000-000000000002")!
    static let aliceLinkPreviewMessageID = UUID(uuidString: "10000000-0000-0000-0000-00000000000A")!
    static let frankRepeatMessageID = UUID(uuidString: "60000000-0000-0000-0000-000000000002")!
    static let bayAreaReactedMessageID = UUID(uuidString: "C1000000-0000-0000-0000-000000000002")!
    static let bayAreaMentionMessageID = UUID(uuidString: "C1000000-0000-0000-0000-000000000003")!

    // MARK: - Mock Public Keys

    /// Deterministic 32-byte public key from a seed. Internal so the per-feature
    /// builders in sibling files can resolve sender key prefixes.
    static func mockPublicKey(seed: UInt8) -> Data {
        Data((0..<ProtocolLimits.publicKeySize).map { UInt8($0) &+ seed })
    }

    // MARK: - Offline Demo Image

    /// URL placed in a seeded DM body so the inline-image render path has a target.
    /// The pixels are pre-seeded into the app-layer image cache on demo connect
    /// (`DemoInlineImageSeeder`), so the bubble renders with no network fetch.
    public static let inlineImageURL = "https://meshcoreone.com/summit.jpg"

    /// A small embedded gradient PNG (see `MockDataProvider+DemoImage`). Doubles as the
    /// offline link-preview hero blob (used directly as `Data`) and, decoded to a `UIImage`
    /// in the app layer, as the offline inline image. Held as base64 so this package stays
    /// UIKit-free.
    public static let demoImageData = Data(base64Encoded: demoImageBase64, options: .ignoreUnknownCharacters) ?? Data()

    // MARK: - Simulator Device

    /// Mock simulator device with realistic configuration
    public static var simulatorDevice: DeviceDTO {
        DeviceDTO(
            id: simulatorDeviceID,
            radioID: simulatorDeviceID,
            publicKey: mockPublicKey(seed: 1),
            nodeName: "Sim",
            firmwareVersion: 8,
            firmwareVersionString: "v1.11.0",
            manufacturerName: "Mock Device",
            buildDate: "2025-12-20",
            maxContacts: 100,
            maxChannels: 8,
            frequency: 915_000,      // 915 MHz
            bandwidth: 250_000,      // 250 kHz
            spreadingFactor: 10,     // SF10
            codingRate: 5,           // 4/5
            txPower: 20,             // 20 dBm
            maxTxPower: 20,
            latitude: 37.7749,       // San Francisco
            longitude: -122.4194,
            blePin: 0,               // Disabled
            manualAddContacts: false,
            multiAcks: 2,
            telemetryModeBase: 2,
            telemetryModeLoc: 0,
            telemetryModeEnv: 0,
            advertLocationPolicy: 0,
            lastConnected: Date(),
            lastContactSync: 0,
            isActive: true,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }
}
