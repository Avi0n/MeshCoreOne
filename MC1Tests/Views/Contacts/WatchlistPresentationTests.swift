import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Watchlist directory and readings")
@MainActor
struct WatchlistPresentationTests {
  @Test
  func `the directory button follows raw search and any repeater on the radio`() {
    let radioID = UUID()
    let marked = repeater(radioID: radioID, name: "Ada", isMyNode: true)
    let pending = repeater(radioID: radioID, name: "Bea", isMyNode: true)
    let otherRadio = repeater(radioID: UUID(), name: "Cara", isMyNode: true)
    let favorite = repeater(radioID: radioID, name: "Dee", isMyNode: false).with(isFavorite: true)
    let contacts = [marked, pending, otherRadio, favorite]

    #expect(WatchlistDirectory.showsButton(
      searchText: "", contacts: contacts, radioID: radioID, pendingRemovalIDs: []
    ))
    #expect(!WatchlistDirectory.showsButton(
      searchText: " ", contacts: contacts, radioID: radioID, pendingRemovalIDs: []
    ))
    #expect(!WatchlistDirectory.showsButton(
      searchText: "Ada", contacts: contacts, radioID: radioID, pendingRemovalIDs: []
    ))
    #expect(WatchlistDirectory.showsButton(
      searchText: "", contacts: contacts, radioID: radioID, pendingRemovalIDs: [marked.id, pending.id]
    ))
    #expect(!WatchlistDirectory.showsButton(
      searchText: "", contacts: contacts, radioID: radioID, pendingRemovalIDs: [marked.id, pending.id, favorite.id]
    ))
    #expect(WatchlistDirectory.showsButton(
      searchText: "", contacts: [favorite], radioID: radioID, pendingRemovalIDs: []
    ))
    #expect(!WatchlistDirectory.showsButton(
      searchText: "", contacts: [otherRadio, chat(radioID: radioID)], radioID: radioID, pendingRemovalIDs: []
    ))
    #expect(WatchlistDirectory.markedRepeaters(
      in: contacts, radioID: radioID, pendingRemovalIDs: [pending.id]
    ).map(\.id) == [marked.id])
  }

  @Test
  func `rows sort by display name`() {
    let radioID = UUID()
    let ada = repeater(radioID: radioID, name: "Ada", isMyNode: true)
    var bea = repeater(radioID: radioID, name: "Bea", isMyNode: true)
    bea = ContactDTO(from: Contact(
      radioID: radioID, publicKey: bea.publicKey, name: "Bea", typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: 0, nickname: "Aaron", isMyNode: true
    ))
    let rows = WatchlistDirectory.markedRepeaters(in: [ada, bea], radioID: radioID, pendingRemovalIDs: [])
    #expect(rows.map(\.name) == ["Bea", "Ada"])
  }

  @Test
  func `readings distinguish unchecked zero and unknown values`() {
    let radioID = UUID()
    let savedAt = Date(timeIntervalSince1970: 1_700_000_000)
    let heard: UInt32 = 1_800_000_000
    let unchecked = repeater(radioID: radioID, name: "Ada", isMyNode: true)
    let zero = repeater(radioID: radioID, name: "Bea", firmware: "", heard: heard, preset: .liIon)
    let alkaline = repeater(radioID: radioID, name: "Cara", firmware: "1.2.3", heard: 0, preset: .alkaline)
    let liIon = repeater(radioID: radioID, name: "Dee", firmware: nil, heard: heard, preset: .liIon)
    let empty = WatchlistRefreshCoordinator.BatteryReading(millivolts: 0, savedAt: savedAt)
    let mid = WatchlistRefreshCoordinator.BatteryReading(millivolts: 3300, savedAt: savedAt)

    #expect(WatchlistReading.battery(nil, contact: unchecked) == L10n.Contacts.Contacts.Watchlist.notChecked)
    #expect(WatchlistReading.battery(empty, contact: zero).hasPrefix("\(volts(0))V ("))
    #expect(WatchlistReading.battery(mid, contact: alkaline) != WatchlistReading.battery(mid, contact: liIon))
    #expect(WatchlistReading.heardTimestamp(zero) == heard)
    #expect(WatchlistReading.heardTimestamp(alkaline) == nil)
  }

  @Test
  func `card lines label each time and omit firmware without a version`() {
    let radioID = UUID()
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let fiveMinutes: TimeInterval = 300
    let heard = UInt32(now.timeIntervalSince1970 - fiveMinutes)
    let heardText = L10n.Contacts.Contacts.Watchlist.heard(
      RelativeTimestampText.format(date: now.addingTimeInterval(-fiveMinutes), relativeTo: now)
    )
    let versioned = repeater(radioID: radioID, name: "Ada", firmware: "1.18.0", heard: heard, preset: .liIon)
    let unknown = repeater(radioID: radioID, name: "Bea", firmware: "", heard: heard, preset: .liIon)
    let unchecked = repeater(radioID: radioID, name: "Cara", firmware: nil, heard: heard, preset: .liIon)
    let silent = repeater(radioID: radioID, name: "Dee", firmware: "1.18.0", heard: 0, preset: .liIon)
    let reading = WatchlistRefreshCoordinator.BatteryReading(millivolts: 4000, savedAt: now.addingTimeInterval(-fiveMinutes))

    #expect(WatchlistReading.detailLine(versioned, now: now) == L10n.Contacts.Contacts.Watchlist.detailSeparator(
      L10n.Contacts.Contacts.Watchlist.firmwareVersion("1.18.0"), heardText
    ))
    #expect(WatchlistReading.detailLine(unknown, now: now) == heardText)
    #expect(WatchlistReading.detailLine(unchecked, now: now) == heardText)
    #expect(WatchlistReading.detailLine(silent, now: now).hasSuffix(L10n.Contacts.Contacts.Watchlist.notHeard))
    #expect(WatchlistReading.checkedLine(nil, now: now) == L10n.Contacts.Contacts.Watchlist.notChecked)
    #expect(WatchlistReading.checkedLine(reading, now: now) == L10n.Contacts.Contacts.Watchlist.checked(
      RelativeTimestampText.format(date: reading.savedAt, relativeTo: now)
    ))
  }

  @Test
  func `favorite locks only for a repeater in Watchlist and adding explains the coupling`() {
    let lockText = L10n.Localizable.Error.ContactService.removeFromWatchlistBeforeUnfavorite
    let addText = L10n.Contacts.Contacts.Watchlist.addMarksFavorite
    typealias Explanation = WatchlistFavoriteExplanation

    #expect(Explanation.locksFavorite(type: .repeater, isMyNode: true, isFavorite: true))
    #expect(!Explanation.locksFavorite(type: .repeater, isMyNode: true, isFavorite: false))
    #expect(!Explanation.locksFavorite(type: .repeater, isMyNode: false, isFavorite: true))
    #expect(!Explanation.locksFavorite(type: .chat, isMyNode: true, isFavorite: true))

    #expect(Explanation.actionsFooter(type: .repeater, isMyNode: true, isFavorite: true) == lockText)
    #expect(Explanation.actionsFooter(type: .repeater, isMyNode: false, isFavorite: false) == addText)
    #expect(Explanation.actionsFooter(type: .repeater, isMyNode: false, isFavorite: true) == addText)
    #expect(Explanation.actionsFooter(type: .chat, isMyNode: false, isFavorite: false) == nil)
    #expect(Explanation.text(type: .repeater, isMyNode: true, isFavorite: true) == lockText)
    #expect(Explanation.text(type: .repeater, isMyNode: true, isFavorite: false) == nil)
    #expect(Explanation.text(type: .repeater, isMyNode: false, isFavorite: true) == nil)
  }

  private func chat(radioID: UUID) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "Chat",
      typeRawValue: ContactType.chat.rawValue,
      lastHeardTimestamp: 40
    ))
  }

  private func volts(_ millivolts: Int) -> String {
    (Double(millivolts) / 1000).formatted(.number.precision(.fractionLength(3)))
  }

  private func repeater(
    radioID: UUID,
    name: String,
    firmware: String?,
    heard: UInt32,
    preset: OCVPreset
  ) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: heard,
      ocvPreset: preset.rawValue,
      isMyNode: true,
      myNodeFirmwareVersion: firmware
    ))
  }

  private func repeater(radioID: UUID, name: String, isMyNode: Bool) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: 40,
      isMyNode: isMyNode
    ))
  }
}
