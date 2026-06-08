import SwiftUI

/// The radio status control (`BLEStatusIndicatorView`) as a reusable toolbar item. Every section
/// surfaces it unconditionally: the icon-only iPad sidebar is too narrow to host the control, so
/// each section owns it in its own toolbar. It sits at the leading edge by default; a section whose
/// leading slot is already taken (the Line of Sight panel's back button) passes `.topBarTrailing`
/// to move it to the right rather than crowd the title.
@MainActor
@ToolbarContentBuilder
func bleStatusToolbarItem(placement: ToolbarItemPlacement = .topBarLeading) -> some ToolbarContent {
    ToolbarItem(placement: placement) {
        BLEStatusIndicatorView()
    }
}
