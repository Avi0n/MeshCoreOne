import SwiftUI

/// The leading radio status control (`BLEStatusIndicatorView`) as a reusable toolbar item. The iPad
/// sidebar owns the radio while it is visible, so each section surfaces the control in its own
/// toolbar only when it owns it — on iPhone (no sidebar) or while the iPad sidebar is collapsed.
/// Each call site passes that per-section condition; the placement stays defined once here.
@MainActor
@ToolbarContentBuilder
func bleStatusToolbarItem(isVisible: Bool) -> some ToolbarContent {
    if isVisible {
        ToolbarItem(placement: .topBarLeading) {
            BLEStatusIndicatorView()
        }
    }
}
