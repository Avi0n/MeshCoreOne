/// Keeps write pacing and operation deadlines on the same monotonic time source.
struct BLEOperationClock: Sendable {
  let now: @Sendable () -> ContinuousClock.Instant
  let sleep: @Sendable (Duration) async throws -> Void

  static let continuous = BLEOperationClock(
    now: { .now },
    sleep: { try await Task.sleep(for: $0) }
  )
}
