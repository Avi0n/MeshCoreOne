@testable import MC1
import MC1Services
import Testing
import UIKit

@Suite("Retained contact detail state")
@MainActor
struct ContactDetailStateTests {
  @Test
  func `payload refresh preserves the nickname draft and rejects a different contact`() {
    let contact = refreshed(makeContact(), nickname: "Stored nickname")
    let state = ContactDetailState(contact: contact)
    defer { state.end() }
    state.nickname = "Unsaved draft"

    state.updateContact(refreshed(contact, nickname: "Externally updated nickname"))
    #expect(state.currentContact.nickname == "Externally updated nickname")
    #expect(state.nickname == "Unsaved draft")

    state.updateContact(makeContact())
    #expect(state.currentContact.id == contact.id)
    #expect(state.currentContact.nickname == "Externally updated nickname")
    #expect(state.nickname == "Unsaved draft")
  }

  @Test
  func `transient renderer disappearance restores a mounted owner and stale disappearance cannot clear a new layout`() {
    let state = ContactDetailState(contact: makeContact())
    let current = UUID()
    let transient = UUID()
    state.prepareRenderer(revision: 1)
    state.mountRenderer(current, revision: 1)
    state.mountRenderer(transient, revision: 1)
    state.unmountRenderer(transient, revision: 1)
    #expect(state.rendererID == current)

    state.prepareRenderer(revision: 2)
    let replacement = UUID()
    state.mountRenderer(replacement, revision: 2)
    state.mountRenderer(current, revision: 1)
    state.unmountRenderer(current, revision: 1)
    #expect(state.rendererID == replacement)
    state.unmountRenderer(replacement, revision: 2)
    #expect(state.rendererID == nil)
    state.end()
  }

  @Test
  func `same activity key and renderer changes retain discovery while logical exit cancels it`() {
    let appState = AppState()
    let state = ContactDetailState(contact: makeContact())
    state.configure(appState: appState, onClose: {})
    state.setActive(true, appState: appState)
    state.pathViewModel.isDiscovering = true
    state.nickname = "Draft"
    state.mountRenderer(UUID())
    state.invalidateRenderer()
    state.mountRenderer(UUID())
    state.setActive(true, appState: appState)
    #expect(state.pathViewModel.isDiscovering)
    #expect(state.nickname == "Draft")
    state.setActive(false, appState: appState)
    #expect(!state.pathViewModel.isDiscovering)
    #expect(state.nickname == "Draft")
    state.end()
  }

  @Test
  func `activity pause preserves mounted renderer ownership for a cached return`() {
    let appState = AppState()
    let state = ContactDetailState(contact: makeContact())
    state.configure(appState: appState, onClose: {})
    state.setActive(true, appState: appState)
    let current = UUID()
    let transient = UUID()
    state.mountRenderer(current)
    state.mountRenderer(transient)
    state.pathViewModel.isDiscovering = true

    state.setActive(false, appState: appState)
    #expect(!state.pathViewModel.isDiscovering)
    #expect(state.rendererID == transient)
    state.unmountRenderer(transient)
    #expect(state.rendererID == current)
    state.setActive(true, appState: appState)
    #expect(state.rendererID == current)
    state.unmountRenderer(current)
    #expect(state.rendererID == nil)
    state.end()
  }

  @Test
  func `ended contact rejects delayed crop authentication close and payload effects`() {
    let appState = AppState()
    let contact = makeContact()
    let state = ContactDetailState(contact: contact)
    var closes = 0
    state.configure(appState: appState, onClose: { closes += 1 })
    let context = state.operationContext
    state.pendingCropImage = UIImage()
    state.activeSheet = .nodeTelemetry(contact)
    state.showRoomJoinSheet = true
    state.pathViewModel.isDiscovering = true
    state.end()
    state.end()
    state.completeAvatarCrop(UIImage())
    state.presentPendingCropIfReady()
    state.presentPendingSheet()
    state.close()
    state.updateContact(refreshed(contact, nickname: "Late refresh"))
    state.setActive(true, appState: appState)
    state.mountRenderer(UUID())

    #expect(!state.accepts(context))
    #expect(closes == 0)
    #expect(state.cropRequest == nil)
    #expect(state.pendingCropImage == nil)
    #expect(state.activeSheet == nil)
    #expect(!state.showRoomJoinSheet)
    #expect(!state.pathViewModel.isDiscovering)
    #expect(state.currentContact.nickname == contact.nickname)
    #expect(state.rendererID == nil)
  }

