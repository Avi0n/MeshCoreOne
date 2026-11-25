import Foundation
@testable import MC1
import SwiftUI
import Testing

@Suite("ForegroundMinuteSchedule Tests")
struct ForegroundMinuteScheduleTests {
  @Test
  func `paused schedule yields only startDate`() {
    let start = Date(timeIntervalSince1970: 1_700_000_030)
    let entries = Array(ForegroundMinuteSchedule(isPaused: true).entries(from: start, mode: .normal))
    #expect(entries == [start])
  }

  @Test
  func `active schedule matches everyMinute entries`() {
    let start = Date(timeIntervalSince1970: 1_700_000_030)
    let actual = Array(
      ForegroundMinuteSchedule(isPaused: false).entries(from: start, mode: .normal).prefix(3)
    )
    let expected = Array(
      EveryMinuteTimelineSchedule.everyMinute.entries(from: start, mode: .normal).prefix(3)
    )
    #expect(actual == expected)
  }
}
