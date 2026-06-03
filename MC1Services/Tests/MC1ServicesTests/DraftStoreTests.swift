import Testing
import Foundation
@testable import MC1Services

/// Covers `DraftStore` persistence and the `draftToApply` restore guard, plus the
/// pinned `ChatConversationID.draftStorageKey` encoding the store keys on.
@Suite("DraftStore Tests")
@MainActor
struct DraftStoreTests {

    /// A per-test isolated `UserDefaults` so suites never share draft state.
    private func makeDefaults() -> UserDefaults {
        let suiteName = "test.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    private let radioID = UUID()
    private let contactID = UUID()

    private func dm(_ radio: UUID, _ contact: UUID) -> ChatConversationID {
        .dm(radioID: radio, contactID: contact)
    }

    private func channel(_ radio: UUID, _ index: UInt8) -> ChatConversationID {
        .channel(radioID: radio, channelIndex: index)
    }

    // MARK: - Round-trip and persistence

    @Test("setDraft / draft / clearDraft round-trip")
    func roundTrip() {
        let store = DraftStore(defaults: makeDefaults())
        let id = dm(radioID, contactID)

        #expect(store.draft(for: id) == nil)

        store.setDraft("hello", for: id)
        #expect(store.draft(for: id) == "hello")

        store.clearDraft(for: id)
        #expect(store.draft(for: id) == nil)
    }

    @Test("draft survives a new DraftStore instance on the same suite (restart)")
    func survivesRestart() {
        let defaults = makeDefaults()
        let id = dm(radioID, contactID)

        let first = DraftStore(defaults: defaults)
        first.setDraft("persisted", for: id)

        let second = DraftStore(defaults: defaults)
        #expect(second.draft(for: id) == "persisted")
    }

    // MARK: - Emptiness handling

    @Test("whitespace / newline-only input removes the entry")
    func whitespaceRemovesEntry() {
        let store = DraftStore(defaults: makeDefaults())
        let id = dm(radioID, contactID)

        store.setDraft("real", for: id)
        #expect(store.draft(for: id) == "real")

        store.setDraft("   \n\t ", for: id)
        #expect(store.draft(for: id) == nil)
    }

    @Test("non-empty draft with a trailing newline is stored verbatim")
    func trailingNewlineStoredVerbatim() {
        let store = DraftStore(defaults: makeDefaults())
        let id = dm(radioID, contactID)

        store.setDraft("hi\n", for: id)
        #expect(store.draft(for: id) == "hi\n")
    }

    // MARK: - draftToApply restore guard

    @Test("draftToApply returns the saved draft when the field is empty")
    func draftToApplyReturnsWhenEmpty() {
        let store = DraftStore(defaults: makeDefaults())
        let id = dm(radioID, contactID)
        store.setDraft("saved", for: id)

        #expect(store.draftToApply(over: "", for: id) == "saved")
    }

    @Test("draftToApply returns nil when the field already has text")
    func draftToApplyGuardsNonEmpty() {
        let store = DraftStore(defaults: makeDefaults())
        let id = dm(radioID, contactID)
        store.setDraft("saved", for: id)

        #expect(store.draftToApply(over: "typing", for: id) == nil)
    }

    @Test("draftToApply returns nil when no draft exists")
    func draftToApplyNilWhenAbsent() {
        let store = DraftStore(defaults: makeDefaults())
        let id = dm(radioID, contactID)

        #expect(store.draftToApply(over: "", for: id) == nil)
    }

    // MARK: - Key isolation

    @Test("dm and channel drafts at matching identifiers do not collide")
    func dmVsChannelIsolation() {
        let store = DraftStore(defaults: makeDefaults())
        let dmID = dm(radioID, contactID)
        let channelID = channel(radioID, 3)

        store.setDraft("dm-text", for: dmID)
        store.setDraft("channel-text", for: channelID)

        #expect(store.draft(for: dmID) == "dm-text")
        #expect(store.draft(for: channelID) == "channel-text")
    }

    @Test("same channel index on different radios is isolated")
    func sameIndexDifferentRadioIsolation() {
        let store = DraftStore(defaults: makeDefaults())
        let radioA = UUID()
        let radioB = UUID()

        store.setDraft("a", for: channel(radioA, 1))
        store.setDraft("b", for: channel(radioB, 1))

        #expect(store.draft(for: channel(radioA, 1)) == "a")
        #expect(store.draft(for: channel(radioB, 1)) == "b")
    }

    // MARK: - Cleared state persistence

    @Test("clearDraft persists the deletion across a fresh instance (restart)")
    func clearedDraftDoesNotSurviveRestart() {
        let defaults = makeDefaults()
        let id = dm(radioID, contactID)

        let first = DraftStore(defaults: defaults)
        first.setDraft("persisted", for: id)
        first.clearDraft(for: id)

        let second = DraftStore(defaults: defaults)
        #expect(second.draft(for: id) == nil)
    }

    @Test("whitespace-clear persists the deletion across a fresh instance (restart)")
    func whitespaceClearedDraftDoesNotSurviveRestart() {
        let defaults = makeDefaults()
        let id = dm(radioID, contactID)

        let first = DraftStore(defaults: defaults)
        first.setDraft("persisted", for: id)
        first.setDraft("   \n ", for: id)

        let second = DraftStore(defaults: defaults)
        #expect(second.draft(for: id) == nil)
    }

    @Test("clearDraft on a never-set id is a no-op")
    func clearDraftOnUnsetIdIsNoOp() {
        let defaults = makeDefaults()
        let id = channel(radioID, 4)

        let store = DraftStore(defaults: defaults)
        store.clearDraft(for: id)
        #expect(store.draft(for: id) == nil)

        let reloaded = DraftStore(defaults: defaults)
        #expect(reloaded.draft(for: id) == nil)
    }

    // MARK: - Batch channel-draft clearing

    @Test("clearChannelDrafts(radioID:indices:) clears the given slots and persists")
    func clearChannelDraftsByRadioClearsAndPersists() {
        let defaults = makeDefaults()
        let store = DraftStore(defaults: defaults)

        store.setDraft("one", for: channel(radioID, 1))
        store.setDraft("two", for: channel(radioID, 2))
        store.setDraft("keep", for: channel(radioID, 3))

        store.clearChannelDrafts(radioID: radioID, indices: [1, 2])

        #expect(store.draft(for: channel(radioID, 1)) == nil)
        #expect(store.draft(for: channel(radioID, 2)) == nil)
        #expect(store.draft(for: channel(radioID, 3)) == "keep")

        let reloaded = DraftStore(defaults: defaults)
        #expect(reloaded.draft(for: channel(radioID, 1)) == nil)
        #expect(reloaded.draft(for: channel(radioID, 3)) == "keep")
    }

    @Test("clearChannelDrafts(slotsByRadio:) clears slots across radios, leaving dm drafts intact")
    func clearChannelDraftsBySlotsByRadioIsScoped() {
        let store = DraftStore(defaults: makeDefaults())
        let radioA = UUID()
        let radioB = UUID()

        store.setDraft("a1", for: channel(radioA, 1))
        store.setDraft("b1", for: channel(radioB, 1))
        store.setDraft("dm", for: dm(radioA, contactID))

        store.clearChannelDrafts(slotsByRadio: [radioA: [1], radioB: [1]])

        #expect(store.draft(for: channel(radioA, 1)) == nil)
        #expect(store.draft(for: channel(radioB, 1)) == nil)
        #expect(store.draft(for: dm(radioA, contactID)) == "dm")
    }

    // MARK: - Pinned key encoding

    @Test("draftStorageKey encodes dm and channel keys in the pinned format")
    func pinnedKeyStrings() {
        let radio = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        let contact = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

        #expect(
            dm(radio, contact).draftStorageKey
                == "11111111-1111-1111-1111-111111111111|dm|22222222-2222-2222-2222-222222222222"
        )
        #expect(
            channel(radio, 5).draftStorageKey
                == "11111111-1111-1111-1111-111111111111|ch|5"
        )
    }
}
