import SwiftUI

/// A settings list row whose value is `SettingsDetail`. `List(selection:)`
/// writes that value to `selectedSetting`, which the split detail renders.
struct SettingsDetailRow<Label: View>: View {
  let detail: SettingsDetail
  @ViewBuilder let label: () -> Label

  var body: some View {
    NavigationLink(value: detail) {
      label()
    }
  }
}
