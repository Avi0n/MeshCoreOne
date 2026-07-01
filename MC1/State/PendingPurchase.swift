import Foundation

/// An in-flight Ask-to-Buy / SCA purchase awaiting approval. Drives the pending banner.
/// Resolution arrives later via `StoreService`'s `Transaction.updates` listener.
struct PendingPurchase: Equatable {
  let productID: String
  let displayName: String
}
