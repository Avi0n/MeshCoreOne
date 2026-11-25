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
    switch sender {
    case .localDevice:
      return NodeNameResolution(displayName: localDeviceName, matchKind: .exact)
    case let .channelName(nodeName):
      return NodeNameResolution(displayName: nodeName, matchKind: .exact)
    case let .contact(keyPrefix):
      if let result = NeighborNameResolver.resolve(
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
    case .unknown:
      return NodeNameResolution(
        displayName: L10n.Chats.Chats.Path.Hop.unknown,
        matchKind: .unresolved
      )
    }
  }

  func senderName(for sender: MessagePathSender, localDeviceName: String) -> String {
    senderResolution(for: sender, localDeviceName: localDeviceName).displayName
  }

  func senderNodeID(for sender: MessagePathSender) -> String? {
    guard case let .contact(keyPrefix) = sender,
          let firstByte = keyPrefix.first else { return nil }
    return String(format: "%02X", firstByte)
  }

  /// Pin A contact when `keyPrefix` equals a located contact's public-key prefix, or when one contact matches the channel sender name.
  /// A shorter prefix stays unpinned so a hash collision does not place a false pin.
  func locatedSender(for sender: MessagePathSender) -> ContactDTO? {
    switch sender {
    case let .contact(keyPrefix):
      guard !keyPrefix.isEmpty,
            let contact = contacts.first(where: { $0.publicKeyPrefix == keyPrefix }),
            contact.hasLocation else {
        return nil
      }
      return contact
    case let .channelName(senderName):
      guard !senderName.isEmpty else { return nil }
      let matches = SenderContactMatcher.filter(contacts: contacts, senderName: senderName)
      guard matches.count == 1, let contact = matches.first, contact.hasLocation else {
        return nil
      }
      return contact
    case .localDevice, .unknown:
      return nil
    }
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
    for arrival: MessagePathArrival,
    context: MessagePathPreviewSnapshot.Context
  ) async {
    guard !isLoading else { return }
    await renderPreview(for: arrival, context: context, force: false)
  }

  /// Renders every arrival except `skippedID`, in order, until cancelled.
  func prefetchPreviews(
    for arrivals: [MessagePathArrival],
    skipping skippedID: UUID?,
    context: MessagePathPreviewSnapshot.Context
  ) async {
    guard !isLoading else { return }
    for arrival in arrivals where arrival.id != skippedID {
      guard !Task.isCancelled else { return }
      await renderPreview(for: arrival, context: context, force: false)
    }
  }

  func retryPreview(
    for arrival: MessagePathArrival,
    context: MessagePathPreviewSnapshot.Context
  ) async {
    failedPreviewKeys.remove(context.key(for: arrival.id))
    await renderPreview(for: arrival, context: context, force: true)
  }

  private func renderPreview(
    for arrival: MessagePathArrival,
    context: MessagePathPreviewSnapshot.Context,
    force: Bool
  ) async {
    let size = context.size
    guard size.width >= 1 else { return }
    let key = context.key(for: arrival.id)
    if !force, previewImages[key] != nil { return }

    let canvas = MessagePathMapView.canvasModel(
      sender: context.sender,
      arrival: arrival,
      pathViewModel: self,
      connectedDevice: context.connectedDevice,
      userLocation: context.userLocation
    )
    guard canvas.showsPathMap else { return }

    let image = await renderer(
      canvas.points,
      canvas.lines.first,
      canvas.cameraRegion,
      size,
      context.isDark,
      context.isOffline
    )
    // A cancelled render returns nil; that is not a failure for this key.
    guard !Task.isCancelled else { return }
    if let image {
      previewImages[key] = image
      failedPreviewKeys.remove(key)
    } else {
      failedPreviewKeys.insert(key)
    }
  }
}
