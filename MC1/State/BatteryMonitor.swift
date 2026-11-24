import Foundation
import MC1Services
import MeshCore
import os
import OSLog

/// Manages device battery polling, threshold checks, and low-battery notifications.
@Observable
@MainActor
final class BatteryMonitor {
  private struct BackgroundFetchGate: Sendable {
    var nextAllowedDate: Date = .distantPast
    var isFetching = false
  }

  private let logger = Logger(subsystem: "com.mc1", category: "BatteryMonitor")
  private weak var connectionManager: ConnectionManager?
  private nonisolated let backgroundFetchGate = OSAllocatedUnfairLock(initialState: BackgroundFetchGate())

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

  /// Deduplicates overlapping battery fetches across foreground loop, RX wake, and BGTask.
  private var inFlightFetchTask: Task<Void, Never>?

  /// Thresholds that have already triggered a notification this session
  private var notifiedBatteryThresholds: Set<Int> = []

  /// Tracks last successful battery fetch for background piggybacking
  private var lastSuccessfulFetchDate: Date?

  /// Battery warning threshold levels (percentage)
  private let batteryWarningThresholds = [20, 10, 5]

  /// Called when battery info is updated, for Live Activity
  var onBatteryChanged: ((_ battery: BatteryInfo) -> Void)?

  var shouldRunRefreshLoopProvider: (@MainActor () -> Bool)?

  #if DEBUG
    var isRefreshLoopActiveForTesting: Bool {
      batteryRefreshTask != nil
    }
  #endif

  private static let refreshIntervalSeconds: TimeInterval = 120
  private static let refreshToleranceSeconds: TimeInterval = 5
  static let backgroundBatteryInterval: TimeInterval = 900
  static let backgroundFailureBackoffSeconds: TimeInterval = 120

  /// The active OCV array for the connected device
  func activeBatteryOCVArray(for device: DeviceDTO?) -> [Int] {
    device?.activeOCVArray ?? OCVPreset.liIon.ocvArray
  }

  // MARK: - Public API

  /// Fetch device battery level on demand
  func fetchDeviceBattery(services: ServiceContainer?, device: DeviceDTO?) async {
    if let existingTask = inFlightFetchTask {
      await existingTask.value
      backgroundFetchGate.withLock { $0.isFetching = false }
      return
    }
    guard let services, let context = context(for: services) else {
      backgroundFetchGate.withLock { $0.isFetching = false }
      return
    }
    let settingsService = services.settingsService
    let task = Task { @MainActor [weak self] in
      guard let self else { return }
      defer {
        self.inFlightFetchTask = nil
        self.backgroundFetchGate.withLock { $0.isFetching = false }
      }
      do {
        let battery = try await settingsService.getBattery()
        guard self.isCurrent(context) else { return }
        self.applyFetchedBattery(battery, device: device)
        await self.checkBatteryThresholds(device: device, services: services)
      } catch {
        guard self.isCurrent(context) else { return }
        let nextAllowed = Date.now.addingTimeInterval(Self.backgroundFailureBackoffSeconds)
        self.backgroundFetchGate.withLock { $0.nextAllowedDate = nextAllowed }
        if self.deviceBattery != nil {
          self.deviceBattery = nil
        }
      }
    }
    inFlightFetchTask = task
    await task.value
  }

  /// Fetches battery only when both the background interval and failure backoff have elapsed.
  func fetchBatteryIfOverdue(services: ServiceContainer?, device: DeviceDTO?, now: Date = .now) async {
    if let last = lastSuccessfulFetchDate,
       now.timeIntervalSince(last) < Self.backgroundBatteryInterval { return }
    let canProceed = backgroundFetchGate.withLock { state in
      guard !state.isFetching, now >= state.nextAllowedDate else { return false }
      state.isFetching = true
      return true
    }
    guard canProceed else { return }
    await fetchDeviceBattery(services: services, device: device)
  }

  /// Checks overdue and backoff state off the main actor so high-rate RX wakes
  /// never hop to `@MainActor` or stall the RX stream.
  nonisolated func requestOverdueBackgroundFetch(
    now: Date = .now,
    perform: @escaping @MainActor @Sendable () async -> Void
  ) {
    let shouldStart = backgroundFetchGate.withLock { state -> Bool in
      guard !state.isFetching, now >= state.nextAllowedDate else { return false }
      state.isFetching = true
      return true
    }
    guard shouldStart else { return }
    Task(priority: .utility) { @MainActor [weak self] in
      await perform()
      self?.backgroundFetchGate.withLock { $0.isFetching = false }
    }
  }

  private func applyFetchedBattery(_ battery: BatteryInfo, device: DeviceDTO?) {
    let ocv = activeBatteryOCVArray(for: device)
    let previousPercent = deviceBattery?.percentage(using: ocv)
    let newPercent = battery.percentage(using: ocv)
    if deviceBattery != battery {
      deviceBattery = battery
    }
    let now = Date.now
    lastSuccessfulFetchDate = now
    let nextAllowed = now.addingTimeInterval(Self.backgroundBatteryInterval)
    backgroundFetchGate.withLock { $0.nextAllowedDate = nextAllowed }
    if previousPercent != newPercent {
      onBatteryChanged?(battery)
    }
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
        applyFetchedBattery(battery, device: device)
      } catch {
        guard isCurrent(context) else { return }
        logger.debug("Deferred battery bootstrap failed: \(error.localizedDescription, privacy: .public)")
        let nextAllowed = Date.now.addingTimeInterval(Self.backgroundFailureBackoffSeconds)
        backgroundFetchGate.withLock { $0.nextAllowedDate = nextAllowed }
        if deviceBattery != nil {
          deviceBattery = nil
        }
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
    inFlightFetchTask?.cancel()
    inFlightFetchTask = nil
    backgroundFetchGate.withLock { $0.isFetching = false }
  }

  /// Clear notification thresholds for a fresh connection
  func clearThresholds() {
    notifiedBatteryThresholds = []
    lastSuccessfulFetchDate = nil
    backgroundFetchGate.withLock {
      $0.nextAllowedDate = .distantPast
      $0.isFetching = false
    }
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
      applyFetchedBattery(battery, device: device)
    } catch {
      let nextAllowed = Date.now.addingTimeInterval(Self.backgroundFailureBackoffSeconds)
      backgroundFetchGate.withLock { $0.nextAllowedDate = nextAllowed }
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
    batteryRefreshTask?.cancel()
    batteryRefreshTask = nil
    guard shouldRunRefreshLoopProvider?() ?? true else { return }
    guard let context = context(for: services) else { return }
    batteryRefreshTask = Task { [weak self] in
      while true {
        do {
          try await Task.sleep(
            for: .seconds(Self.refreshIntervalSeconds),
            tolerance: .seconds(Self.refreshToleranceSeconds)
          )
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
