import SwiftUI

extension View {
  /// Enables row `.swipeActions` inside this `ScrollView` on iOS 27.
  /// Earlier OSes have no container API, so the row modifiers stay inert outside `List`.
  @ViewBuilder
  func swipeActionsContainerIfAvailable() -> some View {
    if #available(iOS 27, *) {
      swipeActionsContainer()
    } else {
      self
    }
  }
}
