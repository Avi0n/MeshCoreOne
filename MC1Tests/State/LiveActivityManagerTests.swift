import Testing
import Foundation
@testable import MC1

@Suite("LiveActivityManager packet rate")
@MainActor
struct LiveActivityManagerTests {

    private let now = Date(timeIntervalSinceReferenceDate: 1_000_000)

    @Test("no packets reads as zero")
    func emptyIsZero() {
        #expect(LiveActivityManager.packetsPerMinute(timestamps: [], now: now) == 0)
    }

    @Test("default 60s window returns the exact count, matching the RX Log")
    func sixtySecondWindowIsTrueCount() {
        let timestamps = (1...7).map { now.addingTimeInterval(-Double($0) * 5) }
        #expect(LiveActivityManager.packetsPerMinute(timestamps: timestamps, now: now) == 7)
    }

    @Test("packets older than the window are excluded")
    func staleTimestampsExcluded() {
        let timestamps = [
            now.addingTimeInterval(-10),
            now.addingTimeInterval(-59),
            now.addingTimeInterval(-61),
            now.addingTimeInterval(-120)
        ]
        #expect(LiveActivityManager.packetsPerMinute(timestamps: timestamps, now: now) == 2)
    }

    @Test("a timestamp exactly at the cutoff is included")
    func boundaryIsInclusive() {
        let atCutoff = now.addingTimeInterval(-LiveActivityManager.packetWindowSeconds)
        #expect(LiveActivityManager.packetsPerMinute(timestamps: [atCutoff], now: now) == 1)
    }

    @Test("a shorter window still projects to a per-minute rate")
    func shorterWindowProjects() {
        let timestamps = (1...3).map { now.addingTimeInterval(-Double($0)) }
        #expect(LiveActivityManager.packetsPerMinute(timestamps: timestamps, now: now, window: 15) == 12)
    }
}
