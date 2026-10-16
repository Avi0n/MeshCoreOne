import Foundation

/// Lifecycle of the product catalog load.
public enum StoreLoadState: Sendable, Equatable {
  case idle
  case loading
  case loaded
  case failed
}
