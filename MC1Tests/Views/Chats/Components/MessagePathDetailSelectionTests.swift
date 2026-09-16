import CoreLocation
import Foundation
import MapKit
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Message path detail selection")
struct MessagePathDetailSelectionTests {
  @Test
  func `resolvedSelection prefers the current id`() {
    let arrivals = makeArrivals(count: 3)
    let current = arrivals[1].id
    #expect(MessagePathArrivals.resolvedSelection(preferred: current, arrivals: arrivals) == current)
  }

  @Test
  func `resolvedSelection falls back to first when current vanishes`() {
    let arrivals = makeArrivals(count: 2)
    let vanished = UUID()
    #expect(
      MessagePathArrivals.resolvedSelection(preferred: vanished, arrivals: arrivals)
        == arrivals[0].id
    )
  }

  @Test
  func `resolvedSelection uses first when preferred is nil`() {
    let arrivals = makeArrivals(count: 2)
    #expect(
      MessagePathArrivals.resolvedSelection(preferred: nil, arrivals: arrivals)
        == arrivals[0].id
    )
  }

  @Test
  func `resolvedSelection is nil when arrivals are empty`() {
    #expect(MessagePathArrivals.resolvedSelection(preferred: UUID(), arrivals: []) == nil)
  }

  @Test
  @MainActor
  func `canvasModel point ids stay stable across rebuilds`() {
    let message = MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "flood",
      timestamp: 1,
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      pathNodes: Data(),
      senderKeyPrefix: nil,
      senderNodeName: "Alice",
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
    let location = CLLocation(latitude: 37.5, longitude: -122.3)
    let first = MessagePathMapView.canvasModel(
      message: message,
      arrivals: MessagePathArrivals.assemble(message: message, repeats: []),
      selectedID: nil,
      pathViewModel: MessagePathViewModel(),
      connectedDevice: nil,
      userLocation: location
    )
    let second = MessagePathMapView.canvasModel(
      message: message,
      arrivals: MessagePathArrivals.assemble(message: message, repeats: []),
      selectedID: nil,
      pathViewModel: MessagePathViewModel(),
      connectedDevice: nil,
      userLocation: location
    )
    #expect(first.points.map(\.id) == second.points.map(\.id))
    #expect(!first.points.isEmpty)
    #expect(first.locatedCount >= 1)
  }

  @Test
  @MainActor
  func `canvasModel outgoing with located device includes point A at the device coordinate`() {
    let deviceLatitude = 51.5074
    let deviceLongitude = -0.1278
    let device = DeviceDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "Radio",
      firmwareVersion: 8,
      firmwareVersionString: "v1.11.0",
      manufacturerName: "TestMfg",
      buildDate: "01 Jan 2025",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: deviceLatitude,
      longitude: deviceLongitude,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: []
    )
    let message = MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "out",
      timestamp: 1,
      createdAt: Date(),
      direction: .outgoing,
      status: .sent,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      pathNodes: Data(),
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
    let canvas = MessagePathMapView.canvasModel(
      message: message,
      arrivals: MessagePathArrivals.assemble(message: message, repeats: []),
      selectedID: nil,
      pathViewModel: MessagePathViewModel(),
      connectedDevice: device,
      userLocation: nil
    )
    let pinA = canvas.points.first { $0.pinStyle == .pointA }
    #expect(pinA != nil)
    #expect(pinA?.id == device.id)
    #expect(pinA?.coordinate.latitude == deviceLatitude)
    #expect(pinA?.coordinate.longitude == deviceLongitude)
    #expect(pinA?.label == device.nodeName)
    #expect(pinA?.isClusterable == false)
    #expect(canvas.lines.isEmpty)
  }

  @Test
  @MainActor
  func `canvasModel outgoing line starts at the located device`() {
    let deviceLatitude = 51.5074
    let deviceLongitude = -0.1278
    let hopLatitude = 51.51
    let hopLongitude = -0.14
    let device = DeviceDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "Radio",
      firmwareVersion: 8,
      firmwareVersionString: "v1.11.0",
      manufacturerName: "TestMfg",
      buildDate: "01 Jan 2025",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: deviceLatitude,
      longitude: deviceLongitude,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: []
    )
    let hop = locatedContact(
      prefix: 0xAA,
      name: "HopA",
      type: .repeater,
      latitude: hopLatitude,
      longitude: hopLongitude
    )
    let message = MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "out",
      timestamp: 1,
      createdAt: Date(),
      direction: .outgoing,
      status: .sent,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      pathNodes: Data(),
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 1,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
    let echo = MessageRepeatDTO(
      messageID: message.id,
      receivedAt: Date(),
      pathNodes: Data([0xAA]),
      pathLength: 1,
      snr: 4.0,
      rssi: -80,
      rxLogEntryID: nil
    )
    let pathViewModel = MessagePathViewModel()
    pathViewModel.contacts = [hop]
    pathViewModel.repeaters = [hop]
    let canvas = MessagePathMapView.canvasModel(
      message: message,
      arrivals: MessagePathArrivals.assemble(message: message, repeats: [echo]),
      selectedID: nil,
      pathViewModel: pathViewModel,
      connectedDevice: device,
      userLocation: CLLocation(latitude: 51.0, longitude: -0.2)
    )
    #expect(canvas.lines.count == 1)
    let coords = canvas.lines.first?.coordinates ?? []
    #expect(coords.first?.latitude == deviceLatitude)
    #expect(coords.first?.longitude == deviceLongitude)
    #expect(coords.contains { $0.latitude == hopLatitude && $0.longitude == hopLongitude })
  }

  @Test
  @MainActor
  func `canvasModel camera follows the selected arrival`() {
    let fixture = makeTwoLocatedArrivals()
    let first = canvasModel(for: fixture, selectedID: nil)
    let extra = canvasModel(for: fixture, selectedID: fixture.extra.id)
    #expect(first.cameraRegion?.center.latitude != extra.cameraRegion?.center.latitude)
  }

  @Test
  @MainActor
  func `canvasModel draws only the selected arrival's line and hops`() {
    let fixture = makeTwoLocatedArrivals()
    let firstCanvas = canvasModel(for: fixture, selectedID: nil)
    let extraCanvas = canvasModel(for: fixture, selectedID: fixture.extra.id)

    #expect(firstCanvas.lines.count == 1)
    #expect(firstCanvas.lines[0].id == "message-path-\(fixture.first.id)")
    #expect(firstCanvas.lines[0].opacity == 1.0)
    #expect(hopLatitudes(in: firstCanvas).contains(Self.firstHopLatitude))
    #expect(hopLatitudes(in: firstCanvas).contains(Self.extraHopLatitude) == false)
    #expect(firstCanvas.points.contains { $0.pinStyle == .pointA })
    #expect(firstCanvas.points.contains { $0.pinStyle == .pointB })
    #expect(firstCanvas.points.allSatisfy { $0.isClusterable == false })

    #expect(extraCanvas.lines.count == 1)
    #expect(extraCanvas.lines[0].id == "message-path-\(fixture.extra.id)")
    #expect(extraCanvas.lines[0].opacity == 1.0)
    #expect(hopLatitudes(in: extraCanvas).contains(Self.extraHopLatitude))
    #expect(hopLatitudes(in: extraCanvas).contains(Self.firstHopLatitude) == false)
    #expect(extraCanvas.points.contains { $0.pinStyle == .pointA })
    #expect(extraCanvas.points.contains { $0.pinStyle == .pointB })
    #expect(extraCanvas.points.allSatisfy { $0.isClusterable == false })
  }

  @Test
  @MainActor
  func `canvasModel endpoint ids survive selecting another arrival`() {
    let fixture = makeTwoLocatedArrivals()
    let first = canvasModel(for: fixture, selectedID: nil)
    let extra = canvasModel(for: fixture, selectedID: fixture.extra.id)
    let firstA = first.points.first { $0.pinStyle == .pointA }
    let extraA = extra.points.first { $0.pinStyle == .pointA }
    let firstB = first.points.first { $0.pinStyle == .pointB }
    let extraB = extra.points.first { $0.pinStyle == .pointB }
    #expect(firstA?.id == extraA?.id)
    #expect(firstB?.id == extraB?.id)
    #expect(firstA?.label == extraA?.label)
    #expect(firstB?.label == extraB?.label)
  }

  private static let firstHopLatitude = 37.11
  private static let extraHopLatitude = 38.22
  private static let senderLatitude = 36.0
  private static let receiverLatitude = 37.5
  private static let receiverLongitude = -122.3

  private struct TwoLocatedArrivals {
    let message: MessageDTO
    let arrivals: [MessagePathArrival]
    let first: MessagePathArrival
    let extra: MessagePathArrival
    let pathViewModel: MessagePathViewModel
    let userLocation: CLLocation
  }

  @MainActor
  private func makeTwoLocatedArrivals() -> TwoLocatedArrivals {
    let sender = locatedContact(
      prefix: 0x11,
      name: "Alice",
      type: .chat,
      latitude: Self.senderLatitude,
      longitude: -121.0
    )
    let firstHop = locatedContact(
      prefix: 0xAA,
      name: "HopA",
      type: .repeater,
      latitude: Self.firstHopLatitude,
      longitude: -122.1
    )
    let extraHop = locatedContact(
      prefix: 0xBB,
      name: "HopB",
      type: .repeater,
      latitude: Self.extraHopLatitude,
      longitude: -123.2
    )
    let message = MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "flood",
      timestamp: 1,
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 1,
      snr: 8.5,
      pathNodes: Data([0xAA]),
      senderKeyPrefix: nil,
      senderNodeName: "Alice",
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 1,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
    let extraRepeat = MessageRepeatDTO(
      messageID: message.id,
      receivedAt: Date().addingTimeInterval(2),
      pathNodes: Data([0xBB]),
      pathLength: 1,
      snr: 4.0,
      rssi: -80,
      rxLogEntryID: nil
    )
    let arrivals = MessagePathArrivals.assemble(message: message, repeats: [extraRepeat])
    let pathViewModel = MessagePathViewModel()
    pathViewModel.contacts = [sender, firstHop, extraHop]
    pathViewModel.repeaters = [firstHop, extraHop]
    return TwoLocatedArrivals(
      message: message,
      arrivals: arrivals,
      first: arrivals[0],
      extra: arrivals[1],
      pathViewModel: pathViewModel,
      userLocation: CLLocation(
        latitude: Self.receiverLatitude,
        longitude: Self.receiverLongitude
      )
    )
  }

  @MainActor
  private func canvasModel(
    for fixture: TwoLocatedArrivals,
    selectedID: UUID?
  ) -> MessagePathMapView.CanvasModel {
    MessagePathMapView.canvasModel(
      message: fixture.message,
      arrivals: fixture.arrivals,
      selectedID: selectedID,
      pathViewModel: fixture.pathViewModel,
      connectedDevice: nil,
      userLocation: fixture.userLocation
    )
  }

  private func hopLatitudes(in canvas: MessagePathMapView.CanvasModel) -> [Double] {
    canvas.points.filter { $0.pinStyle == .repeaterHop }.map(\.coordinate.latitude)
  }

  private func locatedContact(
    prefix: UInt8,
    name: String,
    type: ContactType,
    latitude: Double,
    longitude: Double
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([prefix] + Array(repeating: UInt8(0), count: 31)),
      name: name,
      typeRawValue: type.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: latitude,
      longitude: longitude,
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

  private func makeArrivals(count: Int) -> [MessagePathArrival] {
    (0..<count).map { index in
      MessagePathArrival(
        id: UUID(),
        pathNodes: Data([UInt8(index)]),
        pathLength: 1,
        snr: nil,
        rssi: nil,
        receivedAt: Date(timeIntervalSince1970: TimeInterval(index)),
        isFirst: index == 0
      )
    }
  }
}
