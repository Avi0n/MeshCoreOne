import Foundation
import MC1Services

// MARK: - App Lifecycle

extension AppState {
  private enum BLELifecycleTransition {
    case enterBackground
    case becomeActive
  }

  @discardableResult
  private func enqueueBLELifecycleTransition(_ transition: BLELifecycleTransition) -> Task<Void, Never> {
    let priorTask = bleLifecycleTransitionTask
    let manager = connectionManager

    let transitionTask = Task { @MainActor in
      await priorTask?.value

      #if DEBUG
        switch transition {
        case .enterBackground:
          if let override = bleEnterBackgroundOverride {
            await override()
            return
          }
        case .becomeActive:
          if let override = bleBecomeActiveOverride {
            await override()
            return
          }
        }
      #endif

      switch transition {
      case .enterBackground:
        await manager.appDidEnterBackground()
      case .becomeActive:
        await manager.appDidBecomeActive()
      }
    }

    bleLifecycleTransitionTask = transitionTask
    return transitionTask
  }

  /// Called when app enters background
  func handleEnterBackground() {
    activeRecoveryFallbackTask?.cancel()
    activeRecoveryFallbackTask = nil

    liveActivityManager.handleEnterBackground()

    // Keep battery polling alive when the live activity is visible on the lock screen
    if !liveActivityManager.hasActiveActivity {
      batteryMonitor.stop()
    }

    // Stop room keepalives to save battery/bandwidth
    Task {
      await services?.remoteNodeService.stopAllKeepAlives()
    }

    // Queue BLE lifecycle transition so background/foreground hooks stay ordered.
    enqueueBLELifecycleTransition(.enterBackground)
  }

  /// Called when app returns to foreground
  func handleReturnToForeground() async {
    // Update badge count from database
    await services?.notificationService.updateBadgeCount()

    // Room keepalives are managed by RoomConversationView lifecycle
    // (started on view appear, stopped on disappear, restarted via scenePhase)

    // Reconcile transport state first (WiFi check + BLE lifecycle transition,
    // which internally fires checkBLEConnectionHealth) so any stale "connected"
    // state gets cleaned up via
    // handleConnectionLoss → onConnectionLost → liveActivityManager.handleConnectionLost
    // before the Live Activity tries to validate or restart.
    await connectionManager.checkWiFiConnectionHealth()
    await enqueueBLELifecycleTransition(.becomeActive).value

    liveActivityManager.handleReturnToForeground()
    await liveActivityManager.validateActivityState()
    await restartLiveActivityIfMissing()

    // Check for expired ACKs
    if connectionState == .ready {
      try? await services?.messageService.checkExpiredAcks()
    }

    // Trigger resync if sync failed while connected
    await connectionManager.checkSyncHealth()

    // Check for missed battery thresholds and restart polling if connected
    if let services {
      await batteryMonitor.checkMissedBatteryThreshold(device: connectedDevice, services: services)
      batteryMonitor.startRefreshLoop(services: services, device: connectedDevice)
    }

    offlineMapService.resumeAllPacks()
  }
}
