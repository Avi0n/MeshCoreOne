import Foundation

/// Named layout constants for onboarding screens. Avoids bare numeric literals
/// scattered through `WelcomeView`, `PermissionsView`, `DeviceScanView`, etc.
enum OnboardingMetrics {
    static let heroSize: CGFloat = 130
    static let cardCornerRadius: CGFloat = 12
    static let cardSpacing: CGFloat = 16
    static let contentPadding: CGFloat = 20
    static let ctaCornerRadius: CGFloat = 14
    static let minHitTarget: CGFloat = 44
}
