import Foundation
import MC1Services

extension AppState {
  func connectionSnapshot(for radioID: UUID) -> ConnectionSnapshot? {
    guard let snapshot = connectionManager.currentConnection,
          snapshot.device.radioID == radioID,
          connectionManager.isCurrent(snapshot),
          snapshot.services.isAcceptingRuntimeWork else { return nil }
    return snapshot
  }
}
