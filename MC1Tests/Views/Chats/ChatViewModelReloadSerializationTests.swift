import Testing
import Foundation
@testable import MC1
@testable import MC1Services

/// Proves the funnel invariants the leave-room `UICollectionView` assertion reduces
/// to: a stale reload suspended mid-fetch never commits after a fresher one, an
/// optimistically removed row stays hidden across a stale reload and self-heals, and
/// the cache→snapshot port preserves the filter/partition/sort semantics verbatim.
@Suite("ChatViewModel reload serialization")
@MainActor
struct ChatViewModelReloadSerializationTests {

    // MARK: - Fixtures

    private func makeContact(
        id: UUID = UUID(),
        radioID: UUID,
        name: String,
        type: ContactType = .chat,
        isFavorite: Bool = false,
        isBlocked: Bool = false,
        lastMessageDate: Date? = Date()
    ) -> ContactDTO {
        ContactDTO(
            id: id,
            radioID: radioID,
            publicKey: Data(repeating: UInt8(truncatingIfNeeded: id.hashValue), count: 32),
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
            isBlocked: isBlocked,
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
        isFavorite: Bool = false,
        lastMessageDate: Date? = Date()
    ) -> RemoteNodeSessionDTO {
        RemoteNodeSessionDTO(
            id: id,
            radioID: radioID,
            publicKey: Data(repeating: UInt8(truncatingIfNeeded: id.hashValue), count: 32),
            name: name,
            role: .roomServer,
            isConnected: true,
            isFavorite: isFavorite,
            lastMessageDate: lastMessageDate
        )
    }

    private func makeChannel(
        id: UUID = UUID(),
        radioID: UUID = UUID(),
        index: UInt8 = 0,
        name: String,
        secret: Data = Data(),
        isFavorite: Bool = false,
        lastMessageDate: Date? = Date()
    ) -> ChannelDTO {
        ChannelDTO(
            id: id,
            radioID: radioID,
            index: index,
            name: name,
            secret: secret,
            isEnabled: true,
            lastMessageDate: lastMessageDate,
            unreadCount: 0,
            unreadMentionCount: 0,
            notificationLevel: .all,
            isFavorite: isFavorite
        )
    }

    private func makeDevice(radioID: UUID) -> DeviceDTO {
        DeviceDTO(
            id: UUID(),
            radioID: radioID,
            publicKey: Data(repeating: 0xBB, count: 32),
            nodeName: "TestNode",
            firmwareVersion: 1,
            firmwareVersionString: "1.12.0",
            manufacturerName: "Test",
            buildDate: "2025-01-01",
            maxContacts: 100,
            maxChannels: 8,
            frequency: 915_000,
            bandwidth: 250_000,
            spreadingFactor: 10,
            codingRate: 5,
            txPower: 20,
            maxTxPower: 20,
            latitude: 0,
            longitude: 0,
            blePin: 0,
            manualAddContacts: false,
            multiAcks: 2,
            telemetryModeBase: 2,
            telemetryModeLoc: 0,
            telemetryModeEnv: 0,
            advertLocationPolicy: 0,
            lastConnected: Date(),
            lastContactSync: 0,
            isActive: true,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }

    // MARK: - Serialization

    /// reload #1 suspends in the interleave hook; reload #2 commits first with one
    /// fewer conversation; reload #1 resumes and must hit `isCancelled`, not commit.
    @Test func staleReloadDoesNotCommit() async throws {
        let radioID = UUID()
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)

        var contacts: [ContactDTO] = []
        for index in 0..<12 {
            let contact = makeContact(radioID: radioID, name: "C\(index)")
            contacts.append(contact)
            try await store.saveContact(contact)
        }
        let last = contacts[11]

        let appState = AppState(modelContainer: try PersistenceStore.createContainer(inMemory: true))
        appState.connectionManager.setTestState(connectedDevice: makeDevice(radioID: radioID))

        let viewModel = ChatViewModel()
        viewModel.appState = appState
        viewModel.dataStore = store

        let arrived = AsyncGate()
        let gate = AsyncGate()
        viewModel.reloadInterleaveHook = {
            await arrived.open()   // signal reload #1 reached the hook
            await gate.wait()      // then suspend until the test opens the gate
        }

        let task1 = viewModel.requestConversationReload()
        await arrived.wait()       // reload #1 is now parked at the gate

        // Reconfigure for reload #2: clear the hook, drop C11, fire the fresher reload
        // (which cancels task1's token).
        viewModel.reloadInterleaveHook = nil
        try await store.deleteContact(id: last.id)
        let task2 = viewModel.requestConversationReload()
        await task2?.value         // reload #2 commits the 11-row snapshot

        await gate.open()          // resume reload #1 → must early-return on isCancelled
        await task1?.value

        #expect(viewModel.conversationSnapshot.others.count == 11)
        #expect(viewModel.allConversations.allSatisfy { $0.id != last.id })
    }

    // MARK: - Optimistic delete

    @Test func deletedRowDoesNotReappearOnStaleReload() {
        let room = makeRoom(name: "Room")
        let viewModel = ChatViewModel()
        viewModel.roomSessions = [room]
        viewModel.recomputeSnapshot()

        viewModel.removeConversation(.room(room))
        #expect(viewModel.allConversations.allSatisfy { $0.id != room.id })
        #expect(viewModel.pendingRemovalIDs.contains(room.id))

        // A stale reload still returns the room (pre-commit reconcile + recompute):
        viewModel.roomSessions = [room]
        viewModel.reconcilePendingRemovals()
        viewModel.recomputeSnapshot()
        #expect(viewModel.allConversations.allSatisfy { $0.id != room.id })  // still hidden
        #expect(viewModel.pendingRemovalIDs.contains(room.id))               // still pending

        // The confirming reload no longer returns the room → self-heal:
        viewModel.roomSessions = []
        viewModel.reconcilePendingRemovals()
        viewModel.recomputeSnapshot()
        #expect(viewModel.pendingRemovalIDs.isEmpty)
    }

    // MARK: - Filters / partition / sort

    @Test func filtersPreservedAndPartitioned() {
        let radioID = UUID()
        let older = Date(timeIntervalSince1970: 1000)
        let newer = Date(timeIntervalSince1970: 2000)

        let favNewer = makeContact(radioID: radioID, name: "FavNewer", isFavorite: true, lastMessageDate: newer)
        let favOlder = makeContact(radioID: radioID, name: "FavOlder", isFavorite: true, lastMessageDate: older)
        let plain = makeContact(radioID: radioID, name: "Plain", lastMessageDate: newer)
        let repeaterContact = makeContact(radioID: radioID, name: "Repeater", type: .repeater)
        let blockedContact = makeContact(radioID: radioID, name: "Blocked", isBlocked: true)

        let viewModel = ChatViewModel()
        viewModel.conversations = [favOlder, plain, repeaterContact, blockedContact, favNewer]
        viewModel.channels = [makeChannel(radioID: radioID, name: "")]   // empty-name, secretless → excluded
        viewModel.recomputeSnapshot()

        // Repeater, blocked, and empty-name secretless channel excluded.
        let names = viewModel.allConversations.map(\.displayName)
        #expect(!names.contains("Repeater"))
        #expect(!names.contains("Blocked"))
        #expect(viewModel.allConversations.count == 3)

        // Favorites partitioned first, each partition sorted by lastMessageDate descending.
        #expect(viewModel.favoriteConversations.map(\.displayName) == ["FavNewer", "FavOlder"])
        #expect(viewModel.nonFavoriteConversations.map(\.displayName) == ["Plain"])
    }
}

/// Minimal continuation-backed gate. Used both as the reload-arrival signal and as
/// the suspend point that holds reload #1 until the fresher reload has committed.
private actor AsyncGate {
    private var waiter: CheckedContinuation<Void, Never>?
    private var opened = false

    func wait() async {
        if opened { return }
        await withCheckedContinuation { waiter = $0 }
    }

    func open() {
        opened = true
        waiter?.resume()
        waiter = nil
    }
}
