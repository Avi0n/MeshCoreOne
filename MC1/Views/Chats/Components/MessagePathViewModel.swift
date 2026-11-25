import CoreLocation
import MapKit
import MC1Services
import OSLog
import SwiftUI
import UIKit

@Observable
@MainActor
final class MessagePathViewModel {
  var contacts: [ContactDTO] = []
  var repeaters: [ContactDTO] = []
  var discoveredRepeaters: [DiscoveredNodeDTO] = []
  var isLoading = true
  private(set) var lastPreviewImage: UIImage?

  typealias PathPreviewRenderer = @MainActor (
    _ points: [MapPoint],
    _ line: MapLine?,
    _ region: MKCoordinateRegion?,
    _ size: CGSize,
    _ isDark: Bool,
    _ isOffline: Bool
  ) async -> UIImage?

  private var previewImages: [MessagePathPreviewSnapshot.Key: UIImage] = [:]
  private var failedPreviewKeys: Set<MessagePathPreviewSnapshot.Key> = []
  private let renderer: PathPreviewRenderer
  private let logger = Logger(subsystem: "com.mc1", category: "MessagePathViewModel")

  init(renderPreview: PathPreviewRenderer? = nil) {
    renderer = renderPreview ?? { points, line, region, size, isDark, isOffline in
      await MapSnapshotRenderer().render(
        points: points,
        line: line,
        region: region,
        size: size,
        isDark: isDark,
        isOffline: isOffline
      )
    }
  }

  func loadContacts(dataStore: DataStore?, radioID: UUID) async {
    isLoading = true
    guard let dataStore else {
      contacts = []
      repeaters = []
      discoveredRepeaters = []
      isLoading = false
      return
    }

    do {
      let fetched = try await dataStore.fetchContacts(radioID: radioID)
      contacts = fetched
      repeaters = fetched.filter { $0.type == .repeater }
      let nodes = try await dataStore.fetchDiscoveredNodes(radioID: radioID)
      discoveredRepeaters = nodes.filter { $0.nodeType == .repeater }
    } catch {
      logger.error("Failed to load contacts: \(error.localizedDescription)")
      contacts = []
      repeaters = []
      discoveredRepeaters = []
    }

    isLoading = false
  }

  func senderResolution(for sender: MessagePathSender, localDeviceName: String) -> NodeNameResolution {
    if sender.isLocalDevice {
      return NodeNameResolution(displayName: localDeviceName, matchKind: .exact)
    }

    if let nodeName = sender.channelSenderName {
      return NodeNameResolution(displayName: nodeName, matchKind: .exact)
    }

    if let keyPrefix = sender.keyPrefix,
       let result = NeighborNameResolver.resolve(
         for: keyPrefix,
         contacts: contacts,
         discoveredNodes: [],
         userLocation: nil
       ) {
      return result
    }

    return NodeNameResolution(
      displayName: L10n.Chats.Chats.Path.Hop.unknown,
      matchKind: .unresolved
    )
  }

  func senderName(for sender: MessagePathSender, localDeviceName: String) -> String {
    senderResolution(for: sender, localDeviceName: localDeviceName).displayName
  }

  func senderNodeID(for sender: MessagePathSender) -> String? {
    guard let keyPrefix = sender.keyPrefix,
          let firstByte = keyPrefix.first else { return nil }
    return String(format: "%02X", firstByte)
  }

  /// Pin A contact from a non-empty `keyPrefix`, or a unique channel sender name.
  func locatedSender(for sender: MessagePathSender) -> ContactDTO? {
    if let keyPrefix = sender.keyPrefix, !keyPrefix.isEmpty {
      guard let contact = contacts.first(where: { $0.publicKeyPrefix == keyPrefix }),
            contact.hasLocation else {
        return nil
      }
      return contact
    }

    guard let senderName = sender.channelSenderName, !senderName.isEmpty else {
      return nil
    }

    let matches = SenderContactMatcher.filter(contacts: contacts, senderName: senderName)
    guard matches.count == 1, let contact = matches.first, contact.hasLocation else {
      return nil
    }
    return contact
  }

  func repeaterResolution(for hashBytes: Data, userLocation: CLLocation?) -> NodeNameResolution {
    NeighborNameResolver.resolve(
      for: hashBytes,
      contacts: repeaters,
      discoveredNodes: discoveredRepeaters,
      userLocation: userLocation
    ) ?? NodeNameResolution(
      displayName: L10n.Chats.Chats.Path.Hop.unknown,
      matchKind: .unresolved
    )
  }

