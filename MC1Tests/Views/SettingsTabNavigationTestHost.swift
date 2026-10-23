@testable import MC1
@testable import MC1Services
import SwiftUI
import UIKit

/// Hosts MainTabView to verify Settings state transitions.
@MainActor
final class SettingsTabHarness {
  static let searchQuery = "dan"
  static let translationLanguageCode = "da"

  let appState: AppState

  private let defaults: UserDefaults
  private let suiteName: String
  let window: UIWindow
  let controller: UIViewController
  private var didTearDown = false
  var note = ""
  var recognitionStamp = 0
  var recognitionCache: (stamp: Int, key: String, words: [RecognizedWord])?

  private enum Timing {
    static let timeout: Duration = .seconds(6)
    static let pollInterval: Duration = .milliseconds(10)
  }

  enum Recognition {
    /// Body text is a small fraction of the window, so the Vision default skips it.
    static let minimumRelativeHeight: Float = 0.008
    static let maxScrollPages = 8
    static let scrollOverlap: CGFloat = 0.8
    static let rowHitSlop: CGFloat = 8
  }

  struct RecognizedWord {
    let text: String
    let rect: CGRect
  }

  private enum Frame {
    static let regular = CGSize(width: 1024, height: 768)
    static let compact = CGSize(width: 390, height: 844)
    static let compactHeight = CGSize(width: 844, height: 390)
  }

  private init(
    appState: AppState,
    defaults: UserDefaults,
    suiteName: String,
    window: UIWindow,
    controller: UIViewController
  ) {
    self.appState = appState
    self.defaults = defaults
    self.suiteName = suiteName
    self.window = window
    self.controller = controller
  }

  static func make(connected: Bool, regularWidth: Bool = false, compactHeight: Bool = false) throws -> SettingsTabHarness {
    let suiteName = "settings-tab-navigation-\(UUID().uuidString)"
    guard let defaults = UserDefaults(suiteName: suiteName) else {
      throw SettingsTabHarnessError.defaultsUnavailable
    }
    defaults.removePersistentDomain(forName: suiteName)
    defaults.set(true, forKey: AppStorageKey.translationOffersEnabled.rawValue)
    defaults.set(false, forKey: AppStorageKey.useDefaultTranslationApp.rawValue)
    defaults.set(translationLanguageCode, forKey: AppStorageKey.translationTargetLanguage.rawValue)

    let appState = AppState(defaults: defaults)
    appState.navigation.selectedSetting = nil
    appState.navigation.selectedTab = AppTab.chats.rawValue
    if connected {
      appState.connectionManager.setTestState(
        connectionState: .ready,
        services: .some(nil),
        session: .some(nil),
        connectedDevice: .some(makeDevice(name: "Relay")),
        currentTransportType: .bluetooth
      )
    }

    let root = SettingsTabRoot(appState: appState, defaults: defaults)
    let controller = UIHostingController(rootView: root)
    let size = windowSize(regularWidth: regularWidth, compactHeight: compactHeight)
    let window = makeWindow(size: size)
    window.rootViewController = controller
    applySize(controller, regularWidth: regularWidth, compactHeight: compactHeight)
    window.frame = CGRect(origin: .zero, size: size)
    controller.view.frame = window.bounds
    window.makeKeyAndVisible()
    window.layoutIfNeeded()

    return SettingsTabHarness(
      appState: appState,
      defaults: defaults,
      suiteName: suiteName,
      window: window,
      controller: controller
    )
  }

  func tearDown() {
    guard !didTearDown else { return }
    didTearDown = true
    window.rootViewController = nil
    window.isHidden = true
    window.windowScene = nil
    appState.shutdown()
    defaults.removePersistentDomain(forName: suiteName)
  }

  var navigation: NavigationCoordinator {
    appState.navigation
  }

  func waitUntil(_ condition: () -> Bool) async throws -> Bool {
    let deadline = ContinuousClock.now + Timing.timeout
    repeat {
      // Suspend before every read so UIKit can process the preceding action.
      try await Task.sleep(for: Timing.pollInterval)
      recognitionStamp += 1
      if condition() { return true }
      window.layoutIfNeeded()
    } while ContinuousClock.now < deadline
    recognitionStamp += 1
    return condition()
  }

  static func windowSize(regularWidth: Bool, compactHeight: Bool) -> CGSize {
    if regularWidth { return Frame.regular }
    return compactHeight ? Frame.compactHeight : Frame.compact
  }

  static func applySize(_ controller: UIViewController, regularWidth: Bool, compactHeight: Bool) {
    controller.traitOverrides.horizontalSizeClass = regularWidth ? .regular : .compact
    controller.traitOverrides.verticalSizeClass = compactHeight ? .compact : .regular
  }

  private static func makeWindow(size: CGSize) -> UIWindow {
    let frame = CGRect(origin: .zero, size: size)
    if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
      let window = UIWindow(windowScene: scene)
      window.frame = frame
      return window
    }
    return UIWindow(frame: frame)
  }

  static func makeDevice(name: String) -> DeviceDTO {
    DeviceDTO(
      id: UUID(),
      publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize),
      nodeName: name,
      firmwareVersion: 11,
      firmwareVersionString: "1.15.0",
      manufacturerName: "Test",
      buildDate: "2025-01-01",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
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

  private enum SettingsTabHarnessError: Error {
    case defaultsUnavailable
  }

  private struct SettingsTabRoot: View {
    let appState: AppState
    let defaults: UserDefaults

    var body: some View {
      MainTabView()
        .environment(\.appState, appState)
        .defaultAppStorage(defaults)
    }
  }
}
