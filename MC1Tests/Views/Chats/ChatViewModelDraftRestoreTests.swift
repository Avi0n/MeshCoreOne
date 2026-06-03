import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@MainActor
struct ChatViewModelDraftRestoreTests {

    private func makeStore() -> DraftStore {
        DraftStore(defaults: UserDefaults(suiteName: "test.\(UUID().uuidString)")!)
    }

    private func dmID() -> ChatConversationID {
        .dm(radioID: UUID(), contactID: UUID())
    }

    @Test("loadDraft restores the disk draft when the composer is empty")
    func loadDraftRestoresWhenEmpty() {
        let viewModel = ChatViewModel()
        let store = makeStore()
        let id = dmID()
        store.setDraft("saved", for: id)

        viewModel.loadDraft(from: store, id: id)
        #expect(viewModel.composingText == "saved")
    }

    @Test("loadDraft leaves an already-populated composer untouched")
    func loadDraftKeepsExistingText() {
        let viewModel = ChatViewModel()
        let store = makeStore()
        let id = dmID()
        store.setDraft("disk", for: id)
        viewModel.composingText = "in-progress"

        viewModel.loadDraft(from: store, id: id)
        #expect(viewModel.composingText == "in-progress")
    }

    @Test("restoreComposerDraft keeps disk draft from clobbering an already-populated composer")
    func restoreComposerDraftPreservesExistingText() {
        let viewModel = ChatViewModel()
        let store = makeStore()
        let id = dmID()
        store.setDraft("disk", for: id)
        // Simulates a quick-reply draft having already populated the field; the disk restore
        // must not override it.
        viewModel.composingText = "quick-reply"

        viewModel.restoreComposerDraft(from: store, id: id)
        #expect(viewModel.composingText == "quick-reply")
    }

    @Test("restoreComposerDraft applies the disk draft when the composer is empty")
    func restoreComposerDraftAppliesDiskWhenEmpty() {
        let viewModel = ChatViewModel()
        let store = makeStore()
        let id = dmID()
        store.setDraft("saved", for: id)

        viewModel.restoreComposerDraft(from: store, id: id)
        #expect(viewModel.composingText == "saved")
    }
}
