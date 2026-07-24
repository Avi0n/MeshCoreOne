import Foundation
import MeshCore

/// Advertisement and discovery notifications broadcast by `AdvertisementService`.
///
/// Subscribe via `AdvertisementService.events()`. The stream is multicast:
/// every subscriber receives every event, so coexisting consumers
/// (`SyncCoordinator` for discovery notifications, `AppState` for version
/// bumps and deletion cleanup, `ConnectionUIState` for the storage-full flag,
/// path-discovery views) never steal each other's events.
public enum AdvertisementEvent: Sendable {
  /// A contact or discovered node was created or updated; observers should
  /// reload contact lists.
  case contactUpdated
  /// A new contact was discovered via advertisement.
  case newContactDiscovered(name: String, contactID: UUID, contactType: ContactType)
  /// The device's node storage full state changed (true = full, false = has space).
  case nodeStorageFullChanged(isFull: Bool)
  /// The device auto-deleted a contact (overwrite oldest); observers clean
  /// up its notifications and refresh the badge.
  case contactDeletedCleanup(contactID: UUID, publicKey: Data)
  /// A path discovery response arrived for a contact.
  case pathDiscoveryResponse(PathInfo)
  /// A trace response arrived; `traceInfo.tag` correlates it with the
  /// trace that requested it.
  case traceResponse(traceInfo: TraceInfo, radioID: UUID)
  /// The RX log reported reception of a trace packet, carrying the SNR the
  /// local radio measured and, when present, the far end's measured SNR.
  case traceSnrObserved(tag: UInt32, localSnr: Double, remoteSnr: Double?, radioID: UUID)
}
