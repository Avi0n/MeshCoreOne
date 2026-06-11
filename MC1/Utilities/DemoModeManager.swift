import SwiftUI
import os

private let logger = Logger(subsystem: "com.mc1", category: "DemoMode")

@Observable
@MainActor
final class DemoModeManager {
    static let shared = DemoModeManager()

    private let defaults: UserDefaults

    var isUnlocked: Bool {
        didSet { defaults.set(isUnlocked, forKey: "isDemoModeUnlocked") }
    }

    var isEnabled: Bool {
        didSet { defaults.set(isEnabled, forKey: "isDemoModeEnabled") }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.isUnlocked = defaults.bool(forKey: "isDemoModeUnlocked")
        self.isEnabled = defaults.bool(forKey: "isDemoModeEnabled")
    }

    func unlock() {
        logger.info("Demo mode unlocked and enabled")
        isUnlocked = true
        isEnabled = true
    }
}
