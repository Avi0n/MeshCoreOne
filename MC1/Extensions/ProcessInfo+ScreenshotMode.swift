import Foundation

#if DEBUG
extension ProcessInfo {
    /// True when launched with `-screenshotMode` for App Store screenshot capture.
    var isScreenshotMode: Bool {
        arguments.contains("-screenshotMode")
    }
}
#endif
