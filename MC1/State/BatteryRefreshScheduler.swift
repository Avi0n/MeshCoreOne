import BackgroundTasks
import Foundation
import OSLog

/// Schedules opportunistic background battery checks for quiet meshes where RX wakes are rare.
enum BatteryRefreshScheduler {
  private static let logger = Logger(subsystem: "com.mc1", category: "BatteryRefreshScheduler")

  static let taskIdentifier = "\(Bundle.main.bundleIdentifier ?? "io.pocketmesh.app").battery-refresh"
  static let refreshInterval: TimeInterval = 15 * 60

  static func scheduleIfNeeded() {
    let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
    request.earliestBeginDate = Date(timeIntervalSinceNow: refreshInterval)
    do {
      try BGTaskScheduler.shared.submit(request)
    } catch {
      logger.debug("Skipping background battery refresh schedule: \(error.localizedDescription, privacy: .public)")
    }
  }

  static func cancelPending() {
    BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
  }
}
