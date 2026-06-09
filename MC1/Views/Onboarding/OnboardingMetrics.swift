import Foundation

/// Named layout constants for onboarding screens. Avoids bare numeric literals
/// scattered through `WelcomeView`, `PermissionsView`, `DeviceScanView`, etc.
enum OnboardingMetrics {
    static let heroSize: CGFloat = 130
    static let iconSize: CGFloat = 60
    static let cardCornerRadius: CGFloat = 12
    static let compactSpacing: CGFloat = 4
    static let titleStackSpacing: CGFloat = 8
    static let mediumSpacing: CGFloat = 12
    static let cardSpacing: CGFloat = 16
    static let contentPadding: CGFloat = 20
    static let largeSpacing: CGFloat = 24
    static let sheetTopPadding: CGFloat = 32
    static let minHitTarget: CGFloat = 44
    static let headerTopPadding: CGFloat = 40
}
