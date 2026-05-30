import Foundation
import StoreKit

/// Errors surfaced by `StoreService`. English `errorDescription` matches every other
/// MC1Services error type (L10n is not visible from this package); the MC1 view-model
/// layer maps these to localized copy.
public enum StoreServiceError: LocalizedError, Sendable, Equatable {
    case productsNotLoaded
    case productNotFound(productID: String)
    case purchaseFailed(reason: String)
    case verificationFailed
    case notEntitled
    case networkUnavailable
    case storefrontUnavailable
    case unsupported

    public var errorDescription: String? {
        switch self {
        case .productsNotLoaded:     "Products could not be loaded."
        case .productNotFound:       "Product not found."
        case .purchaseFailed(let r): "Purchase failed: \(r)"
        case .verificationFailed:    "Could not verify purchase."
        case .notEntitled:           "Purchases are restricted on this device."
        case .networkUnavailable:    "Network unavailable."
        case .storefrontUnavailable: "This product is not available in your region."
        case .unsupported:           "In-app purchases are not supported on this device."
        }
    }

    /// Maps a `StoreKitError` to a `StoreServiceError`, or `nil` when the error represents
    /// user cancellation — which callers surface as `StorePurchaseOutcome.userCancelled`,
    /// not as an alert.
    public static func from(_ error: StoreKitError) -> StoreServiceError? {
        if #available(iOS 18.4, macOS 15.4, tvOS 18.4, watchOS 11.4, visionOS 2.4, *),
           case .unsupported = error {
            return .unsupported
        }
        switch error {
        case .userCancelled:               return nil
        case .networkError:                return .networkUnavailable
        case .notAvailableInStorefront:    return .storefrontUnavailable
        case .notEntitled:                 return .notEntitled
        case .systemError(let underlying): return .purchaseFailed(reason: String(describing: underlying))
        case .unknown:                     return .purchaseFailed(reason: "Unknown StoreKit error")
        @unknown default:                  return .purchaseFailed(reason: "Unhandled StoreKit error")
        }
    }
}
