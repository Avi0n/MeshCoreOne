import MC1Services
import SwiftUI

/// Country + state picker used in onboarding step 4 and Settings → Radio → Location.
/// Hides the state row for countries with no subdivision catalog.
struct RegionPickerView: View {
  @Binding var selection: RegionSelection?
  @State private var activeSheet: RegionPickerRows.Sheet?

  var body: some View {
    Form {
      Section {
        RegionPickerRows(selection: $selection, activeSheet: $activeSheet)
      }
    }
    .regionPickerSheet($activeSheet, selection: $selection)
  }
}
