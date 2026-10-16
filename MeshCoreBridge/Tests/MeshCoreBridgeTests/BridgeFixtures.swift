import Foundation
import MeshCore
@testable import MeshCoreBridgeKit

/// Deterministic sample data for the bridge-boundary golden fixtures.
///
/// Every value here is a fixed literal — fixed public keys, dates, secrets, and
/// SNRs — so regenerating without a code change reproduces byte-identical files.
/// The event list covers every `MeshEvent` case; the result list covers one
/// result per command family.
enum BridgeFixtures {
  // MARK: - Deterministic primitives

  /// A 32-byte key: bytes 0x00…0x1F.
  static let publicKey = Data((0 ..< 32).map { UInt8($0) })
  /// A 6-byte prefix: bytes 0xA0…0xA5.
  static let keyPrefix = Data((0 ..< 6).map { UInt8(0xA0 + $0) })
  /// A 16-byte secret/scope key: bytes 0xB0…0xBF.
  static let secret = Data((0 ..< 16).map { UInt8(0xB0 + $0) })
  static let signature = Data((0 ..< 64).map { UInt8($0) })
  static let ackCode = Data([0xDE, 0xAD, 0xBE, 0xEF])
  static let tag4 = Data([0x11, 0x22, 0x33, 0x44])

  static let date = Date(timeIntervalSince1970: 1_700_000_000)
  static let laterDate = Date(timeIntervalSince1970: 1_700_000_500)

  // MARK: - Sample payload values

  static let selfInfo = SelfInfo(
    advertisementType: 1,
    txPower: 22,
    maxTxPower: 30,
    publicKey: publicKey,
    latitude: 37.7749,
    longitude: -122.4194,
    multiAcks: 1,
    advertisementLocationPolicy: 2,
    telemetryModeEnvironment: 3,
    telemetryModeLocation: 1,
    telemetryModeBase: 2,
    manualAddContacts: true,
    radioFrequency: 915.0,
    radioBandwidth: 125.0,
    radioSpreadingFactor: 7,
    radioCodingRate: 5,
    name: "Base Camp"
  )

  static let deviceCapabilities = DeviceCapabilities(
    firmwareVersion: 11,
    maxContacts: 100,
    maxChannels: 8,
    blePin: 123_456,
    firmwareBuild: "v1.15.0-abc",
    model: "Heltec V3",
    version: "1.15.0",
    clientRepeat: true,
    pathHashMode: 1
  )

  static let batteryInfo = BatteryInfo(level: 3700, usedStorageKB: 128, totalStorageKB: 4096)

  static let channelInfo = ChannelInfo(index: 2, name: "Public", secret: secret)

  static let coreStats = CoreStats(batteryMV: 3700, uptimeSeconds: 86400, errors: 3, queueLength: 5)

  static let radioStats = RadioStats(
    noiseFloor: -120,
    lastRSSI: -85,
    lastSNR: 5.25,
    txAirtimeSeconds: 1200,
    rxAirtimeSeconds: 3400
  )

  static let packetStats = PacketStats(
    received: 5000,
    sent: 4200,
    floodTx: 1000,
    directTx: 3200,
    floodRx: 2000,
    directRx: 3000,
    receiveErrors: 12
  )

  static let autoAddConfig = AutoAddConfig(bitmask: 0x06, maxHops: 3)

  static let defaultFloodScope = DefaultFloodScope(name: "Europe", scopeKey: secret)

  static let frequencyRanges = [
    FrequencyRange(lowerKHz: 902_000, upperKHz: 928_000),
    FrequencyRange(lowerKHz: 863_000, upperKHz: 870_000)
  ]

  static let tuningParams = TuningParamsResponse(rxDelayBase: 100.5, airtimeFactor: 1.75)

  static let ownerInfo = OwnerInfoResponse(firmwareVersion: "1.15.0", nodeName: "Repeater A", ownerInfo: "owner@example")

  static let contact = MeshContact(
    id: publicKey.hexString,
    publicKey: publicKey,
    type: .repeater,
    typeRawValue: ContactType.repeater.rawValue,
    flags: [.favorite, .telemetryBase],
    outPathLength: 0x02,
    outPath: Data([0x10, 0x20]),
    advertisedName: "Ridgeline",
    lastAdvertisement: date,
    latitude: 40.0,
    longitude: -105.0,
    lastModified: laterDate
  )

  static let messageSentInfo = MessageSentInfo(route: 1, expectedAck: ackCode, suggestedTimeoutMs: 8000)

