import MC1Services
import SwiftUI
import os

private let logger = Logger(subsystem: "com.mc1", category: "DemoMode")

@Observable
@MainActor
final class DemoModeManager {
    static let shared = DemoModeManager()

    private let defaults: UserDefaults

    var isUnlocked: Bool {
        didSet { defaults.set(isUnlocked, forKey: AppStorageKey.isDemoModeUnlocked.rawValue) }
    }

    var isEnabled: Bool {
        didSet { defaults.set(isEnabled, forKey: AppStorageKey.isDemoModeEnabled.rawValue) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.isUnlocked = defaults.bool(forKey: AppStorageKey.isDemoModeUnlocked.rawValue)
        self.isEnabled = defaults.bool(forKey: AppStorageKey.isDemoModeEnabled.rawValue)
    }

    func unlock() {
        logger.info("Demo mode unlocked and enabled")
        isUnlocked = true
        isEnabled = true
    }
}