  func repeaterName(for hashBytes: Data, userLocation: CLLocation?) -> String {
    repeaterResolution(for: hashBytes, userLocation: userLocation).displayName
  }

  func previewImage(for key: MessagePathPreviewSnapshot.Key) -> UIImage? {
    previewImages[key]
  }

  func previewFailed(for key: MessagePathPreviewSnapshot.Key) -> Bool {
    failedPreviewKeys.contains(key)
  }

  /// Renders the selected arrival on its own task, so a growing arrival list cannot cancel it.
  func renderSelectedPreview(
    arrivalID: UUID,
    sender: MessagePathSender,
    arrivals: [MessagePathArrival],
    connectedDevice: DeviceDTO?,
    userLocation: CLLocation?,
    isDark: Bool,
    isOffline: Bool,
    previewWidth: CGFloat
  ) async {
    guard !isLoading else { return }
    await renderPreview(
      arrivalID: arrivalID,
      sender: sender,
      arrivals: arrivals,
      connectedDevice: connectedDevice,
      userLocation: userLocation,
      isDark: isDark,
      isOffline: isOffline,
      previewWidth: previewWidth,
      force: false
    )
  }

  /// Renders every arrival except `skippedID`, in order, until cancelled.
  func prefetchPreviews(
    sender: MessagePathSender,
    arrivals: [MessagePathArrival],
    skipping skippedID: UUID?,
    connectedDevice: DeviceDTO?,
    userLocation: CLLocation?,
    isDark: Bool,
    isOffline: Bool,
    previewWidth: CGFloat
  ) async {
    guard !isLoading else { return }
    for arrival in arrivals where arrival.id != skippedID {
      guard !Task.isCancelled else { return }
      await renderPreview(
        arrivalID: arrival.id,
        sender: sender,
        arrivals: arrivals,
        connectedDevice: connectedDevice,
        userLocation: userLocation,
        isDark: isDark,
        isOffline: isOffline,
        previewWidth: previewWidth,
        force: false
      )
    }
  }

  func retryPreview(
    arrivalID: UUID,
    sender: MessagePathSender,
    arrivals: [MessagePathArrival],
    connectedDevice: DeviceDTO?,
    userLocation: CLLocation?,
    isDark: Bool,
    isOffline: Bool,
    previewWidth: CGFloat
  ) async {
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: arrivalID,
      isDark: isDark,
      isOffline: isOffline,
      previewWidth: previewWidth
    )
    failedPreviewKeys.remove(key)
    await renderPreview(
      arrivalID: arrivalID,
      sender: sender,
      arrivals: arrivals,
      connectedDevice: connectedDevice,
      userLocation: userLocation,
      isDark: isDark,
      isOffline: isOffline,
      previewWidth: previewWidth,
      force: true
    )
  }

  private func renderPreview(
    arrivalID: UUID,
    sender: MessagePathSender,
    arrivals: [MessagePathArrival],
    connectedDevice: DeviceDTO?,
    userLocation: CLLocation?,
    isDark: Bool,
    isOffline: Bool,
    previewWidth: CGFloat,
    force: Bool
  ) async {
    let size = MessagePathPreviewSnapshot.previewSize(previewWidth: previewWidth)
    guard size.width >= 1, arrivals.contains(where: { $0.id == arrivalID }) else { return }
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: arrivalID,
      isDark: isDark,
      isOffline: isOffline,
      previewWidth: previewWidth
    )
    if !force, previewImages[key] != nil { return }

    let canvas = MessagePathMapView.canvasModel(
      sender: sender,
      arrivals: arrivals,
      selectedID: arrivalID,
      pathViewModel: self,
      connectedDevice: connectedDevice,
      userLocation: userLocation
    )
    guard canvas.showsPathMap else { return }

    let image = await renderer(
      canvas.points,
      canvas.lines.first,
      canvas.cameraRegion,
      size,
      isDark,
      isOffline
    )
    // A cancelled render returns nil; that is not a failure for this key.
    guard !Task.isCancelled else { return }
    if let image {
      previewImages[key] = image
      lastPreviewImage = image
      failedPreviewKeys.remove(key)
    } else {
      failedPreviewKeys.insert(key)
    }
  }
}