  static let contactMessage = ContactMessage(
    senderPublicKeyPrefix: keyPrefix,
    pathLength: 3,
    textType: 0,
    senderTimestamp: date,
    signature: signature,
    text: "Alice: Hello mesh!",
    snr: 7.25
  )

  static let channelMessage = ChannelMessage(
    channelIndex: 2,
    pathLength: 4,
    textType: 0,
    senderTimestamp: date,
    text: "Channel broadcast",
    snr: -3.5
  )

  static let channelDatagram = ChannelDatagram(
    channelIndex: 2,
    pathLength: 0xFF,
    dataType: 42,
    data: Data([0x01, 0x02, 0x03, 0x04]),
    snr: 4.0
  )

  static let traceInfo = TraceInfo(
    tag: 0x1122_3344,
    authCode: 0x5566_7788,
    flags: 0x01,
    pathLength: 2,
    path: [TraceNode(hashBytes: Data([0xAA]), snr: 6.0), TraceNode(hashBytes: nil, snr: 5.5)]
  )

  static let pathInfo = PathInfo(
    publicKeyPrefix: keyPrefix,
    outPathLength: 0x02,
    outPath: Data([0x10, 0x20]),
    inPathLength: 0x01,
    inPath: Data([0x30])
  )

  static let discoverResponse = DiscoverResponse(
    nodeType: 2,
    snrIn: 5.5,
    snr: 6.0,
    rssi: -90,
    pathLength: 3,
    tag: tag4,
    publicKey: publicKey
  )

  static let advertPathResponse = AdvertPathResponse(recvTimestamp: 1_700_000_000, pathLength: 2, path: Data([0x10, 0x20]))

  static let rawDataInfo = RawDataInfo(snr: 5.0, rssi: -88, payload: Data([0xCA, 0xFE]))

  static let logDataInfo = LogDataInfo(snr: nil, rssi: nil, payload: Data([0x01, 0x02, 0x03]))

  static let controlDataInfo = ControlDataInfo(snr: 4.5, rssi: -70, pathLength: 1, payloadType: 11, payload: Data([0x09]))

  static let parsedRxLogData = ParsedRxLogData(
    snr: 5.75,
    rssi: -92,
    rawPayload: Data([0x01, 0x02, 0x03, 0x04, 0x05]),
    routeType: .flood,
    payloadType: .textMessage,
    payloadVersion: 1,
    payloadTypeBits: 2,
    transportCode: nil,
    pathLength: 2,
    pathNodes: [0x10, 0x20],
    packetPayload: Data([0xAA, 0xBB, 0xCC]),
    senderPubkeyPrefix: Data([0xA0]),
    recipientPubkeyPrefix: Data([0xB0])
  )

  static let loginInfo = LoginInfo(
    permissions: 0x03,
    isAdmin: true,
    publicKeyPrefix: keyPrefix,
    serverTime: Date(timeIntervalSince1970: 1_700_000_000)
  )

  static let statusResponse = StatusResponse(
    layout: .repeater,
    publicKeyPrefix: keyPrefix,
    battery: 3700,
    txQueueLength: 2,
    noiseFloor: -120,
    lastRSSI: -85,
    packetsReceived: 5000,
    packetsSent: 4200,
    airtime: 1200,
    uptime: 86400,
    sentFlood: 1000,
    sentDirect: 3200,
    receivedFlood: 2000,
    receivedDirect: 3000,
    fullEvents: 1,
    lastSNR: 5.25,
    directDuplicates: 7,
    floodDuplicates: 11,
    rxAirtime: 3400,
    receiveErrors: 4,
    roomServerPostedCount: 20,
    roomServerPostPushCount: 15
  )

