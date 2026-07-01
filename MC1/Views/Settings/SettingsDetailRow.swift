import SwiftUI

/// A settings list row that opens a `SettingsDetail` page. The value-based `NavigationLink` pushes
/// onto the compact stack (via `SettingsView`'s `navigationDestination`) and drives the iPad split's
/// `List(selection:)` binding (`NavigationCoordinator.selectedSetting`, read by `SettingsDetailView`
/// in the detail column).
struct SettingsDetailRow<Label: View>: View {
  let detail: SettingsDetail
  @ViewBuilder let label: () -> Label

  var body: some View {
    NavigationLink(value: detail) {
      label()
    }
  }
}
