import SwiftUI

/// Pinned glass filter bar for the Chats tab.
struct ChatFilterPicker: View {
    @Binding var selection: ChatFilter
    @Environment(\.isSearching) private var isSearching

    var body: some View {
        GlassFilterBar(
            selection: $selection,
            isSearching: isSearching,
            pickerLabel: L10n.Chats.Chats.Filter.title,
            title: { $0.localizedName }
        )
    }
}