  /// A Cayenne LPP frame carrying one reading of every `LPPSensorType`, all on
  /// channel 1. It is the cross-platform vector for the decoded `points` the
  /// bridge emits, so every sensor type, every `LPPValue` case, and both signed
  /// and unsigned readings are exercised by the golden files. The unix time is
  /// `0x8000_0000` — the first second a signed 32-bit reader would report as
  /// 1901 instead of 19 January 2038.
  static let telemetryFrame = Data([
    0x01, 0, 0x01, // digitalInput: true
    0x01, 1, 0x00, // digitalOutput: false
    0x01, 2, 0xFE, 0x70, // analogInput: -400 / 100 = -4.0
    0x01, 3, 0x01, 0x90, // analogOutput: 400 / 100 = 4.0
    0x01, 100, 0xFF, 0xFF, 0xFF, 0xFF, // genericSensor: 4294967295 (unsigned 32-bit)
    0x01, 101, 0x03, 0xE8, // illuminance: 1000 lux
    0x01, 102, 0x01, // presence: true
    0x01, 103, 0xFF, 0x38, // temperature: -200 / 10 = -20.0 C
    0x01, 104, 0x64, // humidity: 100 * 0.5 = 50.0 %
    0x01, 113, 0x03, 0xE8, 0xFC, 0x18, 0x00, 0x00, // accelerometer: 1.0, -1.0, 0.0 g
    0x01, 115, 0x27, 0x10, // barometer: 10000 / 10 = 1000.0 hPa
    0x01, 116, 0x01, 0x8F, // voltage: 399 / 100 = 3.99 V
    0x01, 117, 0xFF, 0x9C, // current: -100 / 1000 = -0.1 A
    0x01, 118, 0x36, 0xEE, 0x80, 0x00, // frequency: 921600000 Hz
    0x01, 120, 0x55, // percentage: 85 %
    0x01, 121, 0xFF, 0xC4, // altitude: -60 m
    0x01, 122, 0xFF, 0xF0, 0xBD, // load: -3907 / 1000 = -3.907 kg (24-bit signed)
    0x01, 125, 0x01, 0xF4, // concentration: 500 ppm
    0x01, 128, 0x00, 0x64, // power: 100 W
    0x01, 130, 0x00, 0x01, 0x86, 0xA0, // distance: 100000 / 1000 = 100.0 m
    0x01, 131, 0x00, 0x00, 0x27, 0x10, // energy: 10000 / 1000 = 10.0 kWh
    0x01, 132, 0x00, 0xB4, // direction: 180 degrees
    0x01, 133, 0x80, 0x00, 0x00, 0x00, // unixTime: 2147483648 (19 Jan 2038)
    0x01, 134, 0x00, 0x64, 0xFF, 0x9C, 0x00, 0x00, // gyrometer: 1.0, -1.0, 0.0 deg/s
    0x01, 135, 0xFF, 0x80, 0x00, // colour: R255 G128 B0
    0x01, 136, 0x07, 0xFC, 0xC0, 0xFF, 0x4D, 0x92, 0x00, 0x30, 0x39, // gps: 52.3456, -4.5678, 123.45 m
    0x01, 142, 0x01 // switchValue: true
  ])

  static let telemetryResponse = TelemetryResponse(publicKeyPrefix: keyPrefix, tag: tag4, rawData: telemetryFrame)

  static let mmaResponse = MMAResponse(
    publicKeyPrefix: keyPrefix,
    tag: tag4,
    data: [MMAEntry(channel: 1, type: "temperature", min: -5.0, max: 30.5, avg: 12.25)]
  )

  static let aclResponse = ACLResponse(
    publicKeyPrefix: keyPrefix,
    tag: tag4,
    entries: [ACLEntry(keyPrefix: Data([0xA0, 0xA1]), permissions: 0x03)]
  )

  static let neighboursResponse = NeighboursResponse(
    publicKeyPrefix: keyPrefix,
    tag: tag4,
    totalCount: 2,
    neighbours: [Neighbour(publicKeyPrefix: Data([0xC0]), secondsAgo: 30, snr: 5.0)]
  )

  // MARK: - Every MeshEvent case

