import Foundation
import MeshCore

extension ContactService {
  func reconcileBlockedRemoteCLI(radioID: UUID, contacts: [MeshContact]) async throws -> [ContactFrame] {
    var frames = contacts.map { $0.toContactFrame() }
    let blockedContacts = try await dataStore.fetchBlockedContacts(radioID: radioID)

    for blocked in blockedContacts {
      let index = contacts.firstIndex { $0.publicKey == blocked.publicKey }
      let liveContact: MeshContact? = if let index {
        contacts[index]
      } else {
        try await session.getContact(publicKey: blocked.publicKey)
      }
      guard let liveContact else { continue }

      var frame = liveContact.toContactFrame()
      if liveContact.flags.contains(.remoteCLIAllowed) {
        var flags = liveContact.flags
        flags.remove(.remoteCLIAllowed)
        frame = ContactFrame(
          publicKey: frame.publicKey,
          type: frame.type,
          typeRawValue: frame.typeRawValue,
          flags: flags.rawValue,
          outPathLength: frame.outPathLength,
          outPath: frame.outPath,
          name: frame.name,
          lastAdvertTimestamp: frame.lastAdvertTimestamp,
          latitude: frame.latitude,
          longitude: frame.longitude,
          lastModified: frame.lastModified
        )
        try await session.addContact(frame.toMeshContact())
      }

      if let index {
        frames[index] = frame
      } else {
        _ = try await dataStore.saveContact(radioID: radioID, from: frame)
      }
    }

    return frames
  }
}
