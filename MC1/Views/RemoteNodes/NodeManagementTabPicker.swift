import SwiftUI

/// Pinned glass segment switcher for node management pages;
/// floating Liquid Glass pills on iOS 26, a segmented `Picker` on iOS 18.
struct NodeManagementTabPicker: View {
  @Binding var selection: NodeManagementTab
  var tabs: [NodeManagementTab] = Array(NodeManagementTab.allCases)

  var body: some View {
    GlassFilterBar(
      selection: $selection,
      isSearching: false,
      pickerLabel: L10n.RemoteNodes.RemoteNodes.Settings.Tab.picker,
      title: { $0.label },
      size: .large,
      filters: tabs
    )
  }
}
