import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Outgoing repeat details", .serialized)
@MainActor
struct OutgoingRepeatDetailsTests {
  fileprivate static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
  private static let localRadioName = "Local Radio"

  @Test(arguments: [true, false])
  func `expanded details keep every hearing and show the selected path`(resolveRepeaters: Bool) async throws {
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
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let repeats = [
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 6.5, rssi: -92, receivedAt: start),
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 4.2, rssi: -101, receivedAt: start.addingTimeInterval(3)),
      makeRepeat(
        messageID: message.id,
        pathNodes: Data([0xA3, 0xB4]),
        snr: 1.9,
        rssi: -108,
        receivedAt: start.addingTimeInterval(8)
      )
    ]
    let viewModel = MessagePathViewModel()
    viewModel.contacts = resolveRepeaters ? [
      makeRepeater(radioID: device.radioID, hash: 0xA3, name: "Ridge"),
      makeRepeater(radioID: device.radioID, hash: 0xB4, name: "Valley")
    ] : []
    viewModel.repeaters = viewModel.contacts
    viewModel.isLoading = false
    let host = try HostedWindow(root: details(message: message, repeats: repeats, viewModel: viewModel, appState: appState))
    defer {
      host.close()
      appState.shutdown()
    }

    try await waitUntil("Repeat details did not render") {
      host.layout()
      return accessibilityNodes(in: host.window).contains {
        $0.accessibilityLabel == L10n.Chats.Chats.Message.Action.repeatDetails
      }
    }

    let firstHopName = resolveRepeaters ? "Ridge" : L10n.Chats.Chats.Path.Hop.unknown
    let secondHopName = resolveRepeaters ? "Valley" : L10n.Chats.Chats.Path.Hop.unknown
    let labels = accessibilityNodes(in: host.window).compactMap(\.accessibilityLabel)
    let earliestTitle = pillTitle(hops: 1, snr: 6.5)
    #expect(labels.contains([earliestTitle, L10n.Chats.Chats.Path.Arrival.first].joined(separator: ", ")))
    #expect(labels.contains { $0.contains(pillTitle(hops: 1, snr: 4.2)) })
    #expect(labels.contains { $0.contains(pillTitle(hops: 2, snr: 1.9)) })
    #expect(labels.contains(endpointLabel(L10n.Chats.Chats.Path.Hop.sender, Self.localRadioName)))
    #expect(labels.contains(hopRowLabel(index: 1, name: firstHopName)))
    #expect(!labels.contains { $0.contains(L10n.Chats.Chats.Path.Hop.number(2)) })

    let receiver = try #require(accessibilityNodes(in: host.window).first {
      $0.accessibilityLabel == endpointLabel(L10n.Chats.Chats.Path.Receiver.label, Self.localRadioName)
    })
    let receiverValue = try #require(receiver.accessibilityValue)
    #expect(receiverValue.contains(L10n.Chats.Chats.Path.Hop.rssi(-92)))

    let twoHopPill = try #require(accessibilityNodes(in: host.window).first {
      $0.accessibilityLabel?.contains(pillTitle(hops: 2, snr: 1.9)) == true
    })
    try #require(twoHopPill.accessibilityActivate())

    try await waitUntil("The two-hop path did not replace the selected hearing") {
      host.layout()
      let updated = accessibilityNodes(in: host.window).compactMap(\.accessibilityLabel)
      return updated.contains(hopRowLabel(index: 2, name: secondHopName))
    }
    let selected = accessibilityNodes(in: host.window).compactMap(\.accessibilityLabel)
    #expect(selected.contains(hopRowLabel(index: 1, name: firstHopName)))
    #expect(selected.contains(hopRowLabel(index: 2, name: secondHopName)))
  }

  @Test
  func `a sent message with repeats still loading shows a spinner`() async throws {
    let (appState, message, viewModel) = try makeOutgoingMessage()
    let host = try HostedWindow(root: details(message: message, repeats: nil, viewModel: viewModel, appState: appState))
    defer {
      host.close()
      appState.shutdown()
    }

    try await waitUntil("The repeat spinner did not appear") {
      host.layout()
      return containsActivityIndicator(in: host.window)
    }
    let labels = accessibilityNodes(in: host.window).compactMap(\.accessibilityLabel)
    #expect(!labels.contains(L10n.Chats.Chats.Repeats.EmptyState.title))
  }

  @Test
  func `a sent message with no stored repeats shows the empty state`() async throws {
    let (appState, message, viewModel) = try makeOutgoingMessage()
    let host = try HostedWindow(root: details(message: message, repeats: [], viewModel: viewModel, appState: appState))
    defer {
      host.close()
      appState.shutdown()
    }

    let emptyTitle = L10n.Chats.Chats.Repeats.EmptyState.title
    try await waitUntil("The empty repeat state did not appear") {
      host.layout()
      return accessibilityNodes(in: host.window).contains { $0.accessibilityLabel?.contains(emptyTitle) == true }
    }
    #expect(!containsActivityIndicator(in: host.window))
  }

  @Test
  func `a receiver row omits RSSI when the hearing has none`() async throws {
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
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let repeats = [
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 6.5, rssi: nil, receivedAt: start),
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 4.2, rssi: -101, receivedAt: start.addingTimeInterval(3)),
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3, 0xB4]), snr: 1.9, rssi: -108, receivedAt: start.addingTimeInterval(8))
    ]
    let viewModel = MessagePathViewModel()
    viewModel.contacts = []
    viewModel.repeaters = []
    viewModel.isLoading = false
    let host = try HostedWindow(root: details(message: message, repeats: repeats, viewModel: viewModel, appState: appState))
    defer {
      host.close()
      appState.shutdown()
    }

    try await waitUntil("Repeat details did not render") {
      host.layout()
      return accessibilityNodes(in: host.window).contains {
        $0.accessibilityLabel == L10n.Chats.Chats.Message.Action.repeatDetails
      }
    }

    let receiverLabel = endpointLabel(L10n.Chats.Chats.Path.Receiver.label, Self.localRadioName)
    let value = try #require(accessibilityNodes(in: host.window).first {
      $0.accessibilityLabel == receiverLabel
    }?.accessibilityValue)
    #expect(!value.contains("RSSI"))
  }

  @Test
  func `a fallback hop keeps its explanation separately accessible`() async throws {
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
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let viewModel = MessagePathViewModel()
    viewModel.contacts = [
      makeRepeater(radioID: device.radioID, hash: 0xA3, name: "Ridge", distinct: 1),
      makeRepeater(radioID: device.radioID, hash: 0xA3, name: "Valley", distinct: 2)
    ]
    viewModel.repeaters = viewModel.contacts
    viewModel.isLoading = false
    let repeats = [
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 6.5, rssi: -92, receivedAt: start),
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 4.2, rssi: -101, receivedAt: start.addingTimeInterval(3)),
      makeRepeat(messageID: message.id, pathNodes: Data([0xA3]), snr: 1.9, rssi: -108, receivedAt: start.addingTimeInterval(8))
    ]
    let host = try HostedWindow(root: details(message: message, repeats: repeats, viewModel: viewModel, appState: appState))
    defer {
      host.close()
      appState.shutdown()
    }

    try await waitUntil("The possible-match button did not render") {
      host.layout()
      return accessibilityNodes(in: host.window).contains {
        $0.isAccessibilityElement
          && $0.accessibilityLabel == L10n.Chats.Chats.Path.Hop.possibleMatch
          && $0.accessibilityTraits.contains(.button)
      }
    }
    let indicator = try #require(accessibilityNodes(in: host.window).first {
      $0.accessibilityLabel == L10n.Chats.Chats.Path.Hop.possibleMatch
        && $0.accessibilityTraits.contains(.button)
    })
    try #require(indicator.accessibilityActivate())

    try await waitUntil("The possible-match explanation did not appear") {
      host.layout()
      let labels = sceneNodes(around: host.window).compactMap(\.accessibilityLabel)
      return labels.contains(L10n.Chats.Chats.Path.Hop.possibleMatchTitle)
        && labels.contains(L10n.Chats.Chats.Path.Hop.possibleMatchExplanation)
    }
  }

  private func details(
    message: MessageDTO,
    repeats: [MessageRepeatDTO]?,
    viewModel: MessagePathViewModel,
    appState: AppState
  ) -> some View {
    ActionsDetailsSection(
      message: message,
      availability: MessageActionAvailability(message: message),
      isDetailExpanded: .constant(true),
      repeats: repeats,
      pathViewModel: viewModel
    )
    .environment(\.appState, appState)
    .environment(\.locale, Locale(identifier: "en_US"))
    .environment(\.accessibilityEnabled, true)
  }

  private func makeOutgoingMessage() throws -> (AppState, MessageDTO, MessagePathViewModel) {
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
      heardRepeats: 1
    ))
    let viewModel = MessagePathViewModel()
    viewModel.isLoading = false
    return (appState, message, viewModel)
  }

  private func pillTitle(hops: Int, snr: Double) -> String {
    let hopText = hops == 1
      ? L10n.Chats.Chats.Repeats.Hop.singular
      : L10n.Chats.Chats.Repeats.Hop.plural(hops)
    let snrText = snr.formatted(.number.precision(.fractionLength(1)))
      + " "
      + L10n.RemoteNodes.RemoteNodes.Status.snrBadgeUnit
    return "\(hopText) · \(snrText)"
  }

  private func hopRowLabel(index: Int, name: String) -> String {
    "\(L10n.Chats.Chats.Path.Hop.number(index)): \(name)"
  }

  private func endpointLabel(_ role: String, _ name: String) -> String {
    "\(role): \(name)"
  }

  private func makeRepeat(
    messageID: UUID,
    pathNodes: Data,
    snr: Double,
    rssi: Int?,
    receivedAt: Date
  ) -> MessageRepeatDTO {
    MessageRepeatDTO(
      messageID: messageID,
      receivedAt: receivedAt,
      pathNodes: pathNodes,
      pathLength: encodePathLen(hashSize: 1, hopCount: pathNodes.count),
      snr: snr,
      rssi: rssi,
      rxLogEntryID: nil
    )
  }

  private func makeRepeater(radioID: UUID, hash: UInt8, name: String, distinct: UInt8 = 0) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data([hash, distinct] + Array(repeating: UInt8(0), count: ProtocolLimits.publicKeySize - 2)),
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

  private func sceneNodes(around window: UIWindow) -> [NSObject] {
    let windows = window.windowScene?.windows ?? [window]
    return windows.flatMap { accessibilityNodes(in: $0) }
  }

  private func accessibilityNodes(in root: NSObject) -> [NSObject] {
    var visited: Set<ObjectIdentifier> = []
    var result: [NSObject] = []
    func walk(_ object: NSObject) {
      guard visited.insert(ObjectIdentifier(object)).inserted else { return }
      if let view = object as? UIView, view.isHidden || view.accessibilityElementsHidden { return }
      if object.isAccessibilityElement { result.append(object) }
      if let children = object.accessibilityElements {
        for case let child as NSObject in children where child !== object {
          walk(child)
        }
      }
      if let children = object.automationElements {
        for case let child as NSObject in children where child !== object {
          walk(child)
        }
      }
      let count = object.accessibilityElementCount()
      if count != NSNotFound, count > 0 {
        for index in 0..<count {
          if let child = object.accessibilityElement(at: index) as? NSObject, child !== object { walk(child) }
        }
      }
      if let view = object as? UIView { view.subviews.forEach(walk) }
    }
    walk(root)
    return result
  }

  private func containsActivityIndicator(in view: UIView) -> Bool {
    if view is UIActivityIndicatorView || view is UIProgressView { return true }
    return view.subviews.contains { containsActivityIndicator(in: $0) }
  }
}

@MainActor
private struct HostedWindow {
  let window: UIWindow
  let controller: UIViewController
  private let previousKeyWindow: UIWindow?

  init(root: some View) throws {
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    previousKeyWindow = scene.keyWindow
    controller = UIHostingController(rootView: root)
    window = UIWindow(windowScene: scene)
    window.frame = OutgoingRepeatDetailsTests.viewport
    window.rootViewController = controller
    window.makeKeyAndVisible()
  }

  func layout() {
    window.layoutIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.01))
  }

  func close() {
    controller.dismiss(animated: false)
    window.isHidden = true
    window.rootViewController = nil
    window.windowScene = nil
    previousKeyWindow?.makeKey()
  }
}
