import Foundation

/// An in-flight Ask-to-Buy / SCA purchase awaiting approval. Drives the pending banner.
/// Resolution arrives later via `StoreService`'s `Transaction.updates` listener.
public struct PendingPurchase: Sendable, Equatable {
    public let productID: String
    public let displayName: String

    public init(productID: String, displayName: String) {
        self.productID = productID
        self.displayName = displayName
    }
}
