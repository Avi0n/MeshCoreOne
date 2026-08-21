import Foundation
import MC1Services

/// Process-lifetime JPEG bytes for channel cluster-end photos. Replace-all
/// before bake or patch so first paint never hits an empty map with a live revision.
/// Tests bind `isolatedStorage` so parallel suites do not share this map.
@MainActor
enum IncomingAvatarJPEGStore {
  @MainActor
  final class Storage {
    var jpegByContactID: [UUID: Data] = [:]
  }

  @TaskLocal static var isolatedStorage: Storage?

  private static let processStorage = Storage()
  private static var storage: Storage {
    isolatedStorage ?? processStorage
  }

  static func replace(contacts: [ContactDTO], identities: [IncomingAvatarIdentity]) {
    let jpegByID: [UUID: Data] = Dictionary(
      uniqueKeysWithValues: contacts.compactMap { contact in
        guard let data = contact.avatarImageData, !data.isEmpty else { return nil }
        return (contact.id, data)
      }
    )
    var next: [UUID: Data] = [:]
    for identity in identities {
      guard let contactID = identity.matchedContactID,
            let data = jpegByID[contactID] else { continue }
      next[contactID] = data
    }
    storage.jpegByContactID = next
  }

  static func data(for contactID: UUID?) -> Data? {
    guard let contactID else { return nil }
    return storage.jpegByContactID[contactID]
  }
}
