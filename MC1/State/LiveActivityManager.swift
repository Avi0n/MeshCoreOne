@preconcurrency import ActivityKit
import Foundation
import MC1Services
import MeshCore
import os
import OSLog
import SwiftUI
import UIKit

@Observable
@MainActor
final class LiveActivityManager {
  static let enabledKey = AppStorageKey.liveActivityEnabled.rawValue

  private let logger = Logger(subsystem: "com.mc1", category: "LiveActivityManager")

  /// Returns whether BLE is currently connected at the radio layer. Defaults
  /// to `false` so a missing wiring fails closed.
  var connectionStateProvider: (@MainActor () -> Bool)?

  /// Returns the radioID of the currently connected radio, or `nil` when
  /// disconnected. Lets the stale observer restore a connected activity only
  /// when the live radio still matches the one the activity represents, so a
  /// connection to a different radio can't relabel a stale activity.
  var connectedRadioIDProvider: (@MainActor () -> UUID?)?

  /// Current scene phase; non-background gates the decay timer, active gates `Activity.request`.
  var scenePhaseProvider: (@MainActor () -> ScenePhase)?

  var onActivityStateChanged: (@MainActor (_ isActive: Bool) -> Void)?

  private struct RateTrackerState: Sendable {
    var timestamps: [Date] = []
    var isActive = false
    var isInForeground = false
    var isDeviceLocked = false
    var nextMainActorForwardDate: Date = .distantPast
  }

  private nonisolated let rateTracker = OSAllocatedUnfairLock(initialState: RateTrackerState())

  private(set) var isDeviceLocked = false
  private var currentActivity: Activity<MeshStatusAttributes>?
  private var lifecycleGeneration = UUID()
  private var lockTransitionGeneration: UInt64 = 0
  private var decayTimer: Task<Void, Never>?
  private var disconnectTimer: Task<Void, Never>?
  private var enablementTask: Task<Void, Never>?
  private var lockObservationTask: Task<Void, Never>?
  private var lockStreamContinuation: AsyncStream<Bool>.Continuation?
  private var lockObserverTokens: [NSObjectProtocol] = []
  private weak var lockNotificationCenter: NotificationCenter?
  private var stateObservationTask: Task<Void, Never>?
  private var throttleTask: Task<Void, Never>?
  private var ocvArray: [Int] = []
  private var pendingUpdate: PendingUpdate?
  private var lastFlushDate: Date = .distantPast

  nonisolated struct UpdateCadence: Sendable {
    let updateInterval: TimeInterval
    let tolerance: Duration?
    let connectedStaleInterval: TimeInterval
    let staleRefreshLeadTime: TimeInterval

    static let foreground = UpdateCadence(
      updateInterval: 15,
      tolerance: nil,
      connectedStaleInterval: LiveActivityManager.packetWindowSeconds,
      staleRefreshLeadTime: LiveActivityManager.packetWindowSeconds / 2
    )

    /// Stretches Lock Screen refreshes to 60s in background to cut widget-extension wakes;
    /// `displayedPacketRate` may stay non-zero up to 120s after RF traffic stops.
    static let background = UpdateCadence(
      updateInterval: 60,
      tolerance: .seconds(5),
      connectedStaleInterval: 120,
      staleRefreshLeadTime: 30
    )
  }

  static let decayInterval: TimeInterval = 15
  static let decayTolerance: TimeInterval = 1
  nonisolated static let packetWindowSeconds: TimeInterval = 60
  nonisolated static let secondsPerMinute: TimeInterval = 60
  static let disconnectGracePeriod: TimeInterval = 300

  private var scenePhase: ScenePhase {
    scenePhaseProvider?() ?? .background
  }

  private var isInForeground: Bool {
    scenePhase != .background
  }

  private var isForegroundActive: Bool {
    scenePhase == .active
  }

  private var activeCadence: UpdateCadence {
    isInForeground ? .foreground : .background
  }