  @Test
  func `synchronous root replacement rejects old completion before lifetime teardown`() {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.navigateToContactDetail(contact)
    let revision = appState.navigation.nodesPresentationRevision
    let state = ContactDetailState(contact: contact)
    var closes = 0
    state.configure(
      appState: appState,
      isCurrentPresentation: { appState.navigation.nodesPresentationRevision == revision },
      onClose: { closes += 1 }
    )
    let context = state.operationContext
    let replacement = makeContact()
    appState.navigation.navigateToContactDetail(replacement)
    #expect(!state.isEnded)
    #expect(!state.hasPresentationOwnership)
    #expect(!state.accepts(context))
    state.completeAvatarCrop(UIImage())
    state.showTelemetry()
    state.close()
    state.updateContact(refreshed(contact, nickname: "Late refresh"))
    #expect(state.cropRequest == nil)
    #expect(state.activeSheet == nil)
    #expect(state.currentContact.nickname == contact.nickname)
    #expect(appState.navigation.selectedContact?.id == replacement.id)
    #expect(closes == 0)
    state.end()
  }

  @Test(arguments: [false, true])
  func `confirmed removal from an ended screen clears only matching contact and never closes its replacement`(sameContact: Bool) {
    let appState = AppState()
    let removed = makeContact()
    let state = ContactDetailState(contact: removed)
    var closes = 0
    state.configure(appState: appState, onClose: { closes += 1 })
    let context = state.operationContext
    state.end()
    let reopened = sameContact ? removed : makeContact()
    appState.navigation.navigateToContactDetail(reopened)
    state.didRemoveContact(removed.id, navigation: appState.navigation, context: context)
    #expect(appState.navigation.selectedContact?.id == (sameContact ? nil : reopened.id))
    #expect(closes == 0)
  }

  @Test
  func `confirmed removal from a current screen invokes its scoped close`() {
    let appState = AppState()
    let contact = makeContact()
    let state = ContactDetailState(contact: contact)
    var closes = 0
    state.configure(appState: appState, onClose: { closes += 1 })
    appState.navigation.navigateToContactDetail(contact)
    state.didRemoveContact(contact.id, navigation: appState.navigation, context: state.operationContext)
    #expect(appState.navigation.selectedContact == nil)
    #expect(closes == 1)
    state.end()
  }

  @Test
  func `repeated nickname submissions coalesce and completion allows another save`() async throws {
    let appState = AppState()
    let state = ContactDetailState(contact: makeContact())
    state.configure(appState: appState, onClose: {})
    let suspension = SuspendedCommands()
    state.beforeCommandForTesting = suspension.suspend
    state.nickname = "First draft"
    state.isEditingNickname = true
    state.perform(.nickname)
    await suspension.waitForEntries(1)
    #expect(state.isSaving)
    state.perform(.nickname)
    #expect(suspension.entries == [.nickname])
    let first = try #require(state.commandTaskForTesting(.nickname))
    suspension.releaseFirst()
    await first.value
    #expect(!state.isSaving)
    #expect(!state.isEditingNickname)

    state.nickname = "Second draft"
    state.isEditingNickname = true
    state.perform(.nickname)
    await suspension.waitForEntries(2)
    #expect(state.isSaving)
    let second = try #require(state.commandTaskForTesting(.nickname))
    suspension.releaseFirst()
    await second.value
    #expect(suspension.entries == [.nickname, .nickname])
    #expect(!state.isSaving)
    state.end()
  }

