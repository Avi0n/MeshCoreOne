import Foundation
@testable import MC1Services
import Testing

@Suite("Radio WiFi uptime proof")
struct RadioWiFiResetObservationTests {
  @Test(arguments: Scenario.allCases)
  func `only supported uptime observations exclude continuous firmware progress`(_ scenario: Scenario) {
    let start = ContinuousClock.now
    let before = RadioWiFiResetObservation(
      uptimeSeconds: scenario.before, startedAt: start, completedAt: start
    )
    let end = start + .seconds(scenario.elapsed)
    let after = RadioWiFiResetObservation(
      uptimeSeconds: scenario.after, startedAt: end, completedAt: end
    )
    #expect(after.provesReset(since: before) == (scenario == .reset))
  }

  @Test
  func `overlapping observation windows cannot prove reset`() {
    let start = ContinuousClock.now
    let before = RadioWiFiResetObservation(uptimeSeconds: 1000, startedAt: start, completedAt: start + .seconds(2))
    let overlap = RadioWiFiResetObservation(uptimeSeconds: 0, startedAt: start + .seconds(1), completedAt: start + .seconds(3))
    #expect(!overlap.provesReset(since: before))
  }

  enum Scenario: CaseIterable, Sendable {
    case reset, normalProgress, shortUptime, millisWrap, longGap, invalidUptime, impossibleBoot, lateReply

    var before: UInt32 {
      switch self {
      case .shortUptime: 1
      case .millisWrap: 4_294_967
      case .invalidUptime: .max
      default: 1000
      }
    }

    var after: UInt32 {
      switch self {
      case .normalProgress: 1001
      case .impossibleBoot: 100
      case .millisWrap: 0
      default: 1
      }
    }

    var elapsed: Int64 {
      switch self {
      case .longGap: 4_294_968
      case .lateReply: 4_294_000
      default: 1
      }
    }
  }
}
