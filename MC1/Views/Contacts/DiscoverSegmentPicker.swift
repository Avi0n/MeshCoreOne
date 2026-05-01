import SwiftUI

/// Pinned glass filter bar for the Discovery sub-view.
struct DiscoverSegmentPicker: View {
    @Binding var selection: DiscoverSegment
    let isSearching: Bool

    var body: some View {
        GlassFilterBar(
            selection: $selection,
            isSearching: isSearching,
            pickerLabel: L10n.Contacts.Contacts.Discovery.Segment.pickerLabel,
            title: { $0.localizedTitle }
        )
    }
}
