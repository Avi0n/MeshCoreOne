import Foundation
import MC1Services
import MeshCore
import OSLog

/// Manages device battery polling, threshold checks, and low-battery notifications.
@Observable
@MainActor
final class BatteryMonitor {
  private let logger = Logger(subsystem: "com.mc1", category: "BatteryMonitor")
  private weak var connectionManager: ConnectionManager?

  func configure(connectionManager: ConnectionManager) {
    self.connectionManager = connectionManager
  }

  private func context(for services: ServiceContainer) -> ConnectionCallbackContext? {
    guard let connectionManager, let snapshot = connectionManager.currentConnection,
          snapshot.services === services, connectionManager.isCurrent(snapshot) else { return nil }
    return ConnectionCallbackContext(snapshot)
  }

  private func isCurrent(_ context: ConnectionCallbackContext) -> Bool {
    guard let connectionManager else { return false }
    return !Task.isCancelled && context.isCurrent(connectionManager)
  }

  /// Current device battery info (nil if not fetched or disconnected)
  var deviceBattery: BatteryInfo?

  /// Task for initial battery fetch on connect (cancelled on disconnect)
  private var bootstrapTask: Task<Void, Never>?

  /// Task for periodic battery refresh (cancelled on disconnect/background)
  private var batteryRefreshTask: Task<Void, Never>?

  /// Thresholds that have already triggered a notification this session
  private var notifiedBatteryThresholds: Set<Int> = []

  /// Tracks last successful battery fetch for background piggybacking
  private var lastSuccessfulFetchDate: Date?

  /// Battery warning threshold levels (percentage)
  private let batteryWarningThresholds = [20, 10, 5]

  /// Called when battery info is updated, for Live Activity
  var onBatteryChanged: ((_ battery: BatteryInfo) -> Void)?

  /// The active OCV array for the connected device
  func activeBatteryOCVArray(for device: DeviceDTO?) -> [Int] {
    device?.activeOCVArray ?? OCVPreset.liIon.ocvArray
  }

  // MARK: - Public API

  /// Fetch device battery level on demand
  func fetchDeviceBattery(services: ServiceContainer?, device: DeviceDTO?) async {
    guard let services, let context = context(for: services) else { return }
    let settingsService = services.settingsService

    do {
      let battery = try await settingsService.getBattery()
      guard isCurrent(context) else { return }
      deviceBattery = battery
      lastSuccessfulFetchDate = .now
      if let battery = deviceBattery {
        onBatteryChanged?(battery)
      }
      await checkBatteryThresholds(device: device, services: services)
    } catch {
      guard isCurrent(context) else { return }
      deviceBattery = nil
    }
  }

  private static let backgroundBatteryInterval: TimeInterval = 900

  /// Fetch battery only if enough time has passed since last successful fetch.
  /// Called from packet reception handler to piggyback on active BLE wake-ups.
  func fetchBatteryIfOverdue(services: ServiceContainer?, device: DeviceDTO?) async {
    if let last = lastSuccessfulFetchDate,
       Date.now.timeIntervalSince(last) < Self.backgroundBatteryInterval { return }
    await fetchDeviceBattery(services: services, device: device)
  }

  /// Start battery monitoring for a newly connected device.
  /// Defers bootstrap so connection setup is not blocked by device request timeouts.
  func start(services: ServiceContainer, device: DeviceDTO?) {
    guard let context = context(for: services) else { return }
    stop()
    bootstrapTask = Task { @MainActor [weak self] in
      guard let self else { return }

      do {
        let battery = try await services.settingsService.getBattery()
        guard isCurrent(context) else { return }
        deviceBattery = battery
        lastSuccessfulFetchDate = .now
        if let battery = deviceBattery {
          onBatteryChanged?(battery)
        }
      } catch {
        guard isCurrent(context) else { return }
        logger.debug("Deferred battery bootstrap failed: \(error.localizedDescription, privacy: .public)")
        deviceBattery = nil
      }

      guard isCurrent(context) else { return }
      await initializeBatteryThresholds(device: device, services: services)
      guard isCurrent(context) else { return }
      startRefreshLoop(services: services, device: device)
    }
  }

