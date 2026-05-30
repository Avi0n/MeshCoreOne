import SwiftUI
import MC1Services

struct ContactsCompactList: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    @Binding var selectedSegment: NodeSegment
    let isSearching: Bool
    let searchText: String
    let filteredContacts: [ContactDTO]
    let viewModel: ContactsViewModel

    var body: some View {
        List {
            PinnedFilterHeader {
                NodeSegmentPicker(selection: $selectedSegment, isSearching: isSearching)
            }

            if filteredContacts.isEmpty {
                emptyStateRow
            } else {
                ForEach(filteredContacts, id: \.id) { contact in
                    NavigationLink(value: contact) {
                        ContactRowView(
                            contact: contact,
                            showTypeLabel: isSearching,
                            userLocation: appState.bestAvailableLocation,
                            isTogglingFavorite: viewModel.togglingFavoriteID == contact.id
                        )
                    }
                    .contactSwipeActions(contact: contact, viewModel: viewModel)
                }
                .themedPlainRowBackground(theme)
            }
        }
        .listStyle(.plain)
        .themedCanvas(theme)
    }

    @ViewBuilder
    private var emptyStateRow: some View {
        Section {
            if isSearching {
                ContactsSearchEmptyView(searchText: searchText)
            } else {
                ContactsEmptyView(selectedSegment: selectedSegment)
            }
        }
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
    }
}
