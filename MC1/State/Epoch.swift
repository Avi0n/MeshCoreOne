import Foundation

/// Ownership check for one in-flight job. Hiding a screen does not bump it.
/// `reset()`, an explicit stop, or a replacement job does.
struct Epoch: Equatable, Sendable {
  private var generation: UInt = 0

  mutating func bump() {
    generation &+= 1
  }

  func ticket() -> Ticket {
    Ticket(generation: generation)
  }

  struct Ticket: Equatable, Sendable {
    fileprivate let generation: UInt

    func isCurrent(in epoch: Epoch) -> Bool {
      generation == epoch.generation
    }

    /// Publishes `loaded` only while this ticket still owns `epoch` and the
    /// field is unchanged since the last publish.
    func publish<T: Equatable>(
      _ loaded: T,
      current: inout T,
      baseline: inout T,
      in epoch: Epoch
    ) -> Bool {
      guard isCurrent(in: epoch), current == baseline else { return false }
      current = loaded
      baseline = loaded
      return true
    }

    /// Moves the baseline to `sent` only when the field still holds that value.
    func adoptApplied<T: Equatable>(
      _ sent: T,
      current: T,
      baseline: inout T,
      in epoch: Epoch
    ) -> Bool {
      guard isCurrent(in: epoch), current == sent else { return false }
      baseline = sent
      return true
    }
  }
}
