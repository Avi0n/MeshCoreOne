@testable import MC1
import Testing

@Suite("AppState Lifecycle Transition Tests")
@MainActor
struct LifecycleTransitionTests {
  @Test
  func `BLE foreground waits for queued background transition`() async {
    let appState = AppState()
    let recorder = TransitionRecorder()

    appState.setBLELifecycleOverridesForTesting(
      enterBackground: {
        await recorder.record("background-start")
        try? await Task.sleep(for: .milliseconds(150))
        await recorder.record("background-end")
      },
      becomeActive: {
        await recorder.record("foreground-start")
        await recorder.record("foreground-end")
      }
    )

    appState.handleEnterBackground()
    await appState.handleReturnToForeground()

    let events = await recorder.events
    #expect(events == [
      "background-start",
      "background-end",
      "foreground-start",
      "foreground-end"
    ])
  }

  @Test
  func `Explicit disconnect runs the per-session teardown the loss path performs`() async {
    let appState = AppState()
    appState.settingsEventsTask = Task {
      while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(60))
      }
    }
    appState.navigation.nodesShowingDiscovery = true

    await appState.disconnect()

    #expect(appState.settingsEventsTask == nil)
    #expect(appState.navigation.nodesShowingDiscovery == false)
  }

  @Test
  func `rapid background-active bounces keep BLE transitions ordered`() async {
    let appState = AppState()
    let recorder = TransitionRecorder()

    appState.setBLELifecycleOverridesForTesting(
      enterBackground: {
        try? await Task.sleep(for: .milliseconds(20))
        await recorder.record("background")
      },
      becomeActive: {
        await recorder.record("foreground")
      }
    )

    for _ in 0..<5 {
      appState.handleEnterBackground()
      await appState.handleReturnToForeground()
    }

    let events = await recorder.events
    #expect(events.count == 10)

    for index in stride(from: 0, to: events.count, by: 2) {
      #expect(events[index] == "background")
      #expect(events[index + 1] == "foreground")
    }
  }
}

private actor TransitionRecorder {
  private(set) var events: [String] = []

  func record(_ event: String) {
    events.append(event)
  }
}
