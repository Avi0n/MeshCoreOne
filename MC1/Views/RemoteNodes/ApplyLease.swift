import Observation

/// One Apply owns `inFlight` until a newer `begin` replaces it.
/// `clearIfCurrent` leaves that newer owner's flag set.
@Observable
@MainActor
final class ApplyLease {
  private(set) var inFlight = false
  private var generation: UInt = 0

  struct ID: Equatable {
    fileprivate let generation: UInt
  }

  func begin() -> ID {
    generation &+= 1
    inFlight = true
    return ID(generation: generation)
  }

  func clearIfCurrent(_ id: ID) {
    guard isCurrent(id) else { return }
    inFlight = false
  }

  func isCurrent(_ id: ID) -> Bool {
    id.generation == generation
  }
}
