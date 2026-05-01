import SwiftUI
import MC1Services
import CoreLocation

struct ContactsSplitList: View {
    @Environment(\.appState) private var appState

    let filteredContacts: [ContactDTO]
    let isSearching: Bool
    let viewModel: ContactsViewModel
    @Binding var selectedContact: ContactDTO?

    var body: some View {
        List(selection: $selectedContact) {
            ForEach(Array(filteredContacts.enumerated()), id: \.element.id) { index, contact in
                ContactRowView(
                    contact: contact,
                    showTypeLabel: isSearching,
                    userLocation: appState.bestAvailableLocation,
                    index: index,
                    isTogglingFavorite: viewModel.togglingFavoriteID == contact.id
                )
                .contactSwipeActions(contact: contact, viewModel: viewModel)
                .tag(contact)
            }
        }
        .listStyle(.plain)
    }
}
