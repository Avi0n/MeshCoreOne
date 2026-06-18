@preconcurrency import ActivityKit
import Foundation
import MeshCore
import OSLog
import MC1Services

@Observable
@MainActor
final class LiveActivityManager {

    static let enabledKey = AppStorageKey.liveActivityEnabled.rawValue

    private let logger = Logger(subsystem: "com.mc1", category: "LiveActivityManager")

    /// Returns whether BLE is currently connected at the radio layer. Defaults
    /// to `false` so a missing wiring fails closed.
    var connectionStateProvider: (@MainActor () -> Bool)?

    private var currentActivity: Activity<MeshStatusAttributes>?
    private var decayTimer: Task<Void, Never>?
    private var disconnectTimer: Task<Void, Never>?
    private var enablementTask: Task<Void, Never>?
    private var stateObservationTask: Task<Void, Never>?
    private var throttleTask: Task<Void, Never>?
    private var ocvArray: [Int] = []
    private var recentPacketTimestamps: [Date] = []
    private var pendingUpdate: PendingUpdate?
    private var lastFlushDate: Date = .distantPast

    static let decayInterval: TimeInterval = 15
    static let packetWindowSeconds: TimeInterval = 15
    static let secondsPerMinute: TimeInterval = 60
    static let connectedStaleInterval: TimeInterval = 30
    static let disconnectGracePeriod: TimeInterval = 300
    static let updateInterval: TimeInterval = 15

    /// Projects the short-window packet count to a per-minute rate.
    private var projectedPacketsPerMinute: Int {
        Int((Double(recentPacketTimestamps.count) * Self.secondsPerMinute / Self.packetWindowSeconds).rounded())
    }

    var hasActiveActivity: Bool { currentActivity != nil }

    var isEnabled: Bool {
        didSet { UserDefaults.standard.set(isEnabled, forKey: Self.enabledKey) }
    }

    init() {
        isEnabled = UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? AppStorageKey.defaultLiveActivityEnabled
    }

    // MARK: - Pending Update

    private struct PendingUpdate {
        var isConnected: Bool?
        var battery: Int??
        var packetsPerMinute: Int?
        var unreadCount: Int?
        var disconnectedDate: Date??
    }

    // MARK: - Lifecycle

    func startObservingEnablement() {
        enablementTask?.cancel()
        enablementTask = Task { [weak self] in
            for await enabled in ActivityAuthorizationInfo().activityEnablementUpdates {
                guard let self else { break }
                if !enabled {
                    await self.endActivity()
                }
            }
        }
    }

    func handleConnectionReady(
        device: DeviceDTO,
        ocvArray: [Int],
        unreadCount: Int
    ) async {
        self.ocvArray = ocvArray

        // If reconnecting to same device within grace period, restore connected state
        if let activity = currentActivity,
           activity.attributes.deviceName == device.nodeName {
            disconnectTimer?.cancel()
            disconnectTimer = nil
            recentPacketTimestamps = []
            clearPendingUpdate()
            await updateActivity(
                isConnected: true,
                battery: .some(nil),
                packetsPerMinute: 0,
                unreadCount: unreadCount,
                disconnectedDate: .some(nil)
            )
            startDecayTimer()
            startObservingActivityState()
            return
        }

        // If reconnecting to a different device, end the old activity first
        if currentActivity != nil {
            await endActivity()
        }

        await startActivity(
            deviceName: device.nodeName,
            unreadCount: unreadCount
        )
        startDecayTimer()
    }

