import CoreLocation
import SwiftUI
import MC1Services

/// Segment for the nodes picker
enum NodeSegment: String, CaseIterable {
    case favorites
    case contacts
    case repeaters
    case rooms

    var localizedTitle: String {
        switch self {
        case .favorites: L10n.Contacts.Contacts.Segment.favorites
        case .contacts: L10n.Contacts.Contacts.Segment.contacts
        case .repeaters: L10n.Contacts.Contacts.Segment.repeaters
        case .rooms: L10n.Contacts.Contacts.Segment.rooms
        }
    }
}

/// Sort order for nodes list
enum NodeSortOrder: String, CaseIterable {
    case lastHeard
    case name
    case distance

    var localizedTitle: String {
        switch self {
        case .lastHeard: L10n.Contacts.Contacts.Sort.lastHeard
        case .name: L10n.Contacts.Contacts.Sort.name
        case .distance: L10n.Contacts.Contacts.Sort.distance
        }
    }
}

/// ViewModel for contact management
@Observable
@MainActor
final class ContactsViewModel {

    // MARK: - Properties

    /// All contacts
    var contacts: [ContactDTO] = []

    /// Loading state
    var isLoading = false

    /// Whether data has been loaded at least once (prevents empty state flash)
    var hasLoadedOnce = false

    /// Syncing state
    var isSyncing = false

    /// Sync progress (current, total)
    var syncProgress: (Int, Int)?

    /// Error message if any
    var errorMessage: String?

    /// User's current location for distance sorting (optional)
    var userLocation: CLLocation?

    /// Contact ID currently having its favorite status toggled (for loading UI)
    var togglingFavoriteID: UUID?

    /// Mask of rows hidden after a confirmed delete, held until a reload sees the row gone so a
    /// racing reload can't resurrect it. Observed because `filteredContacts` reads it during body
    /// evaluation. Distinct from `deletingIDs`, the in-flight presentation set.
    var pendingRemovalIDs: Set<UUID> = []

    /// Rows with a delete command in flight, surfaced as a spinner. Distinct from
    /// `pendingRemovalIDs`, the post-confirmation mask.
    var deletingIDs: Set<UUID> = []

    /// True while a delete for this row is either confirmed-but-unreloaded or in flight.
    func isDeletePending(_ id: UUID) -> Bool {
        pendingRemovalIDs.contains(id) || deletingIDs.contains(id)
    }

    // MARK: - Dependencies

    private var dataStore: DataStore?
    private var contactService: ContactService?
    private var advertisementService: AdvertisementService?

    // MARK: - Initialization

    init() {}

    /// Configure with services from AppState
    func configure(appState: AppState) {
        self.dataStore = appState.offlineDataStore
        self.contactService = appState.services?.contactService
        self.advertisementService = appState.services?.advertisementService
    }

    /// Configure with services (for testing)
    func configure(
        dataStore: DataStore,
        contactService: ContactService,
        advertisementService: AdvertisementService? = nil
    ) {
        self.dataStore = dataStore
        self.contactService = contactService
        self.advertisementService = advertisementService
    }

    // MARK: - Load Contacts

    /// Load contacts from local database
    func loadContacts(radioID: UUID) async {
        guard let dataStore else { return }

        isLoading = true
        errorMessage = nil

        do {
            contacts = try await dataStore.fetchContacts(radioID: radioID)
            // Self-heal the mask: once a deleted row is gone from the fetch, stop masking it.
            pendingRemovalIDs.formIntersection(Set(contacts.map(\.id)))
        } catch {
            errorMessage = error.localizedDescription
        }

        hasLoadedOnce = true
        isLoading = false
    }

    // MARK: - Sync Contacts

    /// Sync contacts from device
    func syncContacts(radioID: UUID) async {
        guard let contactService else { return }

        isSyncing = true
        syncProgress = nil
        errorMessage = nil

        if let advertisementService {
            await advertisementService.setSyncingContacts(true)
        }
        defer {
            if let advertisementService {
                Task { await advertisementService.setSyncingContacts(false) }
            }
        }

        // Set up progress handler
        await contactService.setSyncProgressHandler { [weak self] current, total in
            Task { @MainActor in
                self?.syncProgress = (current, total)
            }
        }

        do {
            _ = try await contactService.syncContacts(radioID: radioID)

            // Reload from database
            await loadContacts(radioID: radioID)

            // Clear sync progress
            syncProgress = nil
        } catch {
            errorMessage = error.localizedDescription
        }

        isSyncing = false
    }

    // MARK: - Contact Actions