  /// Stop battery monitoring (disconnect or background)
  func stop() {
    bootstrapTask?.cancel()
    bootstrapTask = nil
    batteryRefreshTask?.cancel()
    batteryRefreshTask = nil
  }

  /// Clear notification thresholds for a fresh connection
  func clearThresholds() {
    notifiedBatteryThresholds = []
  }

  /// Check for battery thresholds crossed while app was backgrounded.
  /// Posts a single notification if any thresholds were missed.
  func checkMissedBatteryThreshold(device: DeviceDTO?, services: ServiceContainer?) async {
    guard let device, let services, let context = context(for: services) else { return }
    let settingsService = services.settingsService
    let notificationService = services.notificationService
    do {
      let battery = try await settingsService.getBattery()
      guard isCurrent(context) else { return }
      deviceBattery = battery
      if let battery = deviceBattery {
        onBatteryChanged?(battery)
      }
    } catch {
      return
    }

    guard let battery = deviceBattery else { return }
    guard battery.isBatteryPresent else { return }
    let percentage = battery.percentage(using: device.activeOCVArray)

    let missedThresholds = batteryWarningThresholds.filter { threshold in
      percentage <= threshold && !notifiedBatteryThresholds.contains(threshold)
    }

    guard !missedThresholds.isEmpty else { return }

    for threshold in missedThresholds {
      notifiedBatteryThresholds.insert(threshold)
    }

    await notificationService.postLowBatteryNotification(
      deviceName: device.nodeName,
      batteryPercentage: percentage
    )
  }

  /// Restart the battery refresh loop (e.g., returning to foreground)
  func startRefreshLoop(services: ServiceContainer, device: DeviceDTO?) {
    guard let context = context(for: services) else { return }
    batteryRefreshTask?.cancel()
    batteryRefreshTask = Task { [weak self] in
      while true {
        do {
          try await Task.sleep(for: .seconds(120))
        } catch {
          break
        }
        guard let self, isCurrent(context) else { break }
        await fetchDeviceBattery(services: services, device: device)
      }
    }
  }

  /// Initialize battery thresholds based on current level and notify if already below a threshold
  private func initializeBatteryThresholds(device: DeviceDTO?, services: ServiceContainer) async {
    guard let battery = deviceBattery,
          let device else {
      notifiedBatteryThresholds = []
      return
    }

    guard battery.isBatteryPresent else {
      notifiedBatteryThresholds = []
      return
    }

    let percentage = battery.percentage(using: device.activeOCVArray)

    let crossedThresholds = batteryWarningThresholds.filter { percentage <= $0 }

    notifiedBatteryThresholds = Set(crossedThresholds)

    if !crossedThresholds.isEmpty {
      await services.notificationService.postLowBatteryNotification(
        deviceName: device.nodeName,
        batteryPercentage: percentage
      )
    }
  }

  /// Check battery level against thresholds and send notifications
  private func checkBatteryThresholds(device: DeviceDTO?, services: ServiceContainer?) async {
    guard let battery = deviceBattery,
          let device,
          let notificationService = services?.notificationService else { return }

    guard battery.isBatteryPresent else { return }

    let percentage = battery.percentage(using: device.activeOCVArray)

    for threshold in batteryWarningThresholds {
      if percentage <= threshold, !notifiedBatteryThresholds.contains(threshold) {
        notifiedBatteryThresholds.insert(threshold)
        await notificationService.postLowBatteryNotification(
          deviceName: device.nodeName,
          batteryPercentage: percentage
        )
        break
      } else if percentage > threshold, notifiedBatteryThresholds.contains(threshold) {
        notifiedBatteryThresholds.remove(threshold)
      }
    }
  }
}
