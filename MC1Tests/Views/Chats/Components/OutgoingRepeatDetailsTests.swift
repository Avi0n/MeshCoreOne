import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Outgoing repeat details", .serialized)
@MainActor
struct OutgoingRepeatDetailsTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
  private static let localRadioName = "Local Radio"

  @Test(arguments: [true, false])
  func `expanded details expose each hearing's identity hops and signal`(resolveRepeaters: Bool) async throws {
    let preferences = IsolatedUserDefaults()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let appState = AppState(modelContainer: container, isPlaceholder: true, defaults: preferences.defaults)
    let device = Device(publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize), nodeName: Self.localRadioName)
    appState.connectionManager.setTestState(connectedDevice: .some(DeviceDTO(from: device)))
    let message = MessageDTO(from: Message(
      radioID: device.radioID,
      channelIndex: 0,
      text: "Net check",
      directionRawValue: MessageDirection.outgoing.rawValue,
      statusRawValue: MessageStatus.sent.rawValue,
      heardRepeats: 3
    ))
    let repeats = [
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 6.5, rssi: -92),
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 4.2, rssi: -101),
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3, 0xB4]), snr: 1.9, rssi: -108)
    ]
    let viewModel = MessagePathViewModel()
    viewModel.contacts = resolveRepeaters ? [
      makeRepeater(radioID: device.radioID, hash: 0xA3, name: "Ridge"),
      makeRepeater(radioID: device.radioID, hash: 0xB4, name: "Valley")
    ] : []
    viewModel.repeaters = viewModel.contacts
    viewModel.isLoading = false
    let root = ActionsDetailsSection(
      message: message,
      availability: MessageActionAvailability(message: message),
      isDetailExpanded: .constant(true),
      repeats: repeats,
      pathViewModel: viewModel
    )
    .environment(\.appState, appState)
    .environment(\.locale, Locale(identifier: "en_US"))
    let controller = UIHostingController(rootView: root)
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let previousKeyWindow = scene.keyWindow
    let window = UIWindow(windowScene: scene)
    window.frame = Self.viewport
    window.rootViewController = controller
    window.makeKeyAndVisible()
    defer {
      window.isHidden = true
      window.rootViewController = nil
      window.windowScene = nil
      previousKeyWindow?.makeKey()
      appState.shutdown()
    }

    try await waitUntil("Repeat details did not render") {
      window.layoutIfNeeded()
      return accessibilityNodes(in: window).contains {
        $0.accessibilityLabel == L10n.Chats.Chats.Message.Action.repeatDetails
      }
    }

    let nodes = accessibilityNodes(in: window)
    let names = resolveRepeaters
      ? ["Ridge", "Ridge", "Valley"]
      : Array(repeating: L10n.Chats.Chats.Repeats.unknownRepeater, count: repeats.count)
    let labels = Set(names.map { L10n.Chats.Chats.Repeats.Row.accessibility($0) })
    let rows = nodes.filter { labels.contains($0.accessibilityLabel ?? "") }
    #expect(rows.count == repeats.count)
    #expect(!nodes.contains { $0.accessibilityLabel?.contains(Self.localRadioName) == true })

    for (repeatEntry, name) in zip(repeats, names) {
      let label = L10n.Chats.Chats.Repeats.Row.accessibility(name)
      let signalValue = L10n.Chats.Chats.Repeats.Row.accessibilityValue(
        repeatEntry.snrQuality.localizedLabel,
        repeatEntry.snrFormatted,
        repeatEntry.rssiFormatted
      )
      let row = try #require(rows.first { $0.accessibilityLabel == label && $0.accessibilityValue?.contains(signalValue) == true })
      let value = try #require(row.accessibilityValue)
      #expect(value.contains(L10n.Chats.Chats.Path.Hop.nodeId(repeatEntry.repeaterHashFormatted)))
      let hops = repeatEntry.hopCount == 1
        ? L10n.Chats.Chats.Repeats.Hop.singular
        : L10n.Chats.Chats.Repeats.Hop.plural(repeatEntry.hopCount)
      #expect(value.contains(hops))
    }
  }

  private func makeRepeat(messageID: UUID, pathNodes: Data, snr: Double, rssi: Int) -> MessageRepeatDTO {
    MessageRepeatDTO(
      messageID: messageID,
      receivedAt: Date(),
      pathNodes: pathNodes,
      pathLength: encodePathLen(hashSize: 1, hopCount: pathNodes.count),
      snr: snr,
      rssi: rssi,
      rxLogEntryID: nil
    )
  }

  private func makeRepeater(radioID: UUID, hash: UInt8, name: String) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data([hash] + Array(repeating: UInt8(0), count: ProtocolLimits.publicKeySize - 1)),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func accessibilityNodes(in root: NSObject) -> [NSObject] {
    var visited: Set<ObjectIdentifier> = []
    var result: [NSObject] = []
    func walk(_ object: NSObject) {
      guard visited.insert(ObjectIdentifier(object)).inserted else { return }
      if let view = object as? UIView, view.isHidden || view.accessibilityElementsHidden { return }
      if object.isAccessibilityElement { result.append(object) }
      if let view = object as? UIView { view.subviews.forEach(walk) }
      let count = object.accessibilityElementCount()
      if count != NSNotFound, count > 0 {
        for index in 0..<count {
          if let child = object.accessibilityElement(at: index) as? NSObject { walk(child) }
        }
      }
    }
    walk(root)
    return result
  }
}
