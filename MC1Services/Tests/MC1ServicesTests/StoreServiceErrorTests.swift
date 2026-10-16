import Foundation
@testable import MC1Services
import StoreKit
import Testing

@Suite("StoreServiceError mapping")
struct StoreServiceErrorTests {
  @Test
  func `every case has a non-empty English description`() {
    let cases: [StoreServiceError] = [
      .productsNotLoaded, .productNotFound(productID: "x"),
      .purchaseFailed(reason: "boom"), .verificationFailed, .notEntitled,
      .networkUnavailable, .storefrontUnavailable, .unsupported
    ]
    for error in cases {
      #expect(error.errorDescription?.isEmpty == false)
    }
  }

  @Test
  func `purchaseFailed Equatable compares the reason string`() {
    #expect(StoreServiceError.purchaseFailed(reason: "a") == .purchaseFailed(reason: "a"))
    #expect(StoreServiceError.purchaseFailed(reason: "a") != .purchaseFailed(reason: "b"))
  }

  @Test
  func `network error maps to networkUnavailable`() {
    let mapped = StoreServiceError.from(.networkError(URLError(.notConnectedToInternet)))
    #expect(mapped == .networkUnavailable)
  }

  @Test
  func `storefront / unsupported / notEntitled map correctly`() {
    #expect(StoreServiceError.from(.notAvailableInStorefront) == .storefrontUnavailable)
    if #available(iOS 18.4, macOS 15.4, *) {
      #expect(StoreServiceError.from(.unsupported) == .unsupported)
    }
    #expect(StoreServiceError.from(.notEntitled) == .notEntitled)
  }

  @Test
  func `userCancelled maps to nil (handled as a non-error outcome)`() {
    #expect(StoreServiceError.from(.userCancelled) == nil)
  }

  @Test
  func `unknown maps to a purchaseFailed with a fixed reason`() {
    #expect(StoreServiceError.from(.unknown) == .purchaseFailed(reason: "Unknown StoreKit error"))
  }

  @Test
  func `systemError maps to purchaseFailed`() {
    let mapped = StoreServiceError.from(.systemError(URLError(.unknown)))
    if case .purchaseFailed = mapped { } else {
      Issue.record("expected .purchaseFailed, got \(String(describing: mapped))")
    }
  }
}
