import SwiftUI
import UIKit

/// Bridges UIKit's `UIUserInterfaceLevel` into the SwiftUI environment as a simple elevated flag.
///
/// iPad renders grouped lists in an elevated trait context (split-view columns, presented sheets),
/// where the system collapses `systemGroupedBackground` onto `secondarySystemGroupedBackground` so
/// the surface-less default theme draws its rows flush with the canvas. Themed surfaces read this
/// flag to mirror that behavior. SwiftUI exposes no native accessor for the elevation trait, so the
/// official `UITraitBridgedEnvironmentKey` is the supported path.
private struct SurfaceElevationKey: UITraitBridgedEnvironmentKey {
    static let defaultValue = false

    static func read(from traitCollection: UITraitCollection) -> Bool {
        traitCollection.userInterfaceLevel == .elevated
    }

    static func write(to mutableTraits: inout UIMutableTraits, value: Bool) {
        mutableTraits.userInterfaceLevel = value ? .elevated : .base
    }
}

extension EnvironmentValues {
    /// `true` when the view sits in an elevated trait context (iPad split-view columns, sheets),
    /// where the system grouped-background tiers collapse to a single color.
    var isSurfaceElevated: Bool {
        get { self[SurfaceElevationKey.self] }
        set { self[SurfaceElevationKey.self] = newValue }
    }
}