  @Test(arguments: [
    ContactDetailState.BusyOperation.nickname, .share, .ping, .clearMessages, .avatar, .favorite
  ])
  func `context retirement permits retry and old cleanup cannot retire the newer command`(
    operation: ContactDetailState.BusyOperation
  ) async throws {
    let appState = AppState()
    let state = ContactDetailState(contact: makeContact())
    var closes = 0
    state.configure(appState: appState, onClose: { closes += 1 })
    let suspension = SuspendedCommands()
    state.beforeCommandForTesting = suspension.suspend
    state.nickname = "Unsaved draft"
    state.isEditingNickname = true
    state.nicknameFocused = true
    start(operation, on: state)
    await suspension.waitForEntries(1)
    #expect(isBusy(operation, on: state))
    let first = try #require(task(operation, on: state))
    state.replaceOperationContextForTesting(.init(servicesVersion: 1, radioID: nil))
    #expect(!isBusy(operation, on: state))
    #expect(state.nickname == "Unsaved draft")
    #expect(state.isEditingNickname)
    #expect(state.nicknameFocused)
    start(operation, on: state)
    await suspension.waitForEntries(2)
    #expect(isBusy(operation, on: state))
    let second = try #require(task(operation, on: state))
    suspension.releaseFirst(throwing: SuspendedFailure.stale)
    await first.value
    #expect(isBusy(operation, on: state))
    #expect(closes == 0)
    #expect(state.pingResult == nil)
    #expect(state.errorMessage == nil)
    suspension.releaseFirst()
    await second.value
    #expect(!isBusy(operation, on: state))
    #expect(suspension.entries == [operation, operation])
    state.end()
  }

  private func start(_ operation: ContactDetailState.BusyOperation, on state: ContactDetailState) {
    switch operation {
    case .nickname: state.perform(.nickname)
    case .share: state.perform(.share)
    case .ping: state.perform(.ping)
    case .clearMessages: state.perform(.clearMessages)
    case .avatar: state.removeAvatarPhoto()
    case .favorite: state.isFavorite = !state.isFavorite
    }
  }

  private func isBusy(_ operation: ContactDetailState.BusyOperation, on state: ContactDetailState) -> Bool {
    switch operation {
    case .nickname: state.isSaving
    case .share: state.isSharing
    case .ping: state.isPinging
    case .clearMessages: state.isClearingMessages
    case .avatar: state.isSavingAvatar
    case .favorite: state.isSettingFavorite
    }
  }

  private func task(_ operation: ContactDetailState.BusyOperation, on state: ContactDetailState) -> Task<Void, Never>? {
    switch operation {
    case .nickname: state.commandTaskForTesting(.nickname)
    case .share: state.commandTaskForTesting(.share)
    case .ping: state.commandTaskForTesting(.ping)
    case .clearMessages: state.commandTaskForTesting(.clearMessages)
    case .avatar: state.avatarTask
    case .favorite: state.favoriteTask
    }
  }

  private enum SuspendedFailure: Error {
    case stale
  }

  @MainActor
  private final class SuspendedCommands {
    var entries: [ContactDetailState.BusyOperation] = []
    private var suspended: [CheckedContinuation<Void, Error>] = []
    private var waiter: (count: Int, continuation: CheckedContinuation<Void, Never>)?

    func suspend(_ operation: ContactDetailState.BusyOperation) async throws {
      try await withCheckedThrowingContinuation { continuation in
        entries.append(operation)
        suspended.append(continuation)
        if let waiter, entries.count >= waiter.count {
          self.waiter = nil
          waiter.continuation.resume()
        }
      }
    }

    func waitForEntries(_ count: Int) async {
      guard entries.count < count else { return }
      await withCheckedContinuation { waiter = (count, $0) }
    }

    func releaseFirst(throwing error: Error? = nil) {
      let continuation = suspended.removeFirst()
      if let error { continuation.resume(throwing: error) } else { continuation.resume() }
    }
  }

  private func refreshed(_ contact: ContactDTO, nickname: String) -> ContactDTO {
    let model = Contact(dto: contact)
    model.nickname = nickname
    return ContactDTO(from: model)
  }

  private func makeContact() -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: UUID(), publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize),
      name: "Alex", typeRawValue: ContactType.chat.rawValue, lastHeardTimestamp: 0
    ))
  }
}
