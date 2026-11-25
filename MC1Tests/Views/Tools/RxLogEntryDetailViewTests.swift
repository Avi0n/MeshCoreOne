import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import SwiftUI
import Testing
import UIKit

@Suite("RX Log entry detail", .serialized)
@MainActor
struct RxLogEntryDetailViewTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
  private static let localRadioName = "Local Radio"

  @Test
  func `direct message lists hops still to relay without a sender row`() async throws {
    let entry = makeEntry(
      routeType: .direct,
      payloadType: .textMessage,
      pathNodes: [0x12],
      packetPayload: Data([0xDD, 0xAA, 0xFF, 0xFF])
    )
    let (host, appState) = try present(entry)
    defer { close(host, appState) }

    try await waitUntil("The still-to-relay section did not render") {
      host.layout()
      return labels(in: host).contains(L10n.Tools.Tools.RxLog.stillToRelay)
    }
    #expect(!labels(in: host).contains { $0.hasPrefix("\(L10n.Chats.Chats.Path.Hop.sender):") })
  }

  @Test
  func `direct message with no hops left shows only the receiver`() async throws {
    let entry = makeEntry(
      routeType: .direct,
      payloadType: .textMessage,
      pathNodes: [],
      packetPayload: Data([0xDD, 0xAA, 0xFF, 0xFF])
    )
    let (host, appState) = try present(entry)
    defer { close(host, appState) }

    let receiverLabel = "\(L10n.Chats.Chats.Path.Receiver.label): \(Self.localRadioName)"
    try await waitUntil("The receiver row did not render") {
      host.layout()
      return labels(in: host).contains(receiverLabel)
    }
    #expect(!labels(in: host).contains(L10n.Tools.Tools.RxLog.stillToRelay))
  }

  @Test
  func `flood channel message names its sender and puts signal on the receiver`() async throws {
    let entry = makeEntry(
      routeType: .flood,
      payloadType: .groupText,
      pathNodes: [0x12],
      snr: 6.5,
      rssi: -92,
      decodedText: "Alice: hi"
    )
    let (host, appState) = try present(entry)
    defer { close(host, appState) }

    let senderLabel = "\(L10n.Chats.Chats.Path.Hop.sender): Alice"
    try await waitUntil("The sender row did not render") {
      host.layout()
      return labels(in: host).contains(senderLabel)
    }
    let receiverLabel = "\(L10n.Chats.Chats.Path.Receiver.label): \(Self.localRadioName)"
    let receiver = try #require(accessibilityNodes(in: host.window).first { $0.accessibilityLabel == receiverLabel })
    let value = try #require(receiver.accessibilityValue)
    #expect(value.contains("6.5"))
    #expect(value.contains(L10n.Chats.Chats.Path.Hop.rssi(-92)))
    #expect(!labels(in: host).contains(L10n.Tools.Tools.RxLog.stillToRelay))
  }

  @Test
  func `flood direct message leaves naming the sender to the path rows`() async throws {
    let entry = makeEntry(
      routeType: .flood,
      payloadType: .textMessage,
      pathNodes: [0x12],
      packetPayload: Data([0xDD, 0xAA, 0xFF, 0xFF])
    )
    let (host, appState) = try present(entry)
    defer { close(host, appState) }

    try await waitUntil("The sender row did not render") {
      host.layout()
      return labels(in: host).contains { $0.hasPrefix("\(L10n.Chats.Chats.Path.Hop.sender):") }
    }
    #expect(!labels(in: host).contains { $0.contains("AA → DD") })
  }

  private func present(_ entry: RxLogEntryDTO) throws -> (HostedWindow, AppState) {
    let preferences = IsolatedUserDefaults()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let appState = AppState(modelContainer: container, isPlaceholder: true, defaults: preferences.defaults)
    let device = Device(publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize), nodeName: Self.localRadioName)
    appState.connectionManager.setTestState(connectedDevice: .some(DeviceDTO(from: device)))
    let root = RxLogEntryDetailView(
      entry: entry,
      viewModel: RxLogViewModel(),
      labels: RxLogHopLabels(localPublicKeyPrefix: nil, nodeNames: [:])
    )
    .environment(\.appState, appState)
    .environment(\.locale, Locale(identifier: "en_US"))
    .environment(\.accessibilityEnabled, true)
    return try (HostedWindow(frame: Self.viewport, root: root), appState)
  }

  private func close(_ host: HostedWindow, _ appState: AppState) {
    host.close()
    appState.shutdown()
  }

  private func labels(in host: HostedWindow) -> [String] {
    accessibilityNodes(in: host.window).compactMap(\.accessibilityLabel)
  }

  private func makeEntry(
    routeType: RouteType,
    payloadType: PayloadType,
    pathNodes: [UInt8],
    packetPayload: Data = Data(),
    snr: Double? = nil,
    rssi: Int? = nil,
    decodedText: String? = nil
  ) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: snr,
      rssi: rssi,
      rawPayload: packetPayload,
      routeType: routeType,
      payloadType: payloadType,
      payloadVersion: 0,
      payloadTypeBits: payloadType.rawValue & 0x0F,
      transportCode: nil,
      pathLength: UInt8(pathNodes.count),
      pathNodes: pathNodes,
      packetPayload: packetPayload
    )
    return RxLogEntryDTO(radioID: UUID(), from: parsed, decodedText: decodedText)
  }
}