    func handleConnectionLost() async {
        guard currentActivity != nil else { return }

        stopDecayTimer()
        recentPacketTimestamps = []
        clearPendingUpdate()
        await updateActivity(
            isConnected: false,
            battery: .some(nil),
            packetsPerMinute: 0,
            unreadCount: 0,
            disconnectedDate: .some(.now)
        )

        disconnectTimer?.cancel()
        disconnectTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.disconnectGracePeriod))
            guard !Task.isCancelled else { return }
            await self?.endActivity()
        }
    }

    func handleEnterBackground() {
        stopDecayTimer()
    }

    func handleReturnToForeground() {
        guard hasActiveActivity else { return }
        startDecayTimer()
        if pendingUpdate != nil {
            Task { await flushPendingUpdate() }
        }
    }

    func handlePacketReceived() async {
        let now = Date.now
        recentPacketTimestamps.append(now)
        let cutoff = now.addingTimeInterval(-Self.packetWindowSeconds)
        recentPacketTimestamps.removeAll { $0 < cutoff }
        await scheduleUpdate(packetsPerMinute: projectedPacketsPerMinute)
    }

    func handleBatteryChanged(battery: BatteryInfo) async {
        let percent = battery.percentage(using: ocvArray)
        await scheduleUpdate(battery: .some(percent))
        await flushPendingUpdate()
    }

    func handleUnreadCountChanged(unreadCount: Int) async {
        await scheduleUpdate(unreadCount: unreadCount)
        await flushPendingUpdate()
    }

    func setEnabled(_ enabled: Bool) async {
        isEnabled = enabled
        if !enabled {
            await endActivity()
        }
    }

    // MARK: - App relaunch recovery

    func recoverExistingActivity() async {
        let allActivities = Activity<MeshStatusAttributes>.activities

        // Clean up ended/dismissed activities still visible per their dismissal policy.
        for activity in allActivities where
            activity.activityState == .ended || activity.activityState == .dismissed {
            await activity.end(nil, dismissalPolicy: .immediate)
        }

        // Adopt the first active/stale activity; immediately end any extras as
        // orphans so the user never sees two LAs at once.
        let activeOrStale = allActivities.filter {
            $0.activityState == .active || $0.activityState == .stale
        }
        for orphan in activeOrStale.dropFirst() {
            logger.warning("Ending duplicate orphan Live Activity on recovery")
            await orphan.end(nil, dismissalPolicy: .immediate)
        }

        currentActivity = activeOrStale.first
        guard let activity = currentActivity else { return }

        startObservingActivityState()

        // If the LA is cached as connected, force re-validation. The radio may
        // actually be disconnected (prior session crashed mid-connection, app
        // was killed during a drop, etc.). handleConnectionReady's same-device
        // branch will restore connected state once auto-reconnect lands.
        if activity.content.state.isConnected {
            logger.info("Recovered Live Activity in cached connected state — forcing re-validation as disconnected")
            await handleConnectionLost()
            return
        }

        // Already disconnected — re-arm the grace timer using the persisted
        // disconnectedDate. If it's missing or already expired, end now.
        guard let disconnectedDate = activity.content.state.disconnectedDate else {
            await endActivity()
            return
        }

        let elapsed = Date.now.timeIntervalSince(disconnectedDate)
        let remaining = Self.disconnectGracePeriod - elapsed

        if remaining > 0 {
            disconnectTimer = Task { [weak self] in
                try? await Task.sleep(for: .seconds(remaining))
                guard !Task.isCancelled else { return }
                await self?.endActivity()
            }
        } else {
            await endActivity()
        }
    }

    // MARK: - Private

    private func startDecayTimer() {
        stopDecayTimer()
        decayTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.decayInterval))
                guard !Task.isCancelled, let self else { return }
                let cutoff = Date.now.addingTimeInterval(-Self.packetWindowSeconds)
                self.recentPacketTimestamps.removeAll { $0 < cutoff }
                await self.scheduleUpdate(packetsPerMinute: self.projectedPacketsPerMinute)
            }
        }
    }

    private func stopDecayTimer() {
        decayTimer?.cancel()
        decayTimer = nil
    }

    private func clearActivityReference() {
        currentActivity = nil
        stateObservationTask?.cancel()
        stateObservationTask = nil
        // Everything else here is bound to the activity's lifetime: timers
        // that would mutate it, throttled updates queued for it, packet
        // timestamps used to compute its rate. If any of these survive the
        // reference being cleared, they can leak onto the next activity (a
        // stale disconnect timer ending a fresh LA, a queued flush applying
        // old battery/rate, packet-rate carry-over).
        disconnectTimer?.cancel()
        disconnectTimer = nil
        stopDecayTimer()
        clearPendingUpdate()
        recentPacketTimestamps = []
    }

    private func startActivity(
        deviceName: String,
        unreadCount: Int
    ) async {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            logger.warning("Cannot start Live Activity: not authorized")
            return
        }
        guard isEnabled else {
            logger.debug("Cannot start Live Activity: disabled by user")
            return
        }
        guard !DemoModeManager.shared.isEnabled else { return }

        // Reclaim slots held by ended/dismissed entries before requesting.
        // iOS keeps them in `.activities` for up to 4 hours (Lock Screen
        // dismissal window) and counts them against the per-app activity
        // cap, so `Activity.request` would otherwise throw
        // `ActivityAuthorizationError.targetMaximumExceeded`. Mirrors
        // `recoverExistingActivity`.
        for activity in Activity<MeshStatusAttributes>.activities where
            activity.activityState == .ended || activity.activityState == .dismissed {
            await activity.end(nil, dismissalPolicy: .immediate)
        }

        guard !Activity<MeshStatusAttributes>.activities.contains(where: {
            $0.activityState == .active || $0.activityState == .stale
        }) else {
            logger.warning("Cannot start Live Activity: one already running")
            return
        }

        let attributes = MeshStatusAttributes(deviceName: deviceName)
        let state = MeshStatusAttributes.ContentState(
            isConnected: true,
            batteryPercent: nil,
            packetsPerMinute: 0,
            unreadCount: unreadCount,
            disconnectedDate: nil
        )
        let staleDate = Calendar.current.date(byAdding: .minute, value: 5, to: .now)
        let content = ActivityContent(state: state, staleDate: staleDate)

        do {
            currentActivity = try Activity.request(
                attributes: attributes,
                content: content,
                pushType: nil
            )
            LiveActivityTip.radioConnected.sendDonation()
            startObservingActivityState()
            logger.info("Started Live Activity for \(deviceName, privacy: .public)")
        } catch {
            logger.error("Failed to start Live Activity: \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: - Activity State Observation

    private func startObservingActivityState() {
        stateObservationTask?.cancel()
        guard let activity = currentActivity else { return }
        stateObservationTask = Task { [weak self] in
            for await state in activity.activityStateUpdates {
                guard let self else { break }
                switch state {
                case .ended:
                    let lastState = activity.content.state
                    let deviceName = activity.attributes.deviceName

                    // Clear the field synchronously before any await so a
                    // re-entrant @MainActor caller (e.g. handleConnectionReady)
                    // can't reassign currentActivity to a replacement and have
                    // it nulled out when we return.
                    self.clearActivityReference()

                    // Dismiss the captured (now-detached) activity immediately
                    // so it doesn't linger on screen alongside any replacement
                    // (system-ended activities default to ~4h visible dismissal).
                    await activity.end(nil, dismissalPolicy: .immediate)

                    // Don't resurrect a "connected" LA from cached state —
                    // only restart when the radio is actually connected.
                    let isCurrentlyConnected = self.connectionStateProvider?() ?? false
                    if isCurrentlyConnected {
                        logger.info("System ended Live Activity, restarting (radio still connected)")
                        await self.startActivity(deviceName: deviceName, unreadCount: lastState.unreadCount)
                        self.startDecayTimer()
                    } else {
                        logger.info("System ended Live Activity, not restarting (radio disconnected)")
                    }
                    return

                case .dismissed:
                    self.clearActivityReference()
                    logger.info("Live Activity dismissed by user")
                    return

                case .stale:
                    let currentState = activity.content.state
                    let isCurrentlyConnected = self.connectionStateProvider?() ?? false

                    if currentState.isConnected && !isCurrentlyConnected {
                        // LA cached as connected, but the radio is actually
                        // disconnected (we missed a notification somewhere).
                        // Force the disconnect path instead of refreshing.
                        logger.warning("Stale Live Activity cached connected but radio disconnected — forcing handleConnectionLost")
                        await self.handleConnectionLost()
                    } else if currentState.isConnected && currentState.packetsPerMinute > 0 {
                        logger.debug("Live Activity stale with active rate, resetting to 0")
                        self.recentPacketTimestamps = []
                        self.clearPendingUpdate()
                        await self.updateActivity(packetsPerMinute: 0)
                    } else if self.pendingUpdate != nil {
                        await self.flushPendingUpdate()
                    } else {
                        await self.updateActivity()
                    }

                case .active:
                    break

                case .pending:
                    break

                @unknown default:
                    break
                }
            }
        }
    }

    // MARK: - Throttled Updates

    private func scheduleUpdate(
        isConnected: Bool? = nil,
        battery: Int?? = nil,
        packetsPerMinute: Int? = nil,
        unreadCount: Int? = nil,
        disconnectedDate: Date?? = nil
    ) async {
        guard currentActivity != nil else { return }

        // Merge into pending update (last-writer-wins per field)
        var pending = pendingUpdate ?? PendingUpdate()
        if let isConnected { pending.isConnected = isConnected }
        if let battery { pending.battery = battery }
        if let packetsPerMinute { pending.packetsPerMinute = packetsPerMinute }
        if let unreadCount { pending.unreadCount = unreadCount }
        if let disconnectedDate { pending.disconnectedDate = disconnectedDate }
        pendingUpdate = pending

        // Leading edge: flush immediately if enough time has passed
        if Date.now.timeIntervalSince(lastFlushDate) >= Self.updateInterval {
            await flushPendingUpdate()
            return
        }

        // Trailing edge: schedule flush if not already scheduled
        guard throttleTask == nil else { return }
        let delay = Self.updateInterval - Date.now.timeIntervalSince(lastFlushDate)
        throttleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            await self.flushPendingUpdate()
        }
    }

    private func flushPendingUpdate() async {
        guard let pending = pendingUpdate else { return }
        pendingUpdate = nil
        throttleTask?.cancel()
        throttleTask = nil
        lastFlushDate = .now
        await updateActivity(
            isConnected: pending.isConnected,
            battery: pending.battery,
            packetsPerMinute: pending.packetsPerMinute,
            unreadCount: pending.unreadCount,
            disconnectedDate: pending.disconnectedDate
        )
    }

    private func clearPendingUpdate() {
        pendingUpdate = nil
        throttleTask?.cancel()
        throttleTask = nil
    }

    /// Updates the Live Activity state. Pass `nil` to keep the current value, `.some(value)` to override.
    private func updateActivity(
        isConnected: Bool? = nil,
        battery: Int?? = nil,
        packetsPerMinute: Int? = nil,
        unreadCount: Int? = nil,
        disconnectedDate: Date?? = nil
    ) async {
        guard let current = currentActivity?.content.state else { return }
        let state = MeshStatusAttributes.ContentState(
            isConnected: isConnected ?? current.isConnected,
            batteryPercent: battery ?? current.batteryPercent,
            packetsPerMinute: packetsPerMinute ?? current.packetsPerMinute,
            unreadCount: unreadCount ?? current.unreadCount,
            disconnectedDate: disconnectedDate ?? current.disconnectedDate
        )
        let staleDate: Date? = if state.isConnected && state.packetsPerMinute > 0 {
            Date.now.addingTimeInterval(Self.connectedStaleInterval)
        } else {
            Calendar.current.date(byAdding: .minute, value: 5, to: .now)
        }
        let content = ActivityContent(state: state, staleDate: staleDate)
        await currentActivity?.update(content)
    }

    /// Checks whether the current activity reference is still valid.
    /// If the activity was ended while suspended (and `activityStateUpdates` didn't fire), this catches it.
    func validateActivityState() async {
        guard let activity = currentActivity else { return }
        switch activity.activityState {
        case .ended:
            let lastState = activity.content.state
            let deviceName = activity.attributes.deviceName

            // Clear the field synchronously before the await so a re-entrant
            // main-actor caller can't have its replacement currentActivity
            // nulled out when we resume.
            clearActivityReference()
            await activity.end(nil, dismissalPolicy: .immediate)

            let isCurrentlyConnected = connectionStateProvider?() ?? false
            if isCurrentlyConnected {
                logger.info("Detected ended Live Activity on foreground, restarting (radio still connected)")
                await startActivity(deviceName: deviceName, unreadCount: lastState.unreadCount)
                startDecayTimer()
            } else {
                logger.info("Detected ended Live Activity on foreground, not restarting (radio disconnected)")
            }
        case .dismissed:
            clearActivityReference()
        case .active, .stale:
            break
        case .pending:
            break
        @unknown default:
            break
        }
    }

    func endActivity() async {
        stopDecayTimer()
        stateObservationTask?.cancel()
        stateObservationTask = nil
        disconnectTimer?.cancel()
        disconnectTimer = nil
        clearPendingUpdate()
        recentPacketTimestamps = []
        for activity in Activity<MeshStatusAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        currentActivity = nil
        logger.info("Ended Live Activity")
    }
}
