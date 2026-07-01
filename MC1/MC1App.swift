import AppIntents
import MC1Services
import os
import SwiftData
import SwiftUI
import TipKit

private let logger = Logger(subsystem: "com.mc1", category: "MC1App")

@main
struct MC1App: App {
  @State private var appState: AppState
  @State private var awaitingDataProtection = false
  @Environment(\.scenePhase) private var scenePhase

  /// Stable holder that App Intents read through `AppDependencyManager`. It
  /// must outlive the before-first-unlock `AppState` swap, so it is a plain
  /// stored property registered once in `init()`, not `@State`.
  private let intentBridge = IntentBridge()

  init() {
    // Register the bridge synchronously here: a background-launched intent
    // runs only `App.init` (no scene, no `.task`), so a deferred
    // registration could let its `@Dependency` read throw before it lands.
    // Capture into a local so the escaping autoclosure does not capture the
    // still-initializing `self`.
    let intentBridge = intentBridge
    AppDependencyManager.shared.add(dependency: intentBridge)

    // True only on the before-first-unlock path, where the normal init site
    // holds an in-memory throwaway the bridge must not adopt.
    var usingThrowawayStore = false

    let container: ModelContainer
    do {
      container = try PersistenceStore.createContainer()
    } catch {
      logger.error("Container creation failed: \(error)")

      if UIApplication.shared.isProtectedDataAvailable {
        // Data is accessible — this is a genuine failure, not BFU.
        // Retry once for transient file system issues.
        logger.info("Retrying container creation")
        do {
          container = try PersistenceStore.createContainer()
        } catch {
          let nsError = error as NSError
          logger.fault("""
          Container creation failed after retry: \
          domain=\(nsError.domain, privacy: .public) \
          code=\(nsError.code, privacy: .public) \
          desc=\(nsError.localizedDescription, privacy: .public) \
          userInfo=\(String(describing: nsError.userInfo), privacy: .public)
          """)
          fatalError("ModelContainer creation failed after retry while data is available: \(nsError.domain) \(nsError.code)")
        }
        let appState = AppState(modelContainer: container)
        _appState = State(initialValue: appState)
        intentBridge.adopt(appState)
        return
      }

      // Before first unlock: the encrypted store is inaccessible. Create a throwaway
      // in-memory container so the struct can initialize. The .task body will wait for
      // data protection and replace this with the real store before doing any work.
      logger.warning("Protected data unavailable (before first unlock), deferring initialization")
      do {
        container = try PersistenceStore.createContainer(inMemory: true)
      } catch {
        fatalError("In-memory ModelContainer creation failed: \(error)")
      }
      _awaitingDataProtection = State(initialValue: true)
      usingThrowawayStore = true
    }
    let appState = AppState(modelContainer: container)
    _appState = State(initialValue: appState)
    // BFU throwaway: defer adoption to the post-unlock swap so a pre-unlock
    // intent reads nil, not an empty store.
    if !usingThrowawayStore {
      intentBridge.adopt(appState)
    }
  }

  var body: some Scene {
    WindowGroup {
      ContentView()
        .environment(\.appState, appState)
        .environment(\.appTheme, appState.themeService.current)
        .tint(appState.themeService.current.chromeTint)
        .preferredColorScheme(appState.themeService.effectiveColorScheme)
      #if !SIDELOAD
        .task(id: ObjectIdentifier(appState)) { await appState.storeState.service.load() }
      #endif
        .task {
          if awaitingDataProtection {
            await waitForProtectedData()
            do {
              let container = try PersistenceStore.createContainer()
              // Tear down the BFU-bootstrap AppState's StoreService listener Task
              // before swapping in the real AppState — otherwise the bootstrap
              // instance's Transaction.updates listener leaks for the process
              // lifetime and every later transaction event fires `walkCurrentEntitlements`
              // twice (once per orphaned StoreService).
              appState.shutdown()
              let realAppState = AppState(modelContainer: container)
              appState = realAppState
              // First real store on the BFU path; the bridge was
              // left nil in `init()` until now.
              intentBridge.adopt(realAppState)
              awaitingDataProtection = false
            } catch {
              let nsError = error as NSError
              logger.fault("""
              Container creation failed after unlock: \
              domain=\(nsError.domain, privacy: .public) \
              code=\(nsError.code, privacy: .public) \
              desc=\(nsError.localizedDescription, privacy: .public) \
              userInfo=\(String(describing: nsError.userInfo), privacy: .public)
              """)
              fatalError("ModelContainer creation failed after protected data became available: \(nsError.domain) \(nsError.code)")
            }
          }

          try? Tips.configure([
            .displayFrequency(.immediate)
          ])

          #if DEBUG
            if ProcessInfo.processInfo.isScreenshotMode {
              await setupScreenshotMode()
            } else {
              await appState.initialize()
            }
          #else
            await appState.initialize()
          #endif

          await runInitialForegroundReconciliationIfNeeded()
        }
        .onOpenURL { _ in
          // meshcoreone://status — tapped from Live Activity
          // Opening the app is sufficient; future: navigate based on url.host
        }
        .onChange(of: scenePhase) { oldPhase, newPhase in
          handleScenePhaseChange(from: oldPhase, to: newPhase)
        }
    }
  }

  #if DEBUG && targetEnvironment(simulator)
    /// Sets up the app for App Store screenshot capture.
    /// Bypasses onboarding and auto-connects to simulator with mock data.
    @MainActor
    private func setupScreenshotMode() async {
      // Bypass onboarding
      appState.onboarding.hasCompletedOnboarding = true

      // Persist simulator device ID for auto-reconnect
      UserDefaults.standard.set(
        MockDataProvider.simulatorDeviceID.uuidString,
        forKey: PersistenceKeys.lastConnectedDeviceID
      )

      // Initialize app (will auto-connect to simulator device)
      await appState.initialize()
    }

  #elseif DEBUG
    @MainActor
    private func setupScreenshotMode() async {
      // Screenshot mode only works in simulator
      await appState.initialize()
    }
  #endif

  private func waitForProtectedData() async {
    guard !UIApplication.shared.isProtectedDataAvailable else { return }
    let notification = UIApplication.protectedDataDidBecomeAvailableNotification
    await withTaskGroup(of: Void.self) { group in
      group.addTask {
        for await _ in NotificationCenter.default.notifications(named: notification) {
          return
        }
      }
      group.addTask {
        while await !UIApplication.shared.isProtectedDataAvailable {
          try? await Task.sleep(for: .seconds(1))
        }
      }
      await group.next()
      group.cancelAll()
    }
  }

  private func handleScenePhaseChange(from oldPhase: ScenePhase, to newPhase: ScenePhase) {
    switch newPhase {
    case .active:
      Task {
        await appState.handleReturnToForeground()
      }
    case .background:
      appState.handleEnterBackground()
      Task {
        await appState.services?.debugLogBuffer.flush()
      }
    case .inactive:
      break
    @unknown default:
      break
    }
  }

  private func runInitialForegroundReconciliationIfNeeded() async {
    guard scenePhase == .active else { return }
    await appState.handleReturnToForeground()
  }
}
