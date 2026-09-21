import Foundation

#if DEBUG
  extension ProcessInfo {
    /// True when launched with `-screenshotMode` for App Store screenshot capture.
    var isScreenshotMode: Bool {
      arguments.contains("-screenshotMode")
    }

    /// True when launched with `-gateAPrototype` to host the native split
    /// instead of `MainTabView`.
    var isGateAPrototype: Bool {
      arguments.contains("-gateAPrototype")
    }
  }
#endif
