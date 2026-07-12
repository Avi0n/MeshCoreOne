import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

/// `SendAdvertIntent` broadcasts a self-advertisement. Its `perform()` is a
/// `ProvidesDialog` flow that isn't unit-assertable, so coverage lands on the
/// pure pieces it delegates to (the reach-to-flood mapping, the spoken dialog,
/// the error mapping) plus `AppState`'s GPS gate and disconnected guard. The live
/// Siri/Shortcuts invocation and the connected send are verified on device.
@MainActor
struct SendAdvertIntentTests {
  // MARK: - Reach mapping

  @Test func `reach maps to flood flag`() {
    #expect(AdvertReach.zeroHop.sendsFlood == false)
    #expect(AdvertReach.flood.sendsFlood == true)
  }

  /// These raw values are the persisted identifiers the Shortcuts framework
  /// stores in saved shortcuts; renaming one breaks every shortcut on disk.
  @Test func `reach raw values are pinned shortcut identifiers`() {
    #expect(AdvertReach.zeroHop.rawValue == "zeroHop")
    #expect(AdvertReach.flood.rawValue == "flood")
  }

  // MARK: - Spoken dialog

  @Test func `success dialog is reach specific and honest`() {
    #expect(SendAdvertIntent.successDialog(for: .zeroHop) == L10n.Tools.Intent.Advert.Dialog.sentZeroHop)
    #expect(SendAdvertIntent.successDialog(for: .flood) == L10n.Tools.Intent.Advert.Dialog.sentFlood)
    // An advert has no delivery ACK, so the dialog must never claim delivery.
    #expect(!SendAdvertIntent.successDialog(for: .zeroHop).lowercased().contains("delivered"))
    #expect(!SendAdvertIntent.successDialog(for: .flood).lowercased().contains("delivered"))
  }

  // MARK: - Error mapping

  @Test func `map to intent error routes every case`() {
    #expect(Self.tag(SendAdvertIntent.mapToIntentError(.notConnected)) == "notConnected")
    #expect(Self.tag(SendAdvertIntent.mapToIntentError(.sendFailed)) == "advertFailed")
    #expect(Self.tag(SendAdvertIntent.mapToIntentError(.invalidResponse)) == "advertFailed")

    let mapped = SendAdvertIntent.mapToIntentError(.sessionError(.timeout))
    #expect(Self.tag(mapped) == "sessionError")
    #expect(mapped.errorDescription == L10n.Localizable.Error.MeshCore.timeout)
  }

  @Test func `general error mapping never leaks raw errors`() {
    // An AdvertisementError routes through the typed, exhaustive mapper.
    #expect(Self.tag(SendAdvertIntent.mapToIntentError(AdvertisementError.notConnected as Error)) == "notConnected")
    // A MeshCoreError the service forwards becomes a localized session error.
    #expect(Self.tag(SendAdvertIntent.mapToIntentError(MeshCoreError.timeout as Error)) == "sessionError")
    // A raw transport error the service does not rewrap still maps, so Siri
    // never speaks it verbatim.
    #expect(Self.tag(SendAdvertIntent.mapToIntentError(Self.UnmappedError())) == "advertFailed")
    // An already-localized IntentError passes through unchanged.
    #expect(Self.tag(SendAdvertIntent.mapToIntentError(IntentError.messageTooLong as Error)) == "messageTooLong")
  }

  private struct UnmappedError: Error {}

  // MARK: - GPS gate (privacy)

  @Test func `gps gate respects the per device preference`() throws {
    let appState = AppState()
    let suite = try #require(UserDefaults(suiteName: "test.advert.gate.\(UUID().uuidString)"))
    let store = DevicePreferenceStore(userDefaults: suite)
    let deviceID = UUID()

    // Disabled by default: no location is refreshed even with a permissive policy.
    #expect(appState.advertGPSSource(device: Self.makeDevice(id: deviceID, advertLocationPolicy: 1), store: store) == nil)

    // Enabled, but the device policy forbids location: still no refresh.
    store.setAutoUpdateLocationEnabled(true, deviceID: deviceID)
    #expect(appState.advertGPSSource(device: Self.makeDevice(id: deviceID, advertLocationPolicy: 0), store: store) == nil)

    // Enabled and policy allows: the configured source is returned.
    store.setGPSSource(.device, deviceID: deviceID)
    #expect(appState.advertGPSSource(device: Self.makeDevice(id: deviceID, advertLocationPolicy: 1), store: store) == .device)
  }

  @Test func `gps gate is nil without A device`() throws {
    let appState = AppState()
    let store = try DevicePreferenceStore(userDefaults: #require(UserDefaults(suiteName: "test.advert.gate.\(UUID().uuidString)")))
    #expect(appState.advertGPSSource(device: nil, store: store) == nil)
  }

  // MARK: - Disconnected guard

  @Test func `send self advert without services throws not connected`() async {
    let appState = AppState()
    do {
      try await appState.sendSelfAdvert(flood: false)
      Issue.record("expected sendSelfAdvert to throw while disconnected")
    } catch let error as AdvertisementError {
      guard case .notConnected = error else {
        Issue.record("expected .notConnected, got \(error)")
        return
      }
    } catch {
      Issue.record("unexpected error: \(error)")
    }
  }

  // MARK: - Helpers

  private static func tag(_ error: IntentError) -> String {
    switch error {
    case .notConnected: "notConnected"
    case .invalidRecipient: "invalidRecipient"
    case .messageTooLong: "messageTooLong"
    case .sendFailed: "sendFailed"
    case .advertFailed: "advertFailed"
    case .sessionError: "sessionError"
    }
  }

  private static func makeDevice(id: UUID, advertLocationPolicy: UInt8) -> DeviceDTO {
    DeviceDTO(
      id: id,
      radioID: UUID(),
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "Test",
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
      advertLocationPolicy: advertLocationPolicy,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: []
    )
  }
}
