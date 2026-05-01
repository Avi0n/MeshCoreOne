import SwiftUI

/// Pinned glass filter bar for the Chats tab. On iOS 26 renders as Liquid
/// Glass capsule pills; on iOS 18 falls back to a segmented `Picker`.
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
