import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("MessagePathSender from RX Log entries")
@MainActor
struct MessagePathSenderTests {
  private static let localKey = Data([0x5A] + Array(repeating: UInt8(0x11), count: 31))

  @Test
  func `advert key prefix matches the contact and places pin A`() {
    let contact = makeContact(prefix: [0xAB, 0xCD], name: "Ridge", latitude: 47.6, longitude: -122.3)
    let viewModel = MessagePathViewModel()
    viewModel.contacts = [contact]
    let sender = MessagePathSender(entry: advert(key: contact.publicKey), localPublicKey: Self.localKey)

    #expect(sender.keyPrefix == contact.publicKeyPrefix)
    #expect(sender.isLocalDevice == false)
    #expect(viewModel.locatedSender(for: sender)?.id == contact.id)
  }

  @Test
  func `own advert heard back is the local device with pin A at the radio`() {
    let device = DeviceDTO(from: Device(
      radioID: UUID(),
      publicKey: Self.localKey,
      nodeName: "Radio",
      latitude: 51.5,
      longitude: -0.12
    ))
    let sender = MessagePathSender(entry: advert(key: Self.localKey), localPublicKey: Self.localKey)
    let viewModel = MessagePathViewModel()
    let resolution = viewModel.senderResolution(for: sender, localDeviceName: "Radio")
    let canvas = MessagePathMapView.canvasModel(
      sender: sender,
      arrivals: MessagePathArrivals.assemble(entry: advert(key: Self.localKey), arrivals: []),
      selectedID: nil,
      pathViewModel: viewModel,
      connectedDevice: device,
      userLocation: nil
    )
    let pinA = canvas.points.first { $0.pinStyle == .pointA }

    #expect(sender.isLocalDevice)
    #expect(resolution.displayName == "Radio")
    #expect(pinA?.id == device.id)
    #expect(pinA?.coordinate.latitude == 51.5)
  }

  @Test
  func `a matching six-byte prefix with a different full key is not local`() {
    var otherKey = Self.localKey
    otherKey[ProtocolLimits.publicKeySize - 1] ^= 0xFF
    let sender = MessagePathSender(entry: advert(key: otherKey), localPublicKey: Self.localKey)

    #expect(sender.keyPrefix == Self.localKey.prefix(ProtocolLimits.publicKeyPrefixSize))
    #expect(sender.isLocalDevice == false)
  }

  @Test
  func `truncated advert has no sender even when its bytes match a contact`() {
    let contact = makeContact(prefix: [0xAB, 0xCD], name: "Ridge", latitude: 47.6, longitude: -122.3)
    let viewModel = MessagePathViewModel()
    viewModel.contacts = [contact]
    let entry = makeDTO(payloadType: .advert, packetPayload: Data([0xAB, 0xCD]))
    let sender = MessagePathSender(entry: entry, localPublicKey: Self.localKey)
    let resolution = viewModel.senderResolution(for: sender, localDeviceName: "Radio")

    #expect(sender.keyPrefix == nil)
    #expect(resolution.matchKind == .unresolved)
    #expect(viewModel.locatedSender(for: sender) == nil)
  }

  private func advert(key: Data) -> RxLogEntryDTO {
    makeDTO(payloadType: .advert, packetPayload: key + Data(repeating: 0, count: ProtocolLimits.advertTimestampSize))
  }

  private func makeDTO(payloadType: PayloadType, packetPayload: Data) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: nil,
      rssi: nil,
      rawPayload: packetPayload,
      routeType: .flood,
      payloadType: payloadType,
      payloadVersion: 0,
      payloadTypeBits: payloadType.rawValue & 0x0F,
      transportCode: nil,
      pathLength: 0,
      pathNodes: [],
      packetPayload: packetPayload
    )
    return RxLogEntryDTO(radioID: UUID(), from: parsed)
  }

  private func makeContact(
    prefix: [UInt8],
    name: String,
    latitude: Double,
    longitude: Double
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(prefix + Array(repeating: UInt8(0), count: ProtocolLimits.publicKeySize - prefix.count)),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
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
}
