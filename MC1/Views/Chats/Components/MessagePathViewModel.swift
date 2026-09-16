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
  /// Bumped when prefetch or retry starts so an in-flight render cannot store a stale bitmap.
  private var previewGeneration = 0
  private let renderPreview: PathPreviewRenderer
  private let logger = Logger(subsystem: "com.mc1", category: "MessagePathViewModel")

  init(renderPreview: PathPreviewRenderer? = nil) {
    self.renderPreview = renderPreview ?? { points, line, region, size, isDark, isOffline in
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

  func senderResolution(for message: MessageDTO, localDeviceName: String) -> NodeNameResolution {
    if message.isOutgoing {
      return NodeNameResolution(displayName: localDeviceName, matchKind: .exact)
    }

    if message.isChannelMessage, let nodeName = message.senderNodeName {
      return NodeNameResolution(displayName: nodeName, matchKind: .exact)
    }

    if let keyPrefix = message.senderKeyPrefix,
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

  func senderName(for message: MessageDTO, localDeviceName: String) -> String {
    senderResolution(for: message, localDeviceName: localDeviceName).displayName
  }

  func senderNodeID(for message: MessageDTO) -> String? {
    guard let keyPrefix = message.senderKeyPrefix,
          let firstByte = keyPrefix.first else { return nil }
    return String(format: "%02X", firstByte)
  }

  /// Pin A contact from a non-empty `senderKeyPrefix`, or a unique `senderNodeName` on a channel row.
  func locatedSender(for message: MessageDTO) -> ContactDTO? {
    if let keyPrefix = message.senderKeyPrefix, !keyPrefix.isEmpty {
      guard let sender = contacts.first(where: { $0.publicKeyPrefix == keyPrefix }),
            sender.hasLocation else {
        return nil
      }
      return sender
    }

    guard message.isChannelMessage,
          let senderName = message.senderNodeName, !senderName.isEmpty else {
      return nil
    }

    let matches = SenderContactMatcher.filter(contacts: contacts, senderName: senderName)
    guard matches.count == 1, let sender = matches.first, sender.hasLocation else {
      return nil
    }
    return sender
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

  func prefetchPreviews(
    message: MessageDTO,
    arrivals: [MessagePathArrival],
    selectedID: UUID?,
    connectedDevice: DeviceDTO?,
    userLocation: CLLocation?,
    isDark: Bool,
    isOffline: Bool,
    containerWidth: CGFloat
  ) async {
    let size = MessagePathPreviewSnapshot.previewSize(containerWidth: containerWidth)
    guard size.width >= 1, !isLoading else { return }

    previewGeneration += 1
    let generation = previewGeneration
    let ordered = Self.prefetchOrder(selectedID: selectedID, arrivals: arrivals)
    for arrival in ordered {
      guard generation == previewGeneration else { return }
      let key = MessagePathPreviewSnapshot.key(
        arrivalID: arrival.id,
        isDark: isDark,
        isOffline: isOffline,
        containerWidth: containerWidth
      )
      await renderPreviewIfNeeded(
        arrival: arrival,
        message: message,
        arrivals: arrivals,
        connectedDevice: connectedDevice,
        userLocation: userLocation,
        key: key,
        size: size,
        generation: generation,
        force: false
      )
    }
  }

  func retryPreview(
    arrivalID: UUID,
    message: MessageDTO,
    arrivals: [MessagePathArrival],
    connectedDevice: DeviceDTO?,
    userLocation: CLLocation?,
    isDark: Bool,
    isOffline: Bool,
    containerWidth: CGFloat
  ) async {
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: arrivalID,
      isDark: isDark,
      isOffline: isOffline,
      containerWidth: containerWidth
    )
    failedPreviewKeys.remove(key)
    let size = MessagePathPreviewSnapshot.previewSize(containerWidth: containerWidth)
    guard size.width >= 1, let arrival = arrivals.first(where: { $0.id == arrivalID }) else { return }
    previewGeneration += 1
    let generation = previewGeneration
    await renderPreviewIfNeeded(
      arrival: arrival,
      message: message,
      arrivals: arrivals,
      connectedDevice: connectedDevice,
      userLocation: userLocation,
      key: key,
      size: size,
      generation: generation,
      force: true
    )
  }

  private static func prefetchOrder(
    selectedID: UUID?,
    arrivals: [MessagePathArrival]
  ) -> [MessagePathArrival] {
    let selected = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    guard let selected else { return arrivals }
    let first = arrivals.filter { $0.id == selected }
    let rest = arrivals.filter { $0.id != selected }
    return first + rest
  }

  private func renderPreviewIfNeeded(
    arrival: MessagePathArrival,
    message: MessageDTO,
    arrivals: [MessagePathArrival],
    connectedDevice: DeviceDTO?,
    userLocation: CLLocation?,
    key: MessagePathPreviewSnapshot.Key,
    size: CGSize,
    generation: Int,
    force: Bool
  ) async {
    if !force, previewImages[key] != nil { return }

    let canvas = MessagePathMapView.canvasModel(
      message: message,
      arrivals: arrivals,
      selectedID: arrival.id,
      pathViewModel: self,
      connectedDevice: connectedDevice,
      userLocation: userLocation
    )
    guard canvas.locatedCount >= 1 else { return }

    let image = await renderPreview(
      canvas.points,
      canvas.lines.first,
      canvas.cameraRegion,
      size,
      key.isDark,
      key.isOffline
    )
    guard generation == previewGeneration else { return }
    if let image {
      previewImages[key] = image
      lastPreviewImage = image
      failedPreviewKeys.remove(key)
    } else {
      failedPreviewKeys.insert(key)
    }
  }
}
