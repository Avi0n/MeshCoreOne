import Foundation
import MC1Services

/// Holds remote-admin view models for each node until radio reset.
@Observable
@MainActor
final class RemoteAdminWorkspaces {
  struct NodeKey: Hashable {
    let radioID: UUID
    let publicKey: Data
  }

  private struct Entry {
    var repeaterSettings: RepeaterSettingsViewModel?
    var repeaterStatus: RepeaterStatusViewModel?
    var roomSettings: RoomSettingsViewModel?
    var roomStatus: RoomStatusViewModel?
    var nodeCLI: NodeCLIViewModel?
  }

  private var entries: [NodeKey: Entry] = [:]

  func repeaterSettings(for session: RemoteNodeSessionDTO) -> RepeaterSettingsViewModel {
    mutate(session) { entry in
      let model = entry.repeaterSettings ?? RepeaterSettingsViewModel()
      entry.repeaterSettings = model
      return model
    }
  }

  func repeaterStatus(for session: RemoteNodeSessionDTO) -> RepeaterStatusViewModel {
    mutate(session) { entry in
      let model = entry.repeaterStatus ?? RepeaterStatusViewModel()
      entry.repeaterStatus = model
      return model
    }
  }

  func roomSettings(for session: RemoteNodeSessionDTO) -> RoomSettingsViewModel {
    mutate(session) { entry in
      let model = entry.roomSettings ?? RoomSettingsViewModel()
      entry.roomSettings = model
      return model
    }
  }

  func roomStatus(for session: RemoteNodeSessionDTO) -> RoomStatusViewModel {
    mutate(session) { entry in
      let model = entry.roomStatus ?? RoomStatusViewModel()
      entry.roomStatus = model
      return model
    }
  }

  func nodeCLI(for session: RemoteNodeSessionDTO) -> NodeCLIViewModel {
    mutate(session) { entry in
      let model = entry.nodeCLI ?? NodeCLIViewModel()
      entry.nodeCLI = model
      return model
    }
  }

  func reset() {
    for entry in entries.values {
      entry.repeaterStatus?.stopDiscovery()
      entry.nodeCLI?.cancelCurrentCommand()
    }
    entries.removeAll()
  }

  private func mutate<T>(
    _ session: RemoteNodeSessionDTO,
    _ body: (inout Entry) -> T
  ) -> T {
    let key = NodeKey(radioID: session.radioID, publicKey: session.publicKey)
    var entry = entries[key] ?? Entry()
    let value = body(&entry)
    entries[key] = entry
    return value
  }
}
