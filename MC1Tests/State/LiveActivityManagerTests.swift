import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import SwiftUI
import Testing
import UIKit

@Suite("LiveActivityManager packet rate")
@MainActor
struct LiveActivityManagerTests {
  private let now = Date(timeIntervalSinceReferenceDate: 1_000_000)

  @Test
  func `no packets reads as zero`() {
    #expect(LiveActivityManager.packetsPerMinute(timestamps: [], now: now) == 0)
  }

  @Test
  func `default 60s window returns the exact count, matching the RX Log`() {
    let timestamps = (1...7).map { now.addingTimeInterval(-Double($0) * 5) }
    #expect(LiveActivityManager.packetsPerMinute(timestamps: timestamps, now: now) == 7)
  }

  @Test
  func `packets older than the window are excluded`() {
    let timestamps = [
      now.addingTimeInterval(-10),
      now.addingTimeInterval(-59),
      now.addingTimeInterval(-61),
      now.addingTimeInterval(-120)
    ]
    #expect(LiveActivityManager.packetsPerMinute(timestamps: timestamps, now: now) == 2)
  }

  @Test
  func `a timestamp exactly at the cutoff is included`() {
    let atCutoff = now.addingTimeInterval(-LiveActivityManager.packetWindowSeconds)
    #expect(LiveActivityManager.packetsPerMinute(timestamps: [atCutoff], now: now) == 1)
  }

  @Test
  func `a shorter window still projects to a per-minute rate`() {
    let timestamps = (1...3).map { now.addingTimeInterval(-Double($0)) }
    #expect(LiveActivityManager.packetsPerMinute(timestamps: timestamps, now: now, window: 15) == 12)
  }

