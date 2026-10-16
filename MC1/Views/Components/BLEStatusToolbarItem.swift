import SwiftUI

/// Radio status as a toolbar item. Section lists, Map, and Tools host it in the
/// leading slot by default. Pass a different placement when that slot is taken.
@MainActor
@ToolbarContentBuilder
func bleStatusToolbarItem(placement: ToolbarItemPlacement = .topBarLeading) -> some ToolbarContent {
  if #available(iOS 27, *) {
    ToolbarItem(placement: placement) {
      BLEStatusIndicatorView()
    }
    .visibilityPriority(.high)
  } else {
    ToolbarItem(placement: placement) {
      BLEStatusIndicatorView()
    }
  }
}
