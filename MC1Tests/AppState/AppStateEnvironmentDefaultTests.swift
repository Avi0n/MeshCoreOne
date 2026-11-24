import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import SwiftUI
import Testing

/// Must stay `@MainActor`: reading `EnvironmentValues().appState` takes the first-touch path
/// through `AppState.placeholder`'s `MainActor.assumeIsolated`, which traps off the main actor.
@Suite("AppState environment default", .serialized)
@MainActor
struct AppStateEnvironmentDefaultTests {
  private static func inMemoryContainer() throws -> ModelContainer {
    let config = ModelConfiguration(isStoredInMemoryOnly: true)
    return try ModelContainer(for: PersistenceStore.schema, configurations: [config])
  }

  @Test
  func `environment default is a single shared placeholder`() {
    let first = EnvironmentValues().appState
    let second = EnvironmentValues().appState
    #expect(first === second)
    #expect(first === AppState.placeholder)
  }

  @Test
  func `placeholder is inert with no services, transaction listener, or location manager`() {
    let placeholder = AppState.placeholder
    #expect(placeholder.services == nil)
    #expect(placeholder.storeState.service.transactionListenerTask == nil)
    placeholder.locationService.activateIfNeeded()
    #expect(!placeholder.locationService.hasInitializedLocationManager)
  }

  @Test
  func `placeholder init leaves the shared debug log buffer untouched`() throws {
    let previous = DebugLogBuffer.shared
    defer { DebugLogBuffer.shared = previous }

    DebugLogBuffer.shared = nil
    _ = try AppState(modelContainer: Self.inMemoryContainer(), isPlaceholder: true)
    #expect(DebugLogBuffer.shared == nil)

    let live = try AppState(modelContainer: Self.inMemoryContainer(), isPlaceholder: false)
    #expect(DebugLogBuffer.shared != nil)
    live.shutdown()
  }

  @Test
  func `onboarded AppState init does not initialize location manager`() async throws {
    let suiteName = "test.appstate.location.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }

    defaults.set(true, forKey: AppStorageKey.hasCompletedOnboarding.rawValue)
    let state = try AppState(modelContainer: Self.inMemoryContainer(), defaults: defaults)
    defer { state.shutdown() }

    await Task.yield()
    #expect(!state.locationService.hasInitializedLocationManager)
  }
}
