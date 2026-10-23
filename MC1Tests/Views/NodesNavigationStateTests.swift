import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Nodes route ownership")
@MainActor
struct NodesNavigationStateTests {
  @Test
  func `history and map back retain the root before root back clears it`() throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let contact = makeContact()
    navigation.navigateToContactDetail(contact)
    let root = try #require(NodesNavigationState.root(in: navigation))
    let history = historyRoute(contact)
    state.compactBinding(in: navigation).wrappedValue = [root, history]
    let mapID = UUID()
    state.openMap(presentationID: mapID, in: navigation)
    #expect(state.compactPath(in: navigation) == [root, history, .locationMap(presentationID: mapID)])

    state.compactBinding(in: navigation).wrappedValue = [root, history]
    #expect(navigation.selectedContact?.id == contact.id)
    state.compactBinding(in: navigation).wrappedValue = [root]
    #expect(navigation.selectedContact?.id == contact.id)
    state.compactBinding(in: navigation).wrappedValue = []
    #expect(navigation.selectedContact == nil)
    #expect(state.compactPath(in: navigation).isEmpty)
  }

  @Test(arguments: [false, true])
  func `blocked ancestry survives root back or clears with a jump to nodes`(jumpToNodes: Bool) throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    state.compactBinding(in: navigation).wrappedValue = [.blockedContacts]
    let contact = makeContact()
    navigation.navigateToContactDetail(contact)
    let root = try #require(NodesNavigationState.root(in: navigation))
    state.compactBinding(in: navigation).wrappedValue = [.blockedContacts, root, historyRoute(contact)]

    let remaining: [NodesRoute] = jumpToNodes ? [] : [.blockedContacts]
    state.compactBinding(in: navigation).wrappedValue = remaining
    #expect(navigation.selectedContact == nil)
    #expect(state.compactPath(in: navigation) == remaining)
  }

  @Test(arguments: [false, true])
  func `regular sidebar back remains current after a detail request`(sameRoot: Bool) {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .regular)
    state.sidebarBinding().wrappedValue = [.blockedContacts]
    let contact = makeContact()
    navigation.navigateToContactDetail(contact)
    let sidebarBack = state.sidebarBinding()
    let requested = sameRoot ? contact : makeContact()
    navigation.navigateToContactDetail(requested)
    sidebarBack.wrappedValue = []

    #expect(state.sidebarPath.isEmpty)
    #expect(navigation.selectedContact?.id == requested.id)
    state.changePresentation(to: .compact)
    #expect(!state.compactPath(in: navigation).contains(.blockedContacts))
  }

  @Test
  func `ended and reopened sidebar rejects an old back`() {
    let state = NodesNavigationState(presentation: .regular)
    state.sidebarBinding().wrappedValue = [.blockedContacts]
    let oldBack = state.sidebarBinding()
    state.sidebarBinding().wrappedValue = []
    state.sidebarBinding().wrappedValue = [.blockedContacts]
    oldBack.wrappedValue = []
    #expect(state.sidebarPath == [.blockedContacts])
  }

  @Test
  func `old compact binding cannot clear a root after compact regular compact`() {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let contact = makeContact()
    navigation.navigateToContactDetail(contact)
    let oldBack = state.compactBinding(in: navigation)
    state.changePresentation(to: .regular)
    state.changePresentation(to: .compact)
    oldBack.wrappedValue = []
    #expect(navigation.selectedContact?.id == contact.id)
  }

  @Test
  func `same mode resize keeps a current binding and prepare precedes branch change`() {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    navigation.navigateToDiscovery()
    let back = state.compactBinding(in: navigation)
    let revision = state.layoutRevision
    state.changePresentation(to: .compact)
    #expect(state.layoutRevision == revision)
    back.wrappedValue = []
    #expect(!navigation.nodesShowingDiscovery)
    state.changePresentation(to: .regular) { nextRevision in
      #expect(state.presentation == .compact)
      #expect(state.layoutRevision == nextRevision)
    }
  }

  @Test(arguments: [false, true])
  func `replacement and clear reopen reject old root writes`(clearReopen: Bool) throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let contact = makeContact()
    navigation.selectedContact = contact
    let root = try #require(NodesNavigationState.root(in: navigation))
    state.compactBinding(in: navigation).wrappedValue = [root, historyRoute(contact)]
    let oldBack = state.compactBinding(in: navigation)
    if clearReopen { navigation.selectedContact = nil }
    navigation.selectedContact = clearReopen ? contact : makeContact()
    #expect(state.detailPath(in: navigation).isEmpty)
    oldBack.wrappedValue = []
    #expect(navigation.selectedContact != nil)
    oldBack.wrappedValue = [root, historyRoute(contact)]
    #expect(state.detailPath(in: navigation).isEmpty)
  }

  @Test
  func `payload refresh keeps history and explicit same root resets it`() throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let original = makeContact()
    let model = Contact(dto: original)
    var contact = original
    navigation.selectedContact = contact
    let revision = navigation.nodesPresentationRevision
    let root = try #require(NodesNavigationState.root(in: navigation))
    let history = historyRoute(contact)
    state.compactBinding(in: navigation).wrappedValue = [root, history]
    model.nickname = "Edited"
    contact = ContactDTO(from: model)
    navigation.selectedContact = contact
    #expect(navigation.nodesPresentationRevision == revision)
    #expect(state.detailPath(in: navigation) == [history])
    let oldBack = state.compactBinding(in: navigation)
    navigation.navigateToContactDetail(contact)
    #expect(state.detailPath(in: navigation).isEmpty)
    oldBack.wrappedValue = []
    #expect(navigation.selectedContact?.id == contact.id)
  }

  @Test
  func `hidden radio invalidation never restores the root or descendants`() throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let contact = makeContact()
    navigation.navigateToContactDetail(contact)
    let root = try #require(NodesNavigationState.root(in: navigation))
    state.compactBinding(in: navigation).wrappedValue = [root, historyRoute(contact)]
    let oldBinding = state.compactBinding(in: navigation)
    navigation.selectedTab = AppTab.map.rawValue
    navigation.clearPerRadioSelection()
    #expect(state.compactPath(in: navigation).isEmpty)
    oldBinding.wrappedValue = [root, historyRoute(contact)]
    #expect(navigation.selectedContact == nil)
    #expect(state.detailPath(in: navigation).isEmpty)
  }

  @Test
  func `regular detail back changes only descendants`() {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .regular)
    state.sidebarBinding().wrappedValue = [.blockedContacts]
    let contact = makeContact()
    navigation.navigateToContactDetail(contact)
    state.detailBinding(in: navigation).wrappedValue = [historyRoute(contact)]
    state.detailBinding(in: navigation).wrappedValue = []
    #expect(navigation.selectedContact?.id == contact.id)
    #expect(state.sidebarPath == [.blockedContacts])
  }

  @Test
  func `path cannot install another root or history belonging to another contact`() throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let contact = makeContact()
    let other = makeContact()
    navigation.navigateToContactDetail(contact)
    let root = try #require(NodesNavigationState.root(in: navigation))
    state.compactBinding(in: navigation).wrappedValue = [root, historyRoute(other)]
    #expect(state.detailPath(in: navigation).isEmpty)
    state.compactBinding(in: navigation).wrappedValue = [.contact(id: other.id, presentationRevision: 0)]
    #expect(navigation.selectedContact?.id == contact.id)
  }

  private func historyRoute(_ contact: ContactDTO) -> NodesRoute {
    .savedHistory(.init(publicKey: contact.publicKey, radioID: contact.radioID, showNeighbors: false))
  }

  private func makeContact() -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: UUID(), publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
      name: "Alex", typeRawValue: ContactType.chat.rawValue, lastHeardTimestamp: 0
    ))
  }
}