  /// One sample per `MeshEvent` case, keyed by the encoder's `kind` string.
  static var events: [(name: String, event: MeshEvent)] {
    [
      ("connectionStateChanged", .connectionStateChanged(.failed(.connectionFailed("link lost")))),
      ("ok", .ok(value: 42)),
      ("error", .error(code: 6)),
      ("selfInfo", .selfInfo(selfInfo)),
      ("deviceInfo", .deviceInfo(deviceCapabilities)),
      ("battery", .battery(batteryInfo)),
      ("currentTime", .currentTime(date)),
      ("customVars", .customVars(["region": "eu", "role": "repeater"])),
      ("channelInfo", .channelInfo(channelInfo)),
      ("statsCore", .statsCore(coreStats)),
      ("statsRadio", .statsRadio(radioStats)),
      ("statsPackets", .statsPackets(packetStats)),
      ("autoAddConfig", .autoAddConfig(autoAddConfig)),
      ("defaultFloodScope", .defaultFloodScope(defaultFloodScope)),
      ("allowedRepeatFreq", .allowedRepeatFreq(frequencyRanges)),
      ("contactsStart", .contactsStart(count: 12)),
      ("contact", .contact(contact)),
      ("contactsEnd", .contactsEnd(lastModified: laterDate)),
      ("newContact", .newContact(contact)),
      ("contactDeleted", .contactDeleted(publicKey: publicKey)),
      ("contactsFull", .contactsFull),
      ("contactURI", .contactURI("meshcore://contact/abc")),
      ("messageSent", .messageSent(messageSentInfo)),
      ("contactMessageReceived", .contactMessageReceived(contactMessage)),
      ("channelMessageReceived", .channelMessageReceived(channelMessage)),
      ("channelDataReceived", .channelDataReceived(channelDatagram)),
      ("noMoreMessages", .noMoreMessages),
      ("messagesWaiting", .messagesWaiting),
      ("advertisement", .advertisement(publicKey: publicKey)),
      ("pathUpdate", .pathUpdate(publicKey: publicKey)),
      ("acknowledgement", .acknowledgement(code: ackCode, tripTime: 350)),
      ("traceData", .traceData(traceInfo)),
      ("pathResponse", .pathResponse(pathInfo)),
      ("loginSuccess", .loginSuccess(loginInfo)),
      ("loginFailed", .loginFailed(publicKeyPrefix: keyPrefix)),
      ("statusResponse", .statusResponse(statusResponse)),
      ("telemetryResponse", .telemetryResponse(telemetryResponse)),
      ("binaryResponse", .binaryResponse(tag: tag4, data: Data([0x01, 0x02]))),
      ("mmaResponse", .mmaResponse(mmaResponse)),
      ("aclResponse", .aclResponse(aclResponse)),
      ("neighboursResponse", .neighboursResponse(neighboursResponse)),
      ("signStart", .signStart(maxLength: 512)),
      ("signature", .signature(signature)),
      ("disabled", .disabled(reason: "telemetry disabled")),
      ("rawData", .rawData(rawDataInfo)),
      ("logData", .logData(logDataInfo)),
      ("rxLogData", .rxLogData(parsedRxLogData)),
      ("controlData", .controlData(controlDataInfo)),
      ("discoverResponse", .discoverResponse(discoverResponse)),
      ("advertPathResponse", .advertPathResponse(advertPathResponse)),
      ("tuningParamsResponse", .tuningParamsResponse(tuningParams)),
      ("privateKey", .privateKey(signature)),
      ("parseFailure", .parseFailure(data: Data([0xFF, 0xFE]), reason: "unknown response code"))
    ]
  }

  /// One result per command family, keyed by the fixture file name.
  static var results: [(name: String, value: JSONValue)] {
    [
      ("ok", CommandResultEncoder.ok()),
      ("selfInfo", CommandResultEncoder.selfInfo(selfInfo)),
      ("deviceCapabilities", CommandResultEncoder.deviceCapabilities(deviceCapabilities)),
      ("battery", CommandResultEncoder.battery(batteryInfo)),
      ("time", CommandResultEncoder.time(date)),
      ("customVars", CommandResultEncoder.customVars(["region": "eu"])),
      ("channelInfo", CommandResultEncoder.channelInfo(channelInfo)),
      ("channels", CommandResultEncoder.channels(received: [channelInfo], missing: [3, 5])),
      ("contacts", CommandResultEncoder.contacts([contact])),
      ("contactOptional", CommandResultEncoder.contactOptional(contact)),
      ("contactURI", CommandResultEncoder.contactURI("meshcore://contact/abc")),
      ("messageSent", CommandResultEncoder.messageSent(messageSentInfo)),
      ("messageResult", CommandResultEncoder.messageResult(.contactMessage(contactMessage))),
      ("statusResponse", CommandResultEncoder.statusResponse(statusResponse)),
      ("telemetryResponse", CommandResultEncoder.telemetryResponse(telemetryResponse)),
      ("signature", CommandResultEncoder.signature(signature)),
      ("privateKey", CommandResultEncoder.privateKey(signature)),
      ("statsCore", CommandResultEncoder.statsCore(coreStats)),
      ("statsRadio", CommandResultEncoder.statsRadio(radioStats)),
      ("statsPackets", CommandResultEncoder.statsPackets(packetStats)),
      ("allowedRepeatFreq", CommandResultEncoder.allowedRepeatFreq(frequencyRanges)),
      ("autoAddConfig", CommandResultEncoder.autoAddConfig(autoAddConfig)),
      ("defaultFloodScope", CommandResultEncoder.defaultFloodScope(defaultFloodScope)),
      ("mmaResponse", CommandResultEncoder.mmaResponse(mmaResponse)),
      ("aclResponse", CommandResultEncoder.aclResponse(aclResponse)),
      ("neighboursResponse", CommandResultEncoder.neighboursResponse(neighboursResponse)),
      ("ownerInfo", CommandResultEncoder.ownerInfo(ownerInfo)),
      ("regions", CommandResultEncoder.regions(["Europe", "UK", "$private"])),
      ("discoverTag", CommandResultEncoder.discoverTag(0x4433_2211))
    ]
  }
}
