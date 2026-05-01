import SwiftUI
import MC1Services
import CoreLocation

struct ContactsCompactList: View {
    @Environment(\.appState) private var appState

    let filteredContacts: [ContactDTO]
    let isSearching: Bool
    let viewModel: ContactsViewModel

    var body: some View {
        List {
            ForEach(Array(filteredContacts.enumerated()), id: \.element.id) { index, contact in
                NavigationLink(value: contact) {
                    ContactRowView(
                        contact: contact,
                        showTypeLabel: isSearching,
                        userLocation: appState.bestAvailableLocation,
                        index: index,
                        isTogglingFavorite: viewModel.togglingFavoriteID == contact.id
                    )
                }
                .contactSwipeActions(contact: contact, viewModel: viewModel)
            }
        }
        .listStyle(.plain)
    }
}
