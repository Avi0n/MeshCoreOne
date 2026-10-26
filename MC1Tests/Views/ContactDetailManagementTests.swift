import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Contact detail Management handoff")
@MainActor
struct ContactDetailManagementTests {
  @Test(arguments: [ContactType.repeater, .room])
  func `Management opens authentication in the item sheet`(type: ContactType) {
    let state = ContactDetailState(contact: makeContact(type: type))
    defer { state.end() }

    state.showAdminAccess()

    #expect(state.activeSheet?.id == "admin-auth")
    #expect(state.pendingSheet == nil)
    #expect(state.pendingSheetContext == nil)
  }

  @Test
  func `pending Management destination is consumed once and Done leaves no follow-up`() {
    let contact = makeContact()
    let state = ContactDetailState(contact: contact)
    defer { state.end() }
    let session = makeSession(contact: contact)
    state.pendingSheet = .adminSettings(session)
    state.pendingSheetContext = state.operationContext

    state.presentPendingSheet()

    #expect(state.activeSheet == .adminSettings(session))
    #expect(state.pendingSheet == nil)
    #expect(state.pendingSheetContext == nil)

    state.activeSheet = nil
    state.presentPendingSheet()

    #expect(state.activeSheet == nil)
  }

  @Test
  func `authentication dismissal without a pending destination does not present another sheet`() {
    let state = ContactDetailState(contact: makeContact())
    defer { state.end() }
    state.showAdminAccess()
    state.activeSheet = nil

    state.presentPendingSheet()

    #expect(state.activeSheet == nil)
    #expect(state.pendingSheet == nil)
    #expect(state.pendingSheetContext == nil)
  }

  @Test
  func `pending Management destination from a replaced context is discarded`() {
    let contact = makeContact()
    let state = ContactDetailState(contact: contact)
    defer { state.end() }
    state.pendingSheet = .adminSettings(makeSession(contact: contact))
    state.pendingSheetContext = state.operationContext
    state.replaceOperationContextForTesting(.init(servicesVersion: nil, radioID: UUID()))

    state.presentPendingSheet()

    #expect(state.activeSheet == nil)
    #expect(state.pendingSheet == nil)
    #expect(state.pendingSheetContext == nil)
  }

  @Test
  func `ended contact rejects a delayed pending Management destination and reopening authentication`() {
    let contact = makeContact()
    let state = ContactDetailState(contact: contact)
    let context = state.operationContext
    state.end()
    state.pendingSheet = .adminSettings(makeSession(contact: contact))
    state.pendingSheetContext = context

    state.presentPendingSheet()
    state.showAdminAccess()

    #expect(state.activeSheet == nil)
    #expect(state.pendingSheet == nil)
    #expect(state.pendingSheetContext == nil)
  }

  private func makeContact(type: ContactType = .repeater) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: UUID(), publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize),
      name: "Relay", typeRawValue: type.rawValue, lastHeardTimestamp: 0
    ))
  }

  private func makeSession(contact: ContactDTO) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      radioID: contact.radioID, publicKey: contact.publicKey, name: contact.name,
      role: .repeater, permissionLevel: .admin
    )
  }
}
