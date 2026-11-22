import Foundation
import MC1Services

enum WatchlistReading {
  static func battery(_ reading: WatchlistRefreshCoordinator.BatteryReading?, contact: ContactDTO) -> String {
    guard let reading else {
      return L10n.Contacts.Contacts.Watchlist.notChecked
    }
    return BatteryReadingText.string(millivolts: Int(reading.millivolts), ocvArray: contact.activeOCVArray)
  }

  /// Firmware (when a version is known) and last-heard time, joined for the card's secondary line.
  static func detailLine(_ contact: ContactDTO, now: Date) -> String {
    let heard = heardTimestamp(contact).map {
      L10n.Contacts.Contacts.Watchlist.heard(
        RelativeTimestampText.format(date: Date(timeIntervalSince1970: TimeInterval($0)), relativeTo: now)
      )
    } ?? L10n.Contacts.Contacts.Watchlist.notHeard
    guard let version = contact.myNodeFirmwareVersion, !version.isEmpty else { return heard }
    return L10n.Contacts.Contacts.Watchlist.detailSeparator(
      L10n.Contacts.Contacts.Watchlist.firmwareVersion(version), heard
    )
  }

  static func checkedLine(_ reading: WatchlistRefreshCoordinator.BatteryReading?, now: Date) -> String {
    guard let reading else { return L10n.Contacts.Contacts.Watchlist.notChecked }
    return L10n.Contacts.Contacts.Watchlist.checked(RelativeTimestampText.format(date: reading.savedAt, relativeTo: now))
  }

  static func heardTimestamp(_ contact: ContactDTO) -> UInt32? {
    guard let heard = contact.lastHeardTimestamp, heard > 0 else { return nil }
    return heard
  }
}
