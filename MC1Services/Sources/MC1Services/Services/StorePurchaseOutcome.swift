import Foundation

/// Non-error result of a purchase attempt. User cancellation is an outcome, not an error.
public enum StorePurchaseOutcome: Sendable, Equatable {
  case purchased
  case userCancelled
  /// Ask-to-Buy or Strong Customer Authentication: resolution arrives later via `Transaction.updates`.
  case pending
}
