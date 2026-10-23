import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("Nodes list timestamps")
@MainActor
struct NodeListTimestampTests {
  private static let referenceDate = Date(timeIntervalSince1970: 1_700_000_000)

  @Test(arguments: [1_577_836_800, 1_600_000_000, 1_700_000_300] as [UInt32])
  func `valid advert prevails over newer Last Heard and Last Modified`(advert: UInt32) {
    let contact = makeContact(
      advert: advert,
      heard: 1_700_000_600,
      modified: UInt32.max
    )

    #expect(contact.nodeListTimestamp(relativeTo: Self.referenceDate) == advert)
  }

  @Test(arguments: [0, 1_577_836_799, 1_700_000_301, UInt32.max] as [UInt32])
  func `invalid advert falls back to positive Last Heard`(advert: UInt32) {
    let contact = makeContact(advert: advert, heard: 1_700_000_600)

    #expect(contact.nodeListTimestamp(relativeTo: Self.referenceDate) == 1_700_000_600)
  }

  @Test(arguments: [nil, 0] as [UInt32?])
  func `invalid advert without Last Heard is unknown`(heard: UInt32?) {
    let contact = makeContact(advert: 0, heard: heard, modified: UInt32.max)

    #expect(contact.nodeListTimestamp(relativeTo: Self.referenceDate) == nil)
  }

  @Test
  func `stored lastHeard preference selects advert ordering`() throws {
    let sortOrder = try #require(NodeSortOrder(rawValue: "lastHeard"))
    let viewModel = ContactsViewModel()
    let olderAdvert = makeContact(name: "Older", advert: 1_699_990_000, heard: 1_700_000_000, modified: 1_700_000_010)
    let newerAdvert = makeContact(name: "Newer", advert: 1_699_999_000)
    viewModel.contacts = [olderAdvert, newerAdvert]

    let contacts = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: sortOrder,
      userLocation: nil,
      referenceDate: Self.referenceDate
    )

    #expect(contacts.map(\.id) == [newerAdvert.id, olderAdvert.id])
  }

  @Test(arguments: [
    (1_577_836_799, ["Other", "Subject"]),
    (1_577_836_800, ["Subject", "Other"]),
    (1_700_000_300, ["Subject", "Other"]),
    (1_700_000_301, ["Other", "Subject"])
  ] as [(UInt32, [String])])
  func `sort uses inclusive advert validity bounds`(advert: UInt32, expectedNames: [String]) {
    let viewModel = ContactsViewModel()
    viewModel.contacts = [
      makeContact(name: "Subject", advert: advert, heard: 1_577_836_797, modified: UInt32.max),
      makeContact(name: "Other", advert: 0, heard: 1_577_836_798)
    ]

    let contacts = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .lastHeard,
      userLocation: nil,
      referenceDate: Self.referenceDate
    )

    #expect(contacts.map(\.name) == expectedNames)
  }

  @Test
  func `dated contacts sort before unknowns and ties retain input order`() {
    let viewModel = ContactsViewModel()
    let knownZ = makeContact(name: "Known Z", advert: 1_699_999_990)
    let knownA = makeContact(name: "Known A", advert: 1_699_999_990, modified: UInt32.max)
    let unknownZ = makeContact(name: "Unknown Z", advert: 0, heard: nil)
    let unknownA = makeContact(name: "Unknown A", advert: 0, heard: 0)
    viewModel.contacts = [unknownZ, knownZ, unknownA, knownA]

    let contacts = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .lastHeard,
      userLocation: nil,
      referenceDate: Self.referenceDate
    )

    #expect(contacts.map(\.id) == [knownZ.id, knownA.id, unknownZ.id, unknownA.id])
  }

  @Test(arguments: [
    (1_699_913_600, 1_699_913_600),
    (nil, nil),
    (0, nil)
  ] as [(UInt32?, UInt32?)])
  func `delayed rows retain their date until the list is reevaluated`(heard: UInt32?, expected: UInt32?) {
    let viewModel = ContactsViewModel()
    let lateAdvert = makeContact(name: "Late Advert", advert: 1_700_000_301, heard: heard)
    let other = makeContact(name: "Other", advert: 1_699_996_400)
    viewModel.contacts = [lateAdvert, other]
    let actions = ContactListActions(viewModel: viewModel, appState: AppState(), syncSuccessTrigger: .constant(false))

    let original = actions.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .lastHeard,
      referenceDate: Self.referenceDate
    )
    #expect(original.contacts.map(\.id) == [other.id, lateAdvert.id])
    #expect(original.timestampsByID[lateAdvert.id] == expected)

    let refreshed = actions.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .lastHeard,
      referenceDate: Self.referenceDate.addingTimeInterval(120)
    )
    #expect(refreshed.contacts.map(\.id) == [lateAdvert.id, other.id])
    #expect(refreshed.timestampsByID[lateAdvert.id] == 1_700_000_301)

    #expect(original.timestampsByID[lateAdvert.id] == expected)
    #expect(original.contacts.map(\.id) == [other.id, lateAdvert.id])
  }

  private func makeContact(
    name: String = "Contact",
    advert: UInt32,
    heard: UInt32? = nil,
    modified: UInt32 = 0
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xAA, count: ProtocolLimits.publicKeySize),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: advert,
      latitude: 0,
      longitude: 0,
      lastModified: modified,
      lastHeardTimestamp: heard,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }
}
