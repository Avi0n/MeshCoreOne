import Foundation
import MC1Services
import MeshCore
import OSLog

/// Manages device battery polling, threshold checks, and low-battery notifications.
@Observable
@MainActor
final class BatteryMonitor {

    private let logger = Logger(subsystem: "com.mc1", category: "BatteryMonitor")

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

    /// Battery percentage levels that trigger a warning. Driven by the
    /// user-configurable low-battery threshold (Settings ▸ Battery).
    private var batteryWarningThresholds: [Int] {
        [NotificationPreferencesStore.lowBatteryWarningThreshold()]
    }

    /// Called when battery info is updated, for Live Activity
    var onBatteryChanged: ((_ battery: BatteryInfo) -> Void)?

    /// The active OCV array for the connected device
    func activeBatteryOCVArray(for device: DeviceDTO?) -> [Int] {
        device?.activeOCVArray ?? OCVPreset.liIon.ocvArray
    }

    // MARK: - Public API

    /// Fetch device battery level on demand
    func fetchDeviceBattery(services: ServiceContainer?, device: DeviceDTO?) async {
        guard let settingsService = services?.settingsService else { return }

        do {
            deviceBattery = try await settingsService.getBattery()
            lastSuccessfulFetchDate = .now
            if let battery = deviceBattery {
                onBatteryChanged?(battery)
            }
            await checkBatteryThresholds(device: device, services: services)
        } catch {
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
        bootstrapTask = Task { @MainActor [weak self] in
            guard let self else { return }

            do {
                self.deviceBattery = try await services.settingsService.getBattery()
                self.lastSuccessfulFetchDate = .now
                if let battery = self.deviceBattery {
                    self.onBatteryChanged?(battery)
                }
            } catch {
                self.logger.debug("Deferred battery bootstrap failed: \(error.localizedDescription, privacy: .public)")
                self.deviceBattery = nil
            }

            guard !Task.isCancelled else { return }

            await self.initializeBatteryThresholds(device: device, services: services)
            self.startRefreshLoop(services: services, device: device)
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
        guard let device,
              let settingsService = services?.settingsService,
              let notificationService = services?.notificationService else { return }

        do {
            deviceBattery = try await settingsService.getBattery()
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
        batteryRefreshTask?.cancel()
        batteryRefreshTask = Task { [weak self] in
            while true {
                do {
                    try await Task.sleep(for: .seconds(120))
                } catch {
                    break
                }
                guard let self else { break }
                await self.fetchDeviceBattery(services: services, device: device)
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
            if percentage <= threshold && !notifiedBatteryThresholds.contains(threshold) {
                notifiedBatteryThresholds.insert(threshold)
                await notificationService.postLowBatteryNotification(
                    deviceName: device.nodeName,
                    batteryPercentage: percentage
                )
                break
            } else if percentage > threshold && notifiedBatteryThresholds.contains(threshold) {
                notifiedBatteryThresholds.remove(threshold)
            }
        }
    }
}
