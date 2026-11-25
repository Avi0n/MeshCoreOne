import SwiftUI

/// Minute-aligned timeline schedule that pauses scheduled ticks while backgrounded
/// without altering SwiftUI view identity across scene phase transitions.
struct ForegroundMinuteSchedule: TimelineSchedule {
  struct Entries: Sequence {
    private let baseEntries: EveryMinuteTimelineSchedule.Entries?
    private let singleDate: Date?

    fileprivate init(startDate: Date, mode: TimelineScheduleMode, isPaused: Bool) {
      if isPaused {
        baseEntries = nil
        singleDate = startDate
      } else {
        baseEntries = EveryMinuteTimelineSchedule.everyMinute.entries(from: startDate, mode: mode)
        singleDate = nil
      }
    }

    func makeIterator() -> Iterator {
      Iterator(
        baseIterator: baseEntries?.makeIterator(),
        pendingSingleDate: singleDate
      )
    }

    struct Iterator: IteratorProtocol {
      private var baseIterator: EveryMinuteTimelineSchedule.Entries.Iterator?
      private var pendingSingleDate: Date?

      fileprivate init(
        baseIterator: EveryMinuteTimelineSchedule.Entries.Iterator?,
        pendingSingleDate: Date?
      ) {
        self.baseIterator = baseIterator
        self.pendingSingleDate = pendingSingleDate
      }

      mutating func next() -> Date? {
        if let date = pendingSingleDate {
          pendingSingleDate = nil
          return date
        }
        return baseIterator?.next()
      }
    }
  }

  let isPaused: Bool

  func entries(from startDate: Date, mode: TimelineScheduleMode) -> Entries {
    Entries(startDate: startDate, mode: mode, isPaused: isPaused)
  }
}
