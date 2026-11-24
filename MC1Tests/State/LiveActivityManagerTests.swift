import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

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
      updateInterval: LiveActivityManager.updateInterval,
      staleDate: now.addingTimeInterval(55),
      staleRefreshLeadTime: LiveActivityManager.staleRefreshLeadTime
    )
    #expect(foreground == 10)

    let background = LiveActivityManager.nextUpdateDelay(
      lastFlushDate: now.addingTimeInterval(-10),
      now: now,
      updateInterval: LiveActivityManager.backgroundUpdateInterval,
      staleDate: now.addingTimeInterval(110),
      staleRefreshLeadTime: LiveActivityManager.backgroundStaleRefreshLeadTime
    )
    #expect(background == 50)

    let handoff = LiveActivityManager.nextUpdateDelay(
      lastFlushDate: now.addingTimeInterval(-15),
      now: now,
      updateInterval: LiveActivityManager.backgroundUpdateInterval,
      staleDate: now.addingTimeInterval(45),
      staleRefreshLeadTime: LiveActivityManager.backgroundStaleRefreshLeadTime
    )
    #expect(handoff == 15)

    let insideLeadWindow = LiveActivityManager.nextUpdateDelay(
      lastFlushDate: now.addingTimeInterval(-35),
      now: now,
      updateInterval: LiveActivityManager.backgroundUpdateInterval,
      staleDate: now.addingTimeInterval(25),
      staleRefreshLeadTime: LiveActivityManager.backgroundStaleRefreshLeadTime
    )
    #expect(insideLeadWindow == 0)
  }

  @Test
  func `decay timer does not start without active activity or in background`() async {
    let manager = LiveActivityManager()
    var isForeground = false
    var isForegroundActive = false
    manager.isInForegroundProvider = { isForeground }
    manager.isForegroundActiveProvider = { isForegroundActive }

    let device = DeviceDTO(
      from: Device(
        radioID: UUID(),
        publicKey: Data(repeating: 0x11, count: 32),
        nodeName: "TestRadio"
      )
    )

    await manager.handleConnectionReady(device: device, ocvArray: [], unreadCount: 0, isCurrent: { true })
    #expect(!manager.hasActiveActivity)
    #expect(!manager.isDecayTimerActiveForTesting)

    await manager.handlePacketReceived()
    #expect(!manager.isDecayTimerActiveForTesting)

    isForeground = true
    manager.handleReturnToForeground()
    await manager.handlePacketReceived()
    #expect(!manager.isDecayTimerActiveForTesting)

    isForeground = false
    isForegroundActive = false
    manager.handleEnterBackground()
    #expect(!manager.isDecayTimerActiveForTesting)
  }

  private func sampleState(packetsPerMinute: Int) -> MeshStatusAttributes.ContentState {
    MeshStatusAttributes.ContentState(
      isConnected: true,
      batteryPercent: nil,
      packetsPerMinute: packetsPerMinute,
      unreadCount: 0,
      disconnectedDate: nil
    )
  }
}