  /// Packets in the trailing `packetWindowSeconds` as a per-minute rate. A
  /// full-minute window makes this the true count for that minute, so the
  /// displayed value matches the RX Log instead of extrapolating a burst.
  private var recentPacketsPerMinute: Int {
    let now = Date.now
    return rateTracker.withLock { state in
      let cutoff = now.addingTimeInterval(-Self.packetWindowSeconds)
      state.timestamps.removeAll { $0 < cutoff }
      return Self.packetsPerMinute(timestamps: state.timestamps, now: now)
    }
  }

  /// Packets within `window` ending at `now` as a per-minute rate. Pure so the
  /// rate math is unit-testable without an `Activity`.
  nonisolated static func packetsPerMinute(
    timestamps: [Date],
    now: Date,
    window: TimeInterval = packetWindowSeconds
  ) -> Int {
    let cutoff = now.addingTimeInterval(-window)
    let count = timestamps.count(where: { $0 >= cutoff })
    return Int((Double(count) * secondsPerMinute / window).rounded())
  }

  var hasActiveActivity: Bool {
    currentActivity != nil
  }

  #if DEBUG
    var isDecayTimerActiveForTesting: Bool {
      decayTimer != nil
    }

    var recentPacketsPerMinuteForTesting: Int {
      recentPacketsPerMinute
    }
  #endif

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
    var packetsPerMinute: Int??
    var unreadCount: Int?
    var disconnectedDate: Date??
    var failedSends: FailedSendSummary??
  }

  // MARK: - Lifecycle

  func startObservingEnablement() {
    enablementTask?.cancel()
    enablementTask = Task { [weak self] in
      for await enabled in ActivityAuthorizationInfo().activityEnablementUpdates {
        guard let self else { break }
        if !enabled {
          await endActivity()
        }
      }
    }
  }

  func startObservingDeviceLockState(
    notificationCenter: NotificationCenter = .default,
    initiallyLocked: Bool? = nil
  ) {
    stopObservingDeviceLockState()
    let locked = initiallyLocked ?? !UIApplication.shared.isProtectedDataAvailable
    isDeviceLocked = locked
    syncRateTracker(isDeviceLocked: locked)

    let (stream, continuation) = AsyncStream<Bool>.makeStream()
    lockStreamContinuation = continuation
    lockNotificationCenter = notificationCenter
    lockObserverTokens = [
      notificationCenter.addObserver(
        forName: UIApplication.protectedDataWillBecomeUnavailableNotification,
        object: nil,
        queue: nil
      ) { _ in
        continuation.yield(true)
      },
      notificationCenter.addObserver(
        forName: UIApplication.protectedDataDidBecomeAvailableNotification,
        object: nil,
        queue: nil
      ) { _ in
        continuation.yield(false)
      }
    ]

    lockObservationTask = Task { [weak self] in
      for await locked in stream {
        guard let self, !Task.isCancelled else { break }
        if locked {
          await self.handleDeviceDidLock()
        } else {
          await self.handleDeviceDidUnlock()
        }
      }
    }
  }

  func stopObservingDeviceLockState() {
    if let lockNotificationCenter {
      for token in lockObserverTokens {
        lockNotificationCenter.removeObserver(token)
      }
    }
    lockObserverTokens.removeAll()
    lockNotificationCenter = nil
    lockStreamContinuation?.finish()
    lockStreamContinuation = nil
    lockObservationTask?.cancel()
    lockObservationTask = nil
  }

  func handleDeviceDidLock() async {
    guard !isDeviceLocked else { return }
    isDeviceLocked = true
    lockTransitionGeneration &+= 1
    let generation = lockTransitionGeneration
    stopDecayTimer()
    syncRateTracker(isDeviceLocked: true)

    if var pending = pendingUpdate {
      if case .some(.some) = pending.packetsPerMinute {
        pending.packetsPerMinute = nil
      }
      let hasOtherFields = pending.isConnected != nil || pending.battery != nil
        || pending.unreadCount != nil || pending.disconnectedDate != nil
        || pending.packetsPerMinute != nil
      if hasOtherFields {
        pendingUpdate = pending
      } else {
        clearPendingUpdate()
      }
    }

    guard let activity = currentActivity,
          activity.content.state.isConnected,
          activity.content.state.packetsPerMinute != nil else { return }

    let applyPause: @MainActor () async -> Void = { [weak self] in
      guard let self, lockTransitionGeneration == generation, isDeviceLocked,
            currentActivity?.id == activity.id else { return }
      await updateActivity(packetsPerMinute: .some(nil))
    }

    if isInForeground {
      await applyPause()
    } else {
      await BackgroundTaskRunner.live.run(named: "PauseLiveActivityPacketRate") {
        await applyPause()
      }
    }
  }

  func handleDeviceDidUnlock() async {
    guard isDeviceLocked else { return }
    isDeviceLocked = false
    lockTransitionGeneration &+= 1
    let generation = lockTransitionGeneration
    syncRateTracker(isDeviceLocked: false, resetForwardDate: true)
    if isInForeground {
      startDecayTimer()
    }

    guard let activity = currentActivity,
          activity.content.state.isConnected,
          activity.content.state.packetsPerMinute == nil else { return }

    let liveRate = recentPacketsPerMinute
    let applyResume: @MainActor () async -> Void = { [weak self] in
      guard let self, lockTransitionGeneration == generation, !isDeviceLocked,
            currentActivity?.id == activity.id else { return }
      await updateActivity(packetsPerMinute: .some(liveRate))
    }

    if isInForeground {
      await applyResume()
    } else {
      await BackgroundTaskRunner.live.run(named: "ResumeLiveActivityPacketRate") {
        await applyResume()
      }
    }
  }

  func handleConnectionReady(
    device: DeviceDTO,
    ocvArray: [Int],
    unreadCount: @escaping @MainActor () async -> Int,
    isCurrent: @escaping @MainActor () -> Bool
  ) async {
    guard isCurrent() else { return }
    let generation = UUID()
    lifecycleGeneration = generation
    self.ocvArray = ocvArray

    // If reconnecting to same device within grace period, restore connected state
    if let activity = currentActivity,
       let activityRadioID = activity.attributes.radioID,
       activityRadioID == device.radioID {
      disconnectTimer?.cancel()
      disconnectTimer = nil
      syncRateTracker(isActive: true, isInForeground: isInForeground, resetTimestamps: true, resetForwardDate: true)
      clearPendingUpdate()
      let resolvedUnreadCount = await unreadCount()
      guard lifecycleGeneration == generation, isCurrent(), currentActivity?.id == activity.id else { return }
      await updateActivity(
        isConnected: true,
        battery: .some(nil),
        packetsPerMinute: .some(isDeviceLocked ? nil : 0),
        unreadCount: resolvedUnreadCount,
        disconnectedDate: .some(nil)
      )
      guard lifecycleGeneration == generation, isCurrent() else { return }
      startDecayTimer()
      startObservingActivityState()
      return
    }

    // If reconnecting to a different device, end the old activity first
    if currentActivity != nil {
      let activities = detachActivities()
      await endActivities(activities)
      guard lifecycleGeneration == generation, isCurrent() else { return }
    }

    await startActivity(
      deviceName: device.nodeName,
      radioID: device.radioID,
      unreadCount: unreadCount,
      generation: generation,
      isCurrent: isCurrent
    )
    guard lifecycleGeneration == generation, isCurrent() else { return }
    startDecayTimer()
  }

  func handleConnectionLost(isCurrent: @escaping @MainActor () -> Bool) async {
    guard isCurrent(), let activity = currentActivity else { return }
    let generation = UUID()
    lifecycleGeneration = generation
    stopDecayTimer()
    syncRateTracker(isInForeground: isInForeground, resetTimestamps: true, resetForwardDate: true)
    clearPendingUpdate()
    await updateActivity(
      isConnected: false,
      battery: .some(nil),
      packetsPerMinute: .some(nil),
      unreadCount: 0,
      disconnectedDate: .some(.now)
    )

    guard lifecycleGeneration == generation, currentActivity?.id == activity.id, isCurrent() else { return }
    disconnectTimer?.cancel()
    disconnectTimer = Task { [weak self] in
      try? await Task.sleep(for: .seconds(Self.disconnectGracePeriod))
      guard !Task.isCancelled, let self,
            lifecycleGeneration == generation, currentActivity?.id == activity.id, isCurrent() else { return }
      await endActivity()
    }
  }

  func handleEnterBackground() {
    stopDecayTimer()
    syncRateTracker(isInForeground: false)
    // `throttleTask` pauses while suspended, so the coalesced rate is published here.
    if isDeviceLocked, var pending = pendingUpdate, case .some(.some) = pending.packetsPerMinute {
      pending.packetsPerMinute = nil
      let hasOtherFields = pending.isConnected != nil || pending.battery != nil
        || pending.unreadCount != nil || pending.disconnectedDate != nil
      if hasOtherFields {
        pendingUpdate = pending
      } else {
        clearPendingUpdate()
      }
    }
    guard pendingUpdate != nil else { return }
    Task {
      await BackgroundTaskRunner.live.run(named: "FlushLiveActivity") {
        await flushPendingUpdate()
      }
    }
  }

  func handleReturnToForeground() {
    let wasLocked = isDeviceLocked
    if wasLocked {
      isDeviceLocked = false
      lockTransitionGeneration &+= 1
    }
    syncRateTracker(isInForeground: true, isDeviceLocked: false, resetForwardDate: true)
    guard hasActiveActivity else { return }
    startDecayTimer()
    if wasLocked,
       let activity = currentActivity,
       activity.content.state.isConnected,
       activity.content.state.packetsPerMinute == nil {
      let liveRate = recentPacketsPerMinute
      Task { [weak self] in
        await self?.scheduleUpdate(packetsPerMinute: .some(liveRate))
        await self?.flushPendingUpdate()
      }
    } else if pendingUpdate != nil {
      Task { await flushPendingUpdate() }
    }
  }

  /// Accumulates packet timestamps off the main actor and only hops to `@MainActor`
  /// once per cadence interval while an activity is active and the device is unlocked.
  nonisolated func recordPacketFromRxLog(at now: Date = .now) {
    let forwardedRate: Int? = rateTracker.withLock { state in
      guard state.isActive else { return nil }
      let cutoff = now.addingTimeInterval(-Self.packetWindowSeconds)
      state.timestamps.removeAll { $0 < cutoff }
      state.timestamps.append(now)
      guard !state.isDeviceLocked else { return nil }
      guard now >= state.nextMainActorForwardDate else { return nil }
      let interval = state.isInForeground
        ? UpdateCadence.foreground.updateInterval
        : UpdateCadence.background.updateInterval
      state.nextMainActorForwardDate = now.addingTimeInterval(interval)
      return Self.packetsPerMinute(timestamps: state.timestamps, now: now)
    }
    guard let rate = forwardedRate else { return }
    Task { @MainActor [weak self] in
      await self?.scheduleUpdate(packetsPerMinute: .some(rate))
    }
  }

  func handlePacketReceived() async {
    let now = Date.now
    let rate = rateTracker.withLock { state in
      let cutoff = now.addingTimeInterval(-Self.packetWindowSeconds)
      state.timestamps.removeAll { $0 < cutoff }
      state.timestamps.append(now)
      return Self.packetsPerMinute(timestamps: state.timestamps, now: now)
    }
    guard !isDeviceLocked else { return }
    await scheduleUpdate(packetsPerMinute: .some(rate))
  }

  func handleBatteryChanged(battery: BatteryInfo) async {
    guard let activity = currentActivity else { return }
    let generation = lifecycleGeneration
    let percent = battery.percentage(using: ocvArray)
    let displayedPercent = pendingUpdate?.battery ?? activity.content.state.batteryPercent
    guard displayedPercent != percent else { return }
    await scheduleUpdate(battery: .some(percent))
    guard lifecycleGeneration == generation else { return }
    if isInForeground {
      await flushPendingUpdate()
    }
  }

  func handleUnreadCountChanged(unreadCount: Int) async {
    guard let activity = currentActivity else { return }
    let generation = lifecycleGeneration
    let displayedUnread = pendingUpdate?.unreadCount ?? activity.content.state.unreadCount
    guard displayedUnread != unreadCount else { return }
    await scheduleUpdate(unreadCount: unreadCount)
    guard lifecycleGeneration == generation else { return }
    await flushPendingUpdate()
  }

  /// Publishes unseen failed DMs. Flushed at once, like unread, so a failure is not held by the throttle.
  func handleFailedSendsChanged(_ summary: FailedSendSummary?) async {
    let generation = lifecycleGeneration
    await scheduleUpdate(failedSends: .some(summary))
    guard lifecycleGeneration == generation else { return }
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
    let generation = lifecycleGeneration
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

    guard lifecycleGeneration == generation else { return }
    currentActivity = activeOrStale.first
    guard let activity = currentActivity else { return }
    syncRateTracker(isActive: true, isInForeground: isInForeground, resetForwardDate: true)

    onActivityStateChanged?(true)
    startObservingActivityState()

    // If the LA is cached as connected, force re-validation. The radio may
    // actually be disconnected (prior session crashed mid-connection, app
    // was killed during a drop, etc.). handleConnectionReady's same-device
    // branch will restore connected state once auto-reconnect lands.
    if activity.content.state.isConnected {
      logger.info("Recovered Live Activity in cached connected state — forcing re-validation as disconnected")
      await handleConnectionLost { [weak self] in self?.currentActivity?.id == activity.id }
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
        guard !Task.isCancelled, let self,
              lifecycleGeneration == generation, currentActivity?.id == activity.id else { return }
        await endActivity()
      }
    } else {
      await endActivity()
    }
  }

  // MARK: - Private

  private func startDecayTimer() {
    stopDecayTimer()
    guard hasActiveActivity, isInForeground, !isDeviceLocked else { return }
    decayTimer = Task { [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(
          for: .seconds(Self.decayInterval),
          tolerance: .seconds(Self.decayTolerance)
        )
        guard !Task.isCancelled, let self, !isDeviceLocked else { return }
        await scheduleUpdate(packetsPerMinute: .some(recentPacketsPerMinute))
      }
    }
  }

  private func stopDecayTimer() {
    decayTimer?.cancel()
    decayTimer = nil
  }

  private func syncRateTracker(
    isActive: Bool? = nil,
    isInForeground: Bool? = nil,
    isDeviceLocked: Bool? = nil,
    resetTimestamps: Bool = false,
    resetForwardDate: Bool = false
  ) {
    rateTracker.withLock { state in
      if let isActive { state.isActive = isActive }
      if let isInForeground { state.isInForeground = isInForeground }
      if let isDeviceLocked { state.isDeviceLocked = isDeviceLocked }
      if resetTimestamps { state.timestamps.removeAll() }
      if resetForwardDate { state.nextMainActorForwardDate = .distantPast }
    }
  }

  private func clearActivityReference() {
    let wasActive = currentActivity != nil
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
    syncRateTracker(isActive: false, resetTimestamps: true, resetForwardDate: true)
    if wasActive {
      onActivityStateChanged?(false)
    }
  }

  private func startActivity(
    deviceName: String,
    radioID: UUID?,
    unreadCount: @escaping @MainActor () async -> Int,
    generation: UUID,
    isCurrent: @escaping @MainActor () -> Bool
  ) async {
    guard lifecycleGeneration == generation, isCurrent() else { return }
    guard isForegroundActive else { return }
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

    guard lifecycleGeneration == generation, isCurrent() else { return }
    guard !Activity<MeshStatusAttributes>.activities.contains(where: {
      $0.activityState == .active || $0.activityState == .stale
    }) else {
      logger.warning("Cannot start Live Activity: one already running")
      return
    }

    let resolvedUnreadCount = await unreadCount()
    guard lifecycleGeneration == generation, isCurrent(), isForegroundActive else { return }

    let attributes = MeshStatusAttributes(deviceName: deviceName, radioID: radioID)
    let state = MeshStatusAttributes.ContentState(
      isConnected: true,
      batteryPercent: nil,
      packetsPerMinute: isDeviceLocked ? nil : 0,
      unreadCount: resolvedUnreadCount,
      disconnectedDate: nil
    )
    let content = ActivityContent(state: state, staleDate: nil)

    do {
      currentActivity = try Activity.request(
        attributes: attributes,
        content: content,
        pushType: nil
      )
      syncRateTracker(isActive: true, isInForeground: isInForeground, resetTimestamps: true, resetForwardDate: true)
      onActivityStateChanged?(true)
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
        guard let self, !Task.isCancelled, currentActivity?.id == activity.id else { break }
        let generation = lifecycleGeneration
        switch state {
        case .ended:
          let lastState = activity.content.state
          let deviceName = activity.attributes.deviceName
          let radioID = activity.attributes.radioID

          // Clear the field synchronously before any await so a
          // re-entrant @MainActor caller (e.g. handleConnectionReady)
          // can't reassign currentActivity to a replacement and have
          // it nulled out when we return.
          clearActivityReference()

          // Dismiss the captured (now-detached) activity immediately
          // so it doesn't linger on screen alongside any replacement
          // (system-ended activities default to ~4h visible dismissal).
          await activity.end(nil, dismissalPolicy: .immediate)

          // Don't resurrect a "connected" LA from cached state —
          // only restart when the radio is actually connected.
          guard lifecycleGeneration == generation, currentActivity == nil else { return }
          let isCurrentlyConnected = connectionStateProvider?() ?? false
          if isCurrentlyConnected, connectedRadioIDProvider?() == radioID {
            logger.info("System ended Live Activity, restarting (radio still connected)")
            await startActivity(
              deviceName: deviceName,
              radioID: radioID,
              unreadCount: { lastState.unreadCount },
              generation: generation,
              isCurrent: { [weak self] in
                self?.connectedRadioIDProvider?() == radioID && (self?.connectionStateProvider?() ?? false)
              }
            )
            startDecayTimer()
          } else {
            logger.info("System ended Live Activity, not restarting (radio disconnected)")
          }
          return

        case .dismissed:
          clearActivityReference()
          logger.info("Live Activity dismissed by user")
          return

        case .stale:
          let currentState = activity.content.state
          let isCurrentlyConnected = connectionStateProvider?() ?? false

          if currentState.isConnected, !isCurrentlyConnected {
            // LA cached as connected, but the radio is actually
            // disconnected (we missed a notification somewhere).
            // Force the disconnect path instead of refreshing.
            logger.warning("Stale Live Activity cached connected but radio disconnected — forcing handleConnectionLost")
            await handleConnectionLost { [weak self] in
              self?.currentActivity?.id == activity.id && !(self?.connectionStateProvider?() ?? false)
            }
          } else if !currentState.isConnected,
                    let activityRadioID = activity.attributes.radioID,
                    let connectedRadioID = connectedRadioIDProvider?(),
                    activityRadioID == connectedRadioID {
            // LA cached as disconnected, but the same radio is actually
            // connected. A background reconnect restored the link with no
            // runtime to update. Restore connected, since handleConnectionReady
            // may not fire until the app foregrounds.
            logger.warning("Stale Live Activity cached disconnected but radio connected — restoring connected state")
            disconnectTimer?.cancel()
            disconnectTimer = nil
            syncRateTracker(isActive: true, isInForeground: isInForeground, resetTimestamps: true, resetForwardDate: true)
            clearPendingUpdate()
            await updateActivity(
              isConnected: true,
              packetsPerMinute: .some(isDeviceLocked ? nil : recentPacketsPerMinute),
              disconnectedDate: .some(nil)
            )
            guard lifecycleGeneration == generation, currentActivity?.id == activity.id else { return }
            startDecayTimer()
          } else if currentState.isConnected, let rate = currentState.packetsPerMinute, rate > 0 {
            logger.debug("Live Activity stale with active rate, resetting to 0")
            syncRateTracker(resetTimestamps: true, resetForwardDate: true)
            clearPendingUpdate()
            await updateActivity(packetsPerMinute: .some(isDeviceLocked ? nil : 0))
          } else if pendingUpdate != nil {
            await flushPendingUpdate()
          } else {
            await updateActivity()
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
    packetsPerMinute: Int?? = nil,
    unreadCount: Int? = nil,
    disconnectedDate: Date?? = nil,
    failedSends: FailedSendSummary?? = nil
  ) async {
    guard currentActivity != nil else { return }

    // Merge into pending update (last-writer-wins per field)
    var pending = pendingUpdate ?? PendingUpdate()
    if let isConnected { pending.isConnected = isConnected }
    if let battery { pending.battery = battery }
    if let packetsPerMinute {
      if isDeviceLocked, case .some = packetsPerMinute {
        pending.packetsPerMinute = .some(nil)
      } else {
        pending.packetsPerMinute = packetsPerMinute
      }
    }
    if let unreadCount { pending.unreadCount = unreadCount }
    if let disconnectedDate { pending.disconnectedDate = disconnectedDate }
    if let failedSends { pending.failedSends = failedSends }
    pendingUpdate = pending

    let cadence = activeCadence
    let delay = Self.nextUpdateDelay(
      lastFlushDate: lastFlushDate,
      now: .now,
      staleDate: currentActivity?.content.staleDate,
      cadence: cadence
    )
    if delay == 0 {
      await flushPendingUpdate()
      return
    }

    guard throttleTask == nil else { return }
    throttleTask = Task { [weak self] in
      try? await Task.sleep(for: .seconds(delay), tolerance: cadence.tolerance)
      guard !Task.isCancelled, let self else { return }
      await flushPendingUpdate()
    }
  }

  static func nextUpdateDelay(
    lastFlushDate: Date,
    now: Date,
    staleDate: Date?,
    cadence: UpdateCadence
  ) -> TimeInterval {
    let intervalRemaining = max(0, cadence.updateInterval - now.timeIntervalSince(lastFlushDate))
    guard let staleDate else { return intervalRemaining }
    let staleRemaining = max(0, staleDate.timeIntervalSince(now) - cadence.staleRefreshLeadTime)
    return min(intervalRemaining, staleRemaining)
  }

  private func flushPendingUpdate() async {
    guard var pending = pendingUpdate else { return }
    pendingUpdate = nil
    throttleTask?.cancel()
    throttleTask = nil
    let now = Date.now
    lastFlushDate = now
    let interval = activeCadence.updateInterval
    let shouldRefreshRate = if !isDeviceLocked, case .some(.some) = pending.packetsPerMinute {
      true
    } else {
      false
    }
    if isDeviceLocked, case .some(.some) = pending.packetsPerMinute {
      pending.packetsPerMinute = nil
    }
    let refreshedRate: Int? = rateTracker.withLock { state in
      state.nextMainActorForwardDate = now.addingTimeInterval(interval)
      guard shouldRefreshRate else { return nil }
      let cutoff = now.addingTimeInterval(-Self.packetWindowSeconds)
      state.timestamps.removeAll { $0 < cutoff }
      return Self.packetsPerMinute(timestamps: state.timestamps, now: now)
    }
    if let refreshedRate {
      pending.packetsPerMinute = .some(refreshedRate)
    }
    await updateActivity(
      isConnected: pending.isConnected,
      battery: pending.battery,
      packetsPerMinute: pending.packetsPerMinute,
      unreadCount: pending.unreadCount,
      disconnectedDate: pending.disconnectedDate,
      failedSends: pending.failedSends
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
    packetsPerMinute: Int?? = nil,
    unreadCount: Int? = nil,
    disconnectedDate: Date?? = nil,
    failedSends: FailedSendSummary?? = nil
  ) async {
    guard let activity = currentActivity else { return }
    let current = activity.content.state
    let resolvedIsConnected = isConnected ?? current.isConnected
    let resolvedPacketsPerMinute: Int? = if isDeviceLocked, resolvedIsConnected {
      nil
    } else {
      packetsPerMinute ?? current.packetsPerMinute
    }
    let state = MeshStatusAttributes.ContentState(
      isConnected: resolvedIsConnected,
      batteryPercent: battery ?? current.batteryPercent,
      packetsPerMinute: resolvedPacketsPerMinute,
      unreadCount: unreadCount ?? current.unreadCount,
      disconnectedDate: disconnectedDate ?? current.disconnectedDate,
      failedSends: failedSends ?? current.failedSends
    )
    let cadence = activeCadence
    let now = Date.now
    let staleDate: Date? = if state.isConnected, let rate = state.packetsPerMinute, rate > 0 {
      now.addingTimeInterval(cadence.connectedStaleInterval)
    } else {
      nil
    }
    // Republish unchanged content before its stale date. `displayedPacketRate` shows 0 after that.
    guard Self.shouldPublishUpdate(
      current: current,
      proposed: state,
      currentStaleDate: activity.content.staleDate,
      now: now,
      refreshLeadTime: cadence.staleRefreshLeadTime
    ) else { return }
    let content = ActivityContent(state: state, staleDate: staleDate)
    await activity.update(content)
  }

  static func shouldPublishUpdate(
    current: MeshStatusAttributes.ContentState,
    proposed: MeshStatusAttributes.ContentState,
    currentStaleDate: Date?,
    now: Date,
    refreshLeadTime: TimeInterval
  ) -> Bool {
    if current != proposed { return true }
    guard proposed.isConnected, let rate = proposed.packetsPerMinute, rate > 0 else { return false }
    guard let currentStaleDate else { return true }
    return currentStaleDate.timeIntervalSince(now) <= refreshLeadTime
  }

  /// Checks whether the current activity reference is still valid.
  /// If the activity was ended while suspended (and `activityStateUpdates` didn't fire), this catches it.
  func validateActivityState() async {
    guard let activity = currentActivity else { return }
    let generation = lifecycleGeneration
    switch activity.activityState {
    case .ended:
      let lastState = activity.content.state
      let deviceName = activity.attributes.deviceName
      let radioID = activity.attributes.radioID

      // Clear the field synchronously before the await so a re-entrant
      // main-actor caller can't have its replacement currentActivity
      // nulled out when we resume.
      clearActivityReference()
      await activity.end(nil, dismissalPolicy: .immediate)

      guard lifecycleGeneration == generation, currentActivity == nil else { return }
      let isCurrentlyConnected = connectionStateProvider?() ?? false
      if isCurrentlyConnected, connectedRadioIDProvider?() == radioID {
        logger.info("Detected ended Live Activity on foreground, restarting (radio still connected)")
        await startActivity(
          deviceName: deviceName,
          radioID: radioID,
          unreadCount: { lastState.unreadCount },
          generation: generation,
          isCurrent: { [weak self] in
            self?.connectedRadioIDProvider?() == radioID && (self?.connectionStateProvider?() ?? false)
          }
        )
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
    lifecycleGeneration = UUID()
    let activities = detachActivities()
    await endActivities(activities)
  }

  private func detachActivities() -> [Activity<MeshStatusAttributes>] {
    let activities = Activity<MeshStatusAttributes>.activities
    clearActivityReference()
    return activities
  }

  private func endActivities(_ activities: [Activity<MeshStatusAttributes>]) async {
    for activity in activities {
      await activity.end(nil, dismissalPolicy: .immediate)
    }
    logger.info("Ended Live Activity")
  }
}
