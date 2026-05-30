import Testing
import StoreKit
import Foundation
@testable import MC1Services

@Suite("StoreServiceError mapping")
struct StoreServiceErrorTests {

    @Test("every case has a non-empty English description")
    func descriptions() {
        let cases: [StoreServiceError] = [
            .productsNotLoaded, .productNotFound(productID: "x"),
            .purchaseFailed(reason: "boom"), .verificationFailed, .notEntitled,
            .networkUnavailable, .storefrontUnavailable, .unsupported
        ]
        for error in cases {
            #expect(error.errorDescription?.isEmpty == false)
        }
    }

    @Test("purchaseFailed Equatable compares the reason string")
    func purchaseFailedEquatable() {
        #expect(StoreServiceError.purchaseFailed(reason: "a") == .purchaseFailed(reason: "a"))
        #expect(StoreServiceError.purchaseFailed(reason: "a") != .purchaseFailed(reason: "b"))
    }

    @Test("network error maps to networkUnavailable")
    func mapNetwork() {
        let mapped = StoreServiceError.from(.networkError(URLError(.notConnectedToInternet)))
        #expect(mapped == .networkUnavailable)
    }

    @Test("storefront / unsupported / notEntitled map correctly")
    func mapDirectCases() {
        #expect(StoreServiceError.from(.notAvailableInStorefront) == .storefrontUnavailable)
        if #available(iOS 18.4, macOS 15.4, *) {
            #expect(StoreServiceError.from(.unsupported) == .unsupported)
        }
        #expect(StoreServiceError.from(.notEntitled) == .notEntitled)
    }

    @Test("userCancelled maps to nil (handled as a non-error outcome)")
    func mapUserCancelled() {
        #expect(StoreServiceError.from(.userCancelled) == nil)
    }

    @Test("unknown maps to a purchaseFailed with a fixed reason")
    func mapUnknown() {
        #expect(StoreServiceError.from(.unknown) == .purchaseFailed(reason: "Unknown StoreKit error"))
    }

    @Test("systemError maps to purchaseFailed")
    func mapSystemError() {
        let mapped = StoreServiceError.from(.systemError(URLError(.unknown)))
        if case .purchaseFailed = mapped { } else {
            Issue.record("expected .purchaseFailed, got \(String(describing: mapped))")
        }
    }
}