  @Test
  func `unchanged content waits until the stale date is close`() {
    let state = sampleState(packetsPerMinute: 4)
    #expect(!LiveActivityManager.shouldPublishUpdate(
      current: state,
      proposed: state,
      currentStaleDate: now.addingTimeInterval(50),
      now: now,
      refreshLeadTime: 30
    ))
    #expect(LiveActivityManager.shouldPublishUpdate(
      current: state,
      proposed: state,
      currentStaleDate: now.addingTimeInterval(30),
      now: now,
      refreshLeadTime: 30
    ))
  }

  @Test
  func `nextUpdateDelay respects cadence interval and clamps against earlier staleDate`() {
    let foreground = LiveActivityManager.nextUpdateDelay(
      lastFlushDate: now.addingTimeInterval(-5),
      now: now,
      staleDate: now.addingTimeInterval(55),
      cadence: .foreground
    )
    #expect(foreground == 10)

    let background = LiveActivityManager.nextUpdateDelay(
      lastFlushDate: now.addingTimeInterval(-10),
      now: now,
      staleDate: now.addingTimeInterval(110),
      cadence: .background
    )
    #expect(background == 50)

    let handoff = LiveActivityManager.nextUpdateDelay(
      lastFlushDate: now.addingTimeInterval(-15),
      now: now,
      staleDate: now.addingTimeInterval(45),
      cadence: .background
    )
    #expect(handoff == 15)

    let insideLeadWindow = LiveActivityManager.nextUpdateDelay(
      lastFlushDate: now.addingTimeInterval(-35),
      now: now,
      staleDate: now.addingTimeInterval(25),
      cadence: .background
    )
    #expect(insideLeadWindow == 0)
  }

  @Test
  func `idle activity with zero rate does not republish on nil or approaching staleDate`() {
    let idle = sampleState(packetsPerMinute: 0)
    #expect(!LiveActivityManager.shouldPublishUpdate(
      current: idle,
      proposed: idle,
      currentStaleDate: nil,
      now: now,
      refreshLeadTime: 30
    ))
    #expect(!LiveActivityManager.shouldPublishUpdate(
      current: idle,
      proposed: idle,
      currentStaleDate: now.addingTimeInterval(5),
      now: now,
      refreshLeadTime: 30
    ))
  }

  @Test
  func `decay timer does not start without active activity or in background`() async {
    let manager = LiveActivityManager()
    var phase: ScenePhase = .background
    manager.scenePhaseProvider = { phase }

    let device = DeviceDTO(
      from: Device(
        radioID: UUID(),
        publicKey: Data(repeating: 0x11, count: 32),
        nodeName: "TestRadio"
      )
    )

    var unreadLookups = 0
    await manager.handleConnectionReady(
      device: device,
      ocvArray: [],
      unreadCount: {
        unreadLookups += 1
        return 0
      },
      isCurrent: { true }
    )
    #expect(!manager.hasActiveActivity)
    #expect(!manager.isDecayTimerActiveForTesting)
    #expect(unreadLookups == 0)

    await manager.handlePacketReceived()
    #expect(!manager.isDecayTimerActiveForTesting)

    phase = .active
    manager.handleReturnToForeground()
    await manager.handlePacketReceived()
    #expect(!manager.isDecayTimerActiveForTesting)

    phase = .background
    manager.handleEnterBackground()
    #expect(!manager.isDecayTimerActiveForTesting)
  }

  @Test
  func `content state decodes legacy integer rate and round-trips nil locked rate`() throws {
    let legacyJSON = Data("""
    {"isConnected":true,"packetsPerMinute":5,"unreadCount":2}
    """.utf8)
    let legacy = try JSONDecoder().decode(MeshStatusAttributes.ContentState.self, from: legacyJSON)
    #expect(legacy.packetsPerMinute == 5)

    let paused = sampleState(packetsPerMinute: nil)
    let encoded = try JSONEncoder().encode(paused)
    let decoded = try JSONDecoder().decode(MeshStatusAttributes.ContentState.self, from: encoded)
    #expect(decoded == paused)
    #expect(decoded.packetsPerMinute == nil)
  }

  @Test
  func `locked nil rate never republishes while lock and unlock transitions do`() {
    let paused = sampleState(packetsPerMinute: nil)
    let active = sampleState(packetsPerMinute: 5)
    #expect(!LiveActivityManager.shouldPublishUpdate(
      current: paused,
      proposed: paused,
      currentStaleDate: nil,
      now: now,
      refreshLeadTime: 30
    ))
    #expect(!LiveActivityManager.shouldPublishUpdate(
      current: paused,
      proposed: paused,
      currentStaleDate: now.addingTimeInterval(5),
      now: now,
      refreshLeadTime: 30
    ))
    #expect(LiveActivityManager.shouldPublishUpdate(
      current: active,
      proposed: paused,
      currentStaleDate: now.addingTimeInterval(50),
      now: now,
      refreshLeadTime: 30
    ))
    #expect(LiveActivityManager.shouldPublishUpdate(
      current: paused,
      proposed: sampleState(packetsPerMinute: 0),
      currentStaleDate: nil,
      now: now,
      refreshLeadTime: 30
    ))
  }

  @Test
  func `device lock notifications pause rate updates while still tracking rx timestamps`() async throws {
    let center = NotificationCenter()
    let manager = LiveActivityManager()
    manager.startObservingDeviceLockState(notificationCenter: center, initiallyLocked: false)
    defer { manager.stopObservingDeviceLockState() }

    #expect(!manager.isDeviceLocked)
    center.post(name: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil)
    try await waitUntil { manager.isDeviceLocked }

    await manager.handlePacketReceived()
    #expect(manager.recentPacketsPerMinuteForTesting == 1)

    center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
    try await waitUntil { !manager.isDeviceLocked }
    #expect(manager.recentPacketsPerMinuteForTesting == 1)
  }

  private func waitUntil(
    timeout: Duration = .seconds(1),
    condition: () -> Bool
  ) async throws {
    let deadline = ContinuousClock.now + timeout
    while !condition() {
      if ContinuousClock.now >= deadline {
        Issue.record("Timed out waiting for lock state transition")
        return
      }
      await Task.yield()
      try await Task.sleep(for: .milliseconds(10))
    }
  }

  @Test
  func `an unseen failed send takes the top row only while connected`() {
    var state = sampleState(packetsPerMinute: 4)
    #expect(!state.showsFailedSend)

    state.failedSends = FailedSendSummary(count: 1, repeatsHeard: 3)
    #expect(state.showsFailedSend)

    state.isConnected = false
    #expect(!state.showsFailedSend)
  }

  @Test
  func `a changed failed-send summary is published`() {
    let current = sampleState(packetsPerMinute: 4)
    var proposed = current
    proposed.failedSends = FailedSendSummary(count: 1, repeatsHeard: 0)
    #expect(LiveActivityManager.shouldPublishUpdate(
      current: current,
      proposed: proposed,
      currentStaleDate: now.addingTimeInterval(50),
      now: now,
      refreshLeadTime: 30
    ))
  }

  private func sampleState(packetsPerMinute: Int?) -> MeshStatusAttributes.ContentState {
    MeshStatusAttributes.ContentState(
      isConnected: true,
      batteryPercent: nil,
      packetsPerMinute: packetsPerMinute,
      unreadCount: 0,
      disconnectedDate: nil
    )
  }
}
