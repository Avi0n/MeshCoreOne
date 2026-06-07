import Testing
import Foundation
@testable import MC1
@testable import MC1Services

/// Locks the delete-sequencing invariants the LazyVStack refactor depends on: the restored
/// change-gate suppresses value-identical republishes (so a stale reload mid-delete cannot
/// re-diff the list), the optimistic hide self-heals only when the database confirms the
/// deletion, a failed delete restores the held row exactly once, the direct-message delete
/// surfaces a typed not-connected failure, and the radio-command timeout fires.
@Suite("ChatViewModel delete sequencing")
@MainActor
struct ChatViewModelDeleteSequencingTests {

    // MARK: - Fixtures

    private func makeContact(
        id: UUID = UUID(),
        radioID: UUID = UUID(),
        name: String,
        isFavorite: Bool = false,
        lastMessageDate: Date? = Date()
    ) -> ContactDTO {
        ContactDTO(
            id: id,
            radioID: radioID,
            publicKey: Data(repeating: UInt8(truncatingIfNeeded: id.hashValue), count: 32),
            name: name,
            typeRawValue: ContactType.chat.rawValue,
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
            lastMessageDate: lastMessageDate,
            unreadCount: 0
        )
    }

    private func makeRoom(
        id: UUID = UUID(),
        radioID: UUID = UUID(),
        name: String = "Room",
        lastMessageDate: Date? = Date()
    ) -> RemoteNodeSessionDTO {
        RemoteNodeSessionDTO(
            id: id,
            radioID: radioID,
            publicKey: Data(repeating: UInt8(truncatingIfNeeded: id.hashValue), count: 32),
            name: name,
            role: .roomServer,
            isConnected: true,
            isFavorite: false,
            lastMessageDate: lastMessageDate
        )
    }

    // MARK: - Change-gate ↔ mask coupling

    /// A stale reload that still reads a just-deleted row recomputes to a value-identical
    /// (hidden) snapshot, so the restored gate suppresses the republish and does not bump the
    /// generation. This is the regression lock for the gate and the mask↔gate coupling.
    @Test func maskedStaleReloadProducesNoGenerationBump() {
        let room = makeRoom(name: "Room")
        let viewModel = ChatViewModel()
        viewModel.roomSessions = [room]
        viewModel.recomputeSnapshot()

        viewModel.removeConversation(.room(room))
        let generationAfterRemove = viewModel.snapshotGeneration
        #expect(viewModel.allConversations.allSatisfy { $0.id != room.id })

        // Stale reload: the buffer still holds the room, but the mask keeps it hidden, so the
        // recompute yields the same snapshot and the gate suppresses the republish.
        viewModel.roomSessions = [room]
        viewModel.reconcilePendingRemovals()
        viewModel.recomputeSnapshot()

        #expect(viewModel.snapshotGeneration == generationAfterRemove)
        #expect(viewModel.pendingRemovalIDs.contains(room.id))
    }

    /// The confirming reload (row absent) self-heals the mask, and the recompute equals the
    /// already-hidden snapshot, so the whole delete is a single net membership change.
    @Test func confirmingReloadSelfHealsWithoutAnExtraBump() {
        let room = makeRoom(name: "Room")
        let viewModel = ChatViewModel()
        viewModel.roomSessions = [room]
        viewModel.recomputeSnapshot()

        viewModel.removeConversation(.room(room))
        let generationAfterRemove = viewModel.snapshotGeneration

        viewModel.roomSessions = []
        viewModel.reconcilePendingRemovals()
        viewModel.recomputeSnapshot()

        #expect(viewModel.pendingRemovalIDs.isEmpty)
        #expect(viewModel.snapshotGeneration == generationAfterRemove)
    }

    // MARK: - Restore on failure

    /// A failed delete restores the held DTO exactly once: the row reappears, the mask clears,
    /// one republish fires, and a redundant restore neither duplicates the row nor republishes.
    @Test func restoreReadmitsHeldRowExactlyOnce() {
        let room = makeRoom(name: "Room")
        let viewModel = ChatViewModel()
        viewModel.roomSessions = [room]
        viewModel.recomputeSnapshot()

        viewModel.removeConversation(.room(room))
        let generationAfterRemove = viewModel.snapshotGeneration

        viewModel.restoreConversation(.room(room))
        #expect(viewModel.allConversations.contains { $0.id == room.id })
        #expect(viewModel.pendingRemovalIDs.isEmpty)
        #expect(viewModel.snapshotGeneration == generationAfterRemove + 1)

        viewModel.restoreConversation(.room(room))
        #expect(viewModel.roomSessions.filter { $0.id == room.id }.count == 1)
        #expect(viewModel.snapshotGeneration == generationAfterRemove + 1)
    }

    // MARK: - Typed failure edge

    /// The direct-message delete throws `.notConnected` instead of returning silently, so the
    /// action can roll back the optimistic hide and surface an error.
    @Test func directDeleteThrowsWhenNotConnected() async {
        let viewModel = ChatViewModel()   // no appState, no dataStore → not connected
        let contact = makeContact(name: "Alice")

        await #expect(throws: ConversationActionError.self) {
            try await viewModel.deleteDirectConversation(for: contact)
        }
    }

    // MARK: - Pending guard

    @Test func isDeletePendingReflectsBothSets() {
        let viewModel = ChatViewModel()
        let id = UUID()
        #expect(!viewModel.isDeletePending(id))

        viewModel.pendingRemovalIDs.insert(id)
        #expect(viewModel.isDeletePending(id))

        viewModel.pendingRemovalIDs.remove(id)
        viewModel.deletingIDs.insert(id)
        #expect(viewModel.isDeletePending(id))
    }

    // MARK: - Bounded radio command

    /// The shared `withTimeout` used to bound channel/room delete commands throws when the
    /// operation outlives the deadline, so a silent radio surfaces an error instead of hanging.
    @Test func boundedCommandTimesOut() async {
        await #expect(throws: TimeoutError.self) {
            try await withTimeout(.milliseconds(20), operationName: "test") {
                try await Task.sleep(for: .seconds(10))
            }
        }
    }
}
