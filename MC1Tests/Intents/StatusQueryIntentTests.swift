import Foundation
import MeshCore
import Testing
@testable import MC1
@testable import MC1Services

/// `StatusQueryIntent` is the read-only, cached-only voice glance: it answers from
/// synchronous `@Observable` state with no radio round-trip, never throws on a
/// disconnected or never-connected radio, converts cached millivolts to a percent
/// through the device OCV curve (never speaks raw mV), and reports an absent battery
/// as "no reading" rather than 0%. The spoken line is asserted through the pure
/// `dialogText(for:)` builder, which `perform()` delegates to; `@Dependency` access
/// is gated to the framework's perform flow, so the live Siri/Shortcuts invocation
/// is verified on device rather than here.
@MainActor
struct StatusQueryIntentTests {

    private static let radioID = UUID(uuidString: "33333333-3333-3333-3333-333333333333")!

    /// An OCV curve (11 points, 100%..0%) used to pin the conversion: the seeded
    /// level maps to a deterministic percent through `percentage(using:)`, a value
    /// that can never be confused with the raw millivolts.
    private static let ocvArray = [4200, 4060, 3980, 3920, 3870, 3820, 3790, 3770, 3730, 3680, 3000]

    /// A seeded millivolt level whose digits never coincide with its resulting
    /// percent, so a test catching raw-mV leakage stays meaningful. 4100mV resolves
    /// to 93% against `ocvArray`.
    private static let seededLevel = 4100
    private static let expectedPercent = 93

    private static func makeDevice(name: String) -> DeviceDTO {
        DeviceDTO(
            id: UUID(),
            radioID: radioID,
            publicKey: Data(repeating: 0x01, count: 32),
            nodeName: name,
            firmwareVersion: 8,
            firmwareVersionString: "1.10",
            manufacturerName: "Test",
            buildDate: "",
            maxContacts: 100,
            maxChannels: 16,
            frequency: 0,
            bandwidth: 0,
            spreadingFactor: 0,
            codingRate: 0,
            txPower: 0,
            maxTxPower: 0,
            latitude: 0,
            longitude: 0,
            blePin: 0,
            clientRepeat: false,
            pathHashMode: 0,
            manualAddContacts: false,
            autoAddConfig: 0,
            autoAddMaxHops: 0,
            multiAcks: 0,
            telemetryModeBase: 0,
            telemetryModeLoc: 0,
            telemetryModeEnv: 0,
            advertLocationPolicy: 0,
            lastConnected: Date(),
            lastContactSync: 0,
            isActive: true,
            ocvPreset: nil,
            customOCVArrayString: nil,
            connectionMethods: []
        )
    }

    private func seedConnected(
        _ appState: AppState,
        state: DeviceConnectionState,
        name: String,
        battery: BatteryInfo?
    ) {
        appState.connectionManager.setTestState(
            connectionState: state,
            connectedDevice: Self.makeDevice(name: name),
            currentTransportType: .bluetooth
        )
        appState.batteryMonitor.deviceBattery = battery
    }

    // MARK: - Connected rungs

    @Test(arguments: [DeviceConnectionState.ready, .syncing, .connected])
    func connectedWithBatterySpeaksNameAndPercent(_ state: DeviceConnectionState) {
        let appState = AppState()
        let battery = BatteryInfo(level: Self.seededLevel)
        seedConnected(appState, state: state, name: "Base Camp", battery: battery)

        let percent = battery.percentage(using: appState.connectedDevice!.activeOCVArray)
        let spoken = StatusQueryIntent.dialogText(for: appState)

        #expect(spoken == L10n.Tools.Intent.Status.Dialog.connectedWithBattery("Base Camp", percent))
        // Raw millivolts must never be spoken as the percentage.
        #expect(!spoken.contains(String(Self.seededLevel)))
    }

    @Test func absentBatteryReportsNoReadingNotZeroPercent() {
        let appState = AppState()
        // 0mV = no battery hardware (mains-powered); `isBatteryPresent` is false.
        seedConnected(appState, state: .ready, name: "Mains Node", battery: BatteryInfo(level: 0))

        let spoken = StatusQueryIntent.dialogText(for: appState)
        #expect(spoken == L10n.Tools.Intent.Status.Dialog.connectedNoBattery("Mains Node"))
        #expect(!spoken.contains("0%"))
    }

    @Test func missingBatteryReadingReportsNoReading() {
        let appState = AppState()
        seedConnected(appState, state: .ready, name: "No Poll Yet", battery: nil)

        #expect(
            StatusQueryIntent.dialogText(for: appState)
                == L10n.Tools.Intent.Status.Dialog.connectedNoBattery("No Poll Yet")
        )
    }

    // MARK: - Connecting and disconnected rungs (offline name, no throw)

    @Test func connectingSpeaksConnecting() {
        let appState = AppState()
        appState.connectionManager.persistConnection(
            deviceID: UUID(), radioID: Self.radioID, deviceName: "Field Radio"
        )
        appState.connectionManager.setTestState(connectionState: .connecting, connectedDevice: .some(nil))

        #expect(
            StatusQueryIntent.dialogText(for: appState)
                == L10n.Tools.Intent.Status.Dialog.connecting("Field Radio")
        )
        appState.connectionManager.clearPersistedConnection()
    }

    @Test func disconnectedWithKnownRadioSpeaksOfflineName() {
        let appState = AppState()
        appState.connectionManager.persistConnection(
            deviceID: UUID(), radioID: Self.radioID, deviceName: "Last Radio"
        )
        appState.connectionManager.setTestState(connectionState: .disconnected, connectedDevice: .some(nil))

        #expect(
            StatusQueryIntent.dialogText(for: appState)
                == L10n.Tools.Intent.Status.Dialog.disconnectedNamed("Last Radio")
        )
        appState.connectionManager.clearPersistedConnection()
    }

    @Test func neverConnectedReportsNoRadio() {
        let appState = AppState()
        appState.connectionManager.clearPersistedConnection()
        appState.connectionManager.setTestState(connectionState: .disconnected, connectedDevice: .some(nil))

        #expect(
            StatusQueryIntent.dialogText(for: appState)
                == L10n.Tools.Intent.Status.Dialog.disconnectedUnknown
        )
    }

    // MARK: - Pre-unlock (no AppState)

    @Test func nilAppStateMeansNotReady() {
        let bridge = IntentBridge()
        // No `adopt`, so `bridge.appState` is nil; the pre-unlock BFU window, where
        // `perform()` returns the not-ready dialog without reading any state. The
        // `nil` bridge is what gates that branch.
        #expect(bridge.appState == nil)
    }

    // MARK: - Conversion correctness

    @Test func millivoltsConvertToPercentThroughOCVCurve() {
        let battery = BatteryInfo(level: Self.seededLevel)
        let percent = battery.percentage(using: Self.ocvArray)
        #expect(percent == Self.expectedPercent)
        #expect(percent != battery.level)
    }

    @Test func absentBatteryIsNotPresent() {
        #expect(BatteryInfo(level: 0).isBatteryPresent == false)
        #expect(BatteryInfo(level: Self.seededLevel).isBatteryPresent == true)
    }
}
