import Foundation

/// A contact fetch paired with the device's reported contact total.
///
/// The device sends the total in the `contactsStart` header at the start of a
/// `GET_CONTACTS` reply. On a full fetch (`since == nil`) the total is the
/// device's complete contact count, so a caller that prunes local rows can
/// compare it to `contacts.count` and skip the prune on a truncated stream.
///
/// `reportedTotal` is `nil` when the reply completed without a `contactsStart`
/// header. The total is then unknown, so a prune caller must not treat the
/// received set as complete.
public struct ContactFetchResult: Sendable {
  /// The contacts received in the reply.
  public let contacts: [MeshContact]

  /// The contact total the device reported in the `contactsStart` header, or
  /// `nil` if the header never arrived.
  public let reportedTotal: Int?

  public init(contacts: [MeshContact], reportedTotal: Int?) {
    self.contacts = contacts
    self.reportedTotal = reportedTotal
  }
}