    /// Toggle favorite status on device and update local state
    func toggleFavorite(contact: ContactDTO) async {
        guard let contactService else { return }

        togglingFavoriteID = contact.id
        defer { togglingFavoriteID = nil }

        do {
            try await contactService.setContactFavorite(contact.id, isFavorite: !contact.isFavorite)

            // Reload to get updated state
            if contacts.contains(where: { $0.id == contact.id }) {
                await loadContacts(radioID: contact.radioID)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Toggle blocked status
    func toggleBlocked(contact: ContactDTO) async {
        guard let contactService else { return }

        do {
            try await contactService.updateContactPreferences(
                contactID: contact.id,
                isBlocked: !contact.isBlocked
            )

            // Update local list
            await loadContacts(radioID: contact.radioID)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Update nickname
    func updateNickname(contact: ContactDTO, nickname: String?) async {
        guard let contactService else { return }

        do {
            try await contactService.updateContactPreferences(
                contactID: contact.id,
                nickname: nickname?.isEmpty == true ? nil : nickname
            )

            // Update local list
            await loadContacts(radioID: contact.radioID)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Delete a contact. Removing a node is a real radio command, so the row stays in place with a
    /// spinner until the radio acks, then is hidden once; a failure or timeout leaves it untouched
    /// with an error rather than bouncing it out and back.
    func deleteContact(_ contact: ContactDTO) async {
        guard let contactService else {
            errorMessage = L10n.Contacts.Contacts.ViewModel.connectToDelete
            return
        }
        guard !isDeletePending(contact.id) else { return }

        deletingIDs.insert(contact.id)
        defer { deletingIDs.remove(contact.id) }

        do {
            try await withTimeout(RadioCommandTimeout.delete, operationName: "removeContact") {
                try await contactService.removeContact(
                    radioID: contact.radioID,
                    publicKey: contact.publicKey
                )
            }
            hideDeletedContact(contact)
        } catch ContactServiceError.contactNotFound {
            // The radio no longer knows this contact (e.g. a prior attempt's command landed but its
            // local delete was interrupted). Clear the orphaned local row so it stops reappearing.
            do {
                try await contactService.removeLocalContact(contactID: contact.id, publicKey: contact.publicKey)
                hideDeletedContact(contact)
            } catch {
                errorMessage = error.localizedDescription
            }
        } catch is TimeoutError {
            errorMessage = L10n.Contacts.Contacts.ViewModel.removeTimedOut
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Masks and removes a row whose deletion the radio confirmed, in one animation.
    /// The mask insert is observed (read live by `filteredContacts`), so it must land inside
    /// the same transaction as the array removal or it hides the row unanimated first.
    private func hideDeletedContact(_ contact: ContactDTO) {
        withAnimation(.snappy) {
            pendingRemovalIDs.insert(contact.id)
            contacts.removeAll { $0.id == contact.id }
        }
    }

    // MARK: - Filtering

    /// Returns contacts filtered by segment and sorted
    func filteredContacts(
        searchText: String,
        segment: NodeSegment,
        sortOrder: NodeSortOrder,
        userLocation: CLLocation?
    ) -> [ContactDTO] {
        var result = contacts.filter { !pendingRemovalIDs.contains($0.id) }

        // If searching, show all types (ignore segment)
        if searchText.isEmpty {
            // Filter by segment
            switch segment {
            case .favorites:
                result = result.filter(\.isFavorite)
            case .contacts:
                result = result.filter { $0.type == .chat }
            case .repeaters:
                result = result.filter { $0.type == .repeater }
            case .rooms:
                result = result.filter { $0.type == .room }
            }
        } else {
            // Filter by search text only
            result = result.filter { contact in
                contact.displayName.localizedStandardContains(searchText)
                    || contact.publicKey.hexString().hasPrefix(searchText.uppercased())
            }
        }

        // Sort
        result = sorted(result, by: sortOrder, userLocation: userLocation)

        return result
    }

    /// Sort contacts by the given order
    private func sorted(
        _ contacts: [ContactDTO],
        by order: NodeSortOrder,
        userLocation: CLLocation?
    ) -> [ContactDTO] {
        switch order {
        case .lastHeard:
            return contacts.sorted { $0.lastModified > $1.lastModified }
        case .name:
            return contacts.sorted {
                $0.displayName.localizedCompare($1.displayName) == .orderedAscending
            }
        case .distance:
            guard let userLocation else {
                // No user location, fall back to name sort
                return sorted(contacts, by: .name, userLocation: nil)
            }
            return contacts.sorted { lhs, rhs in
                let lhsHasLocation = lhs.hasLocation
                let rhsHasLocation = rhs.hasLocation

                // Nodes without location sort to bottom
                if lhsHasLocation != rhsHasLocation {
                    return lhsHasLocation
                }

                guard lhsHasLocation && rhsHasLocation else {
                    // Both have no location, sort by name
                    return lhs.displayName.localizedCompare(rhs.displayName) == .orderedAscending
                }

                let lhsLocation = CLLocation(latitude: lhs.latitude, longitude: lhs.longitude)
                let rhsLocation = CLLocation(latitude: rhs.latitude, longitude: rhs.longitude)

                return lhsLocation.distance(from: userLocation) < rhsLocation.distance(from: userLocation)
            }
        }
    }

}
