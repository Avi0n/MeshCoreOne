import Foundation

/// Gate for suites that depend on a loaded StoreKit catalog via `SKTestSession`.
///
/// Under `xcodebuild test`, iOS 26.x simulators deliver 0 products to storekitd (Apple
/// regression FB22237318 / FB22774836), so every product-dependent assertion falsely fails.
/// Suites apply `.enabled(if: StoreKitTestAvailability.servesProducts)` so they skip on the
/// affected runtime — keeping the default iOS 26 run green — and run on iOS 18.x, which
/// `make test-store` pins explicitly.
enum StoreKitTestAvailability {
    /// iOS 26.x is the known-broken runtime; every other version serves products normally.
    static let brokenMajorVersion = 26

    static var servesProducts: Bool {
        ProcessInfo.processInfo.operatingSystemVersion.majorVersion != brokenMajorVersion
    }
}
