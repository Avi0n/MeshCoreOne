import Foundation

/// Tracks progress of a streamed read (the contact notification stream or the windowed
/// channel-read pipeline) so a watchdog can distinguish "still streaming" from "idle" by
/// comparing the generation across an inactivity gap.
actor StreamProgressTracker {
    struct Snapshot: Sendable {
        let generation: Int
        let elapsed: TimeInterval
    }

    private var generation = 0
    private let startedAt = Date()

    func markProgress() {
        generation += 1
    }

    func snapshot() -> Snapshot {
        Snapshot(
            generation: generation,
            elapsed: Date().timeIntervalSince(startedAt)
        )
    }
}
