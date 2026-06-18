import SwiftUI

/// Pinned glass filter bar for `AddHopPickerView`. Lives in its own file so it
/// can read `@Environment(\.isSearching)` from its descendant-of-`.searchable`
/// position — reading `isSearching` on the view that declares `.searchable`
/// isn't reliable. While searching, the picker is muted and disabled (searches
/// behave as if `All` were selected, matching `DiscoverSegmentPicker`).
struct AddHopSegmentPicker: View {
    @Binding var selection: AddHopFilter
    @Environment(\.isSearching) private var isSearching

    var body: some View {
        GlassFilterBar(
            selection: $selection,
            isSearching: isSearching,
            pickerLabel: L10n.Contacts.Contacts.PathEdit.filterPickerLabel,
            title: { $0.localizedLabel }
        )
    }
}
