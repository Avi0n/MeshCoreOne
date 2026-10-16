import SwiftUI

extension View {
  /// Presents the shared `AddHopPickerView` for the given intent binding.
  ///
  /// On the iPad app on Mac a `navigationDestination` push inside a split-view
  /// detail column renders without the system back button, stranding the user on
  /// the picker with no way back. Only a caller inside such a column knows it is
  /// affected, so it opts in via `inDetailColumn`; there the picker is presented
  /// as a sheet that carries its own dismiss control. Callers presenting from
  /// their own `NavigationStack` (e.g. a modal sheet) keep the push, where the
  /// system back button works on every platform.
  @ViewBuilder
  func addHopPicker(
    for intent: Binding<AddHopIntent?>,
    source: any HopPickerSource,
    inDetailColumn: Bool = false
  ) -> some View {
    if inDetailColumn, ProcessInfo.processInfo.isiOSAppOnMac {
      sheet(item: intent) { intent in
        NavigationStack {
          AddHopPickerView(viewModel: source, intent: intent, presentsOwnDismiss: true)
        }
        .presentationSizing(.page)
        .presentationDragIndicator(.visible)
      }
    } else {
      navigationDestination(item: intent) { intent in
        AddHopPickerView(viewModel: source, intent: intent)
      }
    }
  }
}
