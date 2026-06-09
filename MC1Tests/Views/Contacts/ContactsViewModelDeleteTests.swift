import Testing
import Foundation
@testable import MC1
@testable import MC1Services

/// Locks the node-list delete invariants the LazyVStack refactor depends on: a confirmed-but-
/// unreloaded delete is masked out of the filtered list (so a reload racing the database delete
/// can't resurrect the row), the pending guard reflects both the masking and the in-flight sets,
/// a delete with no radio surfaces a typed error instead of silently mutating the list, and the
/// shared timeout bounds the radio command.
@Suite("ContactsViewModel delete sequencing")
@MainActor
struct ContactsViewModelDeleteTests {

    private func makeContact(
        id: UUID = UUID(),
        radioID: UUID = UUID(),
        name: String,
        type: ContactType = .chat,
        isFavorite: Bool = false
    ) -> ContactDTO {
        ContactDTO(
            id: id,
            radioID: radioID,
            publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
            name: name,
            typeRawValue: type.rawValue,
            flags: 0,
            outPathLength: 0,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: isFavorite,
            lastMessageDate: nil,
            unreadCount: 0
        )
    }

    // MARK: - Mask filtering

    /// A masked row is excluded from the filtered list regardless of the active segment, so a
    /// confirmed delete stays hidden even while the database catches up.
    @Test func filteredContactsExcludesMaskedRow() {
        let viewModel = ContactsViewModel()
        let deviceID = UUID()
        let doomed = makeContact(radioID: deviceID, name: "Doomed")
        let kept = makeContact(radioID: deviceID, name: "Kept")
        viewModel.contacts = [doomed, kept]

        viewModel.pendingRemovalIDs.insert(doomed.id)

        let result = viewModel.filteredContacts(
            searchText: "",
            segment: .contacts,
            sortOrder: .name,
            userLocation: nil
        )

        #expect(result.map(\.id) == [kept.id])
    }

    /// The mask also hides a row that would otherwise match the active search text.
    @Test func filteredContactsMaskAppliesToSearch() {
        let viewModel = ContactsViewModel()
        let deviceID = UUID()
        let doomed = makeContact(radioID: deviceID, name: "Alice")
        viewModel.contacts = [doomed]

        viewModel.pendingRemovalIDs.insert(doomed.id)

        let result = viewModel.filteredContacts(
            searchText: "Ali",
            segment: .contacts,
            sortOrder: .name,
            userLocation: nil
        )

        #expect(result.isEmpty)
    }

    // MARK: - Pending guard

    @Test func isDeletePendingReflectsBothSets() {
        let viewModel = ContactsViewModel()
        let id = UUID()
        #expect(!viewModel.isDeletePending(id))

        viewModel.pendingRemovalIDs.insert(id)
        #expect(viewModel.isDeletePending(id))

        viewModel.pendingRemovalIDs.remove(id)
        viewModel.deletingIDs.insert(id)
        #expect(viewModel.isDeletePending(id))
    }

    @Test func maskAndDeletingSetsStartEmpty() {
        let viewModel = ContactsViewModel()
        #expect(viewModel.pendingRemovalIDs.isEmpty)
        #expect(viewModel.deletingIDs.isEmpty)
    }

    // MARK: - Not-connected guard

    /// Deleting with no contact service (offline) surfaces an error and leaves the list, mask,
    /// and spinner set untouched rather than optimistically hiding the row.
    @Test func deleteContactWithoutServiceSurfacesErrorAndKeepsRow() async {
        let viewModel = ContactsViewModel()
        let contact = makeContact(name: "Alice")
        viewModel.contacts = [contact]

        await viewModel.deleteContact(contact)

        #expect(viewModel.errorMessage != nil)
        #expect(viewModel.contacts.map(\.id) == [contact.id])
        #expect(viewModel.pendingRemovalIDs.isEmpty)
        #expect(viewModel.deletingIDs.isEmpty)
    }

    // MARK: - Bounded radio command

    /// The shared `withTimeout` that bounds the remove-contact command throws when the operation
    /// outlives the deadline, so a silent radio surfaces an error instead of spinning forever.
    @Test func boundedCommandTimesOut() async {
        await #expect(throws: TimeoutError.self) {
            try await withTimeout(.milliseconds(20), operationName: "removeContact") {
                try await Task.sleep(for: .seconds(10))
            }
        }
    }
}
