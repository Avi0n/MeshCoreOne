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
    #expect(navigation.nodesSelection?.rootContact?.id == contact.id)
    state.compactBinding(in: navigation).wrappedValue = [root]
    #expect(navigation.nodesSelection?.rootContact?.id == contact.id)
    state.compactBinding(in: navigation).wrappedValue = []
    #expect(navigation.nodesSelection?.rootContact == nil)
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
    #expect(navigation.nodesSelection?.rootContact == nil)
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
    #expect(navigation.nodesSelection?.rootContact?.id == requested.id)
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
    #expect(navigation.nodesSelection?.rootContact?.id == contact.id)
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
    #expect(navigation.nodesSelection != .discovery)
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
    navigation.nodesSelection = .contact(contact)
    let root = try #require(NodesNavigationState.root(in: navigation))
    state.compactBinding(in: navigation).wrappedValue = [root, historyRoute(contact)]
    let oldBack = state.compactBinding(in: navigation)
    if clearReopen { navigation.nodesSelection = nil }
    navigation.nodesSelection = .contact(clearReopen ? contact : makeContact())
    #expect(state.detailPath(in: navigation).isEmpty)
    oldBack.wrappedValue = []
    #expect(navigation.nodesSelection?.rootContact != nil)
    oldBack.wrappedValue = [root, historyRoute(contact)]
    #expect(state.detailPath(in: navigation).isEmpty)
  }

  @Test(arguments: [false, true])
  func `payload refresh keeps history and explicit same root resets it`(fromWatchlist: Bool) throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let original = fromWatchlist ? makeRepeater(radioID: UUID(), name: "Ridge") : makeContact()
    if fromWatchlist {
      navigation.navigateToWatchlist(radioID: original.radioID)
      state.openContact(original, in: navigation)
    } else {
      navigation.navigateToContactDetail(original)
    }
    let revision = navigation.nodesPresentationRevision
    let root = try #require(NodesNavigationState.root(in: navigation))
    let prefix = state.detailPath(in: navigation)
    let history = historyRoute(original)
    state.compactBinding(in: navigation).wrappedValue = [root] + prefix + [history]
    let model = Contact(dto: original)
    model.nickname = "Edited"
    let updated = ContactDTO(from: model)
    navigation.updateSelectedContact(updated)
    #expect(navigation.nodesSelection?.contact?.nickname == "Edited")
    #expect(navigation.nodesPresentationRevision == revision)
    #expect(state.detailPath(in: navigation) == prefix + [history])
    let oldBack = state.compactBinding(in: navigation)
    if fromWatchlist {
      navigation.navigateToWatchlist(radioID: original.radioID)
      #expect(navigation.nodesSelection?.contact == nil)
    } else {
      navigation.navigateToContactDetail(updated)
    }
    #expect(state.detailPath(in: navigation).isEmpty)
    oldBack.wrappedValue = []
    #expect(NodesNavigationState.root(in: navigation) == root)
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
    #expect(navigation.nodesSelection?.rootContact == nil)
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
    #expect(navigation.nodesSelection?.rootContact?.id == contact.id)
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
    #expect(navigation.nodesSelection?.rootContact?.id == contact.id)
  }

  @Test
  func `watchlist back restores the board before root back clears it`() throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let radioID = UUID()
    let repeater = makeRepeater(radioID: radioID, name: "Ridge")
    navigation.navigateToWatchlist(radioID: radioID)
    let root = try #require(NodesNavigationState.root(in: navigation))
    let child = NodesRoute.contact(id: repeater.id, presentationRevision: navigation.nodesPresentationRevision)
    state.openContact(repeater, in: navigation)
    state.compactBinding(in: navigation).wrappedValue = [root, child, historyRoute(repeater)]
    let mapID = UUID()
    state.openMap(presentationID: mapID, in: navigation)
    #expect(state.detailPath(in: navigation) == [
      child, historyRoute(repeater), .locationMap(presentationID: mapID),
    ])

    state.compactBinding(in: navigation).wrappedValue = [root, child, historyRoute(repeater)]
    state.compactBinding(in: navigation).wrappedValue = [root, child]
    #expect(navigation.nodesSelection?.watchlistRadioID == radioID)
    #expect(navigation.nodesSelection?.contact?.id == repeater.id)
    state.compactBinding(in: navigation).wrappedValue = [root]
    #expect(navigation.nodesSelection?.watchlistRadioID == radioID)
    #expect(navigation.nodesSelection?.contact == nil)
    state.compactBinding(in: navigation).wrappedValue = []
    #expect(navigation.nodesSelection?.watchlistRadioID == nil)
    #expect(state.compactPath(in: navigation).isEmpty)
  }

  @Test
  func `regular watchlist stays selected while a child pops and a sidebar contact replaces it`() {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .regular)
    let radioID = UUID()
    let repeater = makeRepeater(radioID: radioID, name: "Ridge")
    navigation.navigateToWatchlist(radioID: radioID)
    let child = NodesRoute.contact(id: repeater.id, presentationRevision: navigation.nodesPresentationRevision)
    state.openContact(repeater, in: navigation)
    state.detailBinding(in: navigation).wrappedValue = [child, historyRoute(repeater)]
    state.detailBinding(in: navigation).wrappedValue = []
    #expect(navigation.nodesSelection?.watchlistRadioID == radioID)
    #expect(state.detailPath(in: navigation).isEmpty)

    let contact = makeContact()
    navigation.nodesSelection = .contact(contact)
    #expect(navigation.nodesSelection?.watchlistRadioID == nil)
    #expect(NodesNavigationState.root(in: navigation) == .contact(
      id: contact.id, presentationRevision: navigation.nodesPresentationRevision
    ))
  }

  @Test
  func `same radio disconnect keeps the board and replacement clears it`() throws {
    let navigation = NavigationCoordinator()
    let state = NodesNavigationState(presentation: .compact)
    let radioID = UUID()
    let repeater = makeRepeater(radioID: radioID, name: "Ridge")
    navigation.navigateToWatchlist(radioID: radioID)
    let root = try #require(NodesNavigationState.root(in: navigation))
    state.openContact(repeater, in: navigation)
    let child = NodesRoute.contact(id: repeater.id, presentationRevision: navigation.nodesPresentationRevision)
    state.compactBinding(in: navigation).wrappedValue = [root, child, historyRoute(repeater)]

    navigation.clearPendingLinks()
    #expect(navigation.nodesSelection?.watchlistRadioID == radioID)
    #expect(navigation.nodesSelection?.contact?.id == repeater.id)
    #expect(state.detailPath(in: navigation) == [child, historyRoute(repeater)])

    state.changePresentation(to: .regular)
    #expect(navigation.nodesSelection?.watchlistRadioID == radioID)
    #expect(state.detailPath(in: navigation) == [child, historyRoute(repeater)])
    state.changePresentation(to: .compact)
    #expect(state.compactPath(in: navigation) == [root, child, historyRoute(repeater)])

    navigation.clearPerRadioSelection()
    #expect(navigation.nodesSelection?.watchlistRadioID == nil)
    #expect(navigation.nodesSelection?.contact == nil)
    #expect(state.detailPath(in: navigation).isEmpty)
  }

  @Test
  func `forgetting the board radio clears only that board`() {
    let navigation = NavigationCoordinator()
    let radioID = UUID()
    navigation.navigateToWatchlist(radioID: radioID)
    navigation.clearWatchlistRoot(matching: UUID())
    #expect(navigation.nodesSelection?.watchlistRadioID == radioID)
    navigation.clearWatchlistRoot(matching: radioID)
    #expect(navigation.nodesSelection?.watchlistRadioID == nil)
  }

  private func historyRoute(_ contact: ContactDTO) -> NodesRoute {
    .savedHistory(.init(publicKey: contact.publicKey, radioID: contact.radioID, showNeighbors: false))
  }

  private func makeRepeater(radioID: UUID, name: String) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: radioID, publicKey: Data(repeating: 0x44, count: ProtocolLimits.publicKeySize),
      name: name, typeRawValue: ContactType.repeater.rawValue, lastHeardTimestamp: 0, isMyNode: true
    ))
  }

  private func makeContact() -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: UUID(), publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
      name: "Alex", typeRawValue: ContactType.chat.rawValue, lastHeardTimestamp: 0
    ))
  }
}
