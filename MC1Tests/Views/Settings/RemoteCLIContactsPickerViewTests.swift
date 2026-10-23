import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("RemoteCLIContactsPickerView selection")
struct RemoteCLIContactsPickerViewTests {
  private func contact(
    name: String,
    type: ContactType = .chat,
    flags: UInt8,
    isBlocked: Bool = false
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xAB, count: 32),
      name: name,
      typeRawValue: type.rawValue,
      flags: flags,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: isBlocked,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0
    )
  }

  @Test
  func `blocked chat contact is absent from the granted set`() {
    let allowed = contact(name: "Allowed", flags: ContactFlags.remoteCLIAllowed.rawValue)
    let blocked = contact(
      name: "Blocked",
      flags: ContactFlags.remoteCLIAllowed.rawValue,
      isBlocked: true
    )
    let repeater = contact(
      name: "Repeater",
      type: .repeater,
      flags: ContactFlags.remoteCLIAllowed.rawValue
    )

    let eligible = RemoteCLIContactsPickerView.Selection.eligibleContacts([allowed, blocked, repeater])
    #expect(eligible.map(\.id) == [allowed.id])

    let granted = RemoteCLIContactsPickerView.Selection.grantedIDs(in: [allowed, blocked, repeater])
    #expect(granted == [allowed.id])
  }

  @Test
  func `baseline after a confirmed grant lets a later deselect revoke`() {
    let granted = UUID()
    let pendingGrant = UUID()
    let eligible: Set<UUID> = [granted, pendingGrant]
    var initial: Set<UUID> = []
    let pending: Set<UUID> = [granted, pendingGrant]

    #expect(
      RemoteCLIContactsPickerView.Selection.idsToGrant(
        pending: pending,
        initial: initial,
        eligible: eligible
      ) == [granted, pendingGrant]
    )

    initial = RemoteCLIContactsPickerView.Selection.baselineAfterGrant(granted, initial: initial)
    #expect(initial == [granted])

    let afterDeselect: Set<UUID> = [pendingGrant]
    #expect(
      RemoteCLIContactsPickerView.Selection.idsToGrant(
        pending: afterDeselect,
        initial: initial,
        eligible: eligible
      ) == [pendingGrant]
    )
    #expect(
      RemoteCLIContactsPickerView.Selection.idsToRevoke(pending: afterDeselect, initial: initial)
        == [granted]
    )
  }

  @Test
  func `nonempty search uses noSearchResults even with favorites only`() {
    #expect(
      RemoteCLIContactsPickerView.Selection.filterEmptyState(
        searchText: "x",
        showFavoritesOnly: true
      ) == .noSearchResults
    )
  }
}
