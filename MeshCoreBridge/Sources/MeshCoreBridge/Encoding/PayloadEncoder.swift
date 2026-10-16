import Foundation
import MeshCore

/// Encodes MeshCore value types into `JSONValue` payloads.
///
/// These functions are the single definition of every payload's shape; both the
/// event encoder and the command-result encoder call into them, so a struct that
/// appears in both an event and a command result (e.g. `BatteryInfo`) has exactly
/// one wire form. `ENCODING.md` documents every field these emit. Field names
/// mirror the Swift property names; `Data` is base64, `Date` is integer epoch
/// seconds (see ``JSONValue``).
enum PayloadEncoder {
  // MARK: - Device

  static func selfInfo(_ value: SelfInfo) -> JSONValue {
    .object([
      "advertisementType": .int(value.advertisementType),
      "txPower": .int(value.txPower),
      "maxTxPower": .int(value.maxTxPower),
      "publicKey": .data(value.publicKey),
      "latitude": .double(value.latitude),
      "longitude": .double(value.longitude),
      "multiAcks": .int(value.multiAcks),
      "advertisementLocationPolicy": .int(value.advertisementLocationPolicy),
      "telemetryModeEnvironment": .int(value.telemetryModeEnvironment),
      "telemetryModeLocation": .int(value.telemetryModeLocation),
      "telemetryModeBase": .int(value.telemetryModeBase),
      "manualAddContacts": .bool(value.manualAddContacts),
      "radioFrequency": .double(value.radioFrequency),
      "radioBandwidth": .double(value.radioBandwidth),
      "radioSpreadingFactor": .int(value.radioSpreadingFactor),
      "radioCodingRate": .int(value.radioCodingRate),
      "name": .string(value.name)
    ])
  }

  static func deviceCapabilities(_ value: DeviceCapabilities) -> JSONValue {
    .object([
      "firmwareVersion": .int(value.firmwareVersion),
      "maxContacts": .int(value.maxContacts),
      "maxChannels": .int(value.maxChannels),
      "blePin": .int(value.blePin),
      "firmwareBuild": .string(value.firmwareBuild),
      "model": .string(value.model),
      "version": .string(value.version),
      "clientRepeat": .bool(value.clientRepeat),
      "pathHashMode": .int(value.pathHashMode)
    ])
  }

  static func batteryInfo(_ value: BatteryInfo) -> JSONValue {
    .object([
      "level": .int(value.level),
      "usedStorageKB": .optionalInt(value.usedStorageKB),
      "totalStorageKB": .optionalInt(value.totalStorageKB)
    ])
  }

  static func customVars(_ value: [String: String]) -> JSONValue {
    .object(value.mapValues { .string($0) })
  }

  static func coreStats(_ value: CoreStats) -> JSONValue {
    .object([
      "batteryMV": .int(value.batteryMV),
      "uptimeSeconds": .int(value.uptimeSeconds),
      "errors": .int(value.errors),
      "queueLength": .int(value.queueLength)
    ])
  }

  static func radioStats(_ value: RadioStats) -> JSONValue {
    .object([
      "noiseFloor": .int(value.noiseFloor),
      "lastRSSI": .int(value.lastRSSI),
      "lastSNR": .double(value.lastSNR),
      "txAirtimeSeconds": .int(value.txAirtimeSeconds),
      "rxAirtimeSeconds": .int(value.rxAirtimeSeconds)
    ])
  }

  static func packetStats(_ value: PacketStats) -> JSONValue {
    .object([
      "received": .int(value.received),
      "sent": .int(value.sent),
      "floodTx": .int(value.floodTx),
      "directTx": .int(value.directTx),
      "floodRx": .int(value.floodRx),
      "directRx": .int(value.directRx),
      "receiveErrors": .int(value.receiveErrors)
    ])
  }

  static func autoAddConfig(_ value: AutoAddConfig) -> JSONValue {
    .object([
      "bitmask": .int(value.bitmask),
      "maxHops": .int(value.maxHops)
    ])
  }

  static func defaultFloodScope(_ value: DefaultFloodScope?) -> JSONValue {
    guard let value else { return .null }
    return .object([
      "name": .string(value.name),
      "scopeKey": .data(value.scopeKey)
    ])
  }

  static func frequencyRanges(_ value: [FrequencyRange]) -> JSONValue {
    .array(value.map { range in
      .object([
        "lowerKHz": .int(range.lowerKHz),
        "upperKHz": .int(range.upperKHz)
      ])
    })
  }

  static func tuningParamsResponse(_ value: TuningParamsResponse) -> JSONValue {
    .object([
      "rxDelayBase": .double(value.rxDelayBase),
      "airtimeFactor": .double(value.airtimeFactor)
    ])
  }

  static func ownerInfoResponse(_ value: OwnerInfoResponse) -> JSONValue {
    .object([
      "firmwareVersion": .string(value.firmwareVersion),
      "nodeName": .string(value.nodeName),
      "ownerInfo": .string(value.ownerInfo)
    ])
  }

  // MARK: - Contacts

  static func contact(_ value: MeshContact) -> JSONValue {
    .object([
      "id": .string(value.id),
      "publicKey": .data(value.publicKey),
      "typeRawValue": .int(value.typeRawValue),
      "flags": .int(value.flags.rawValue),
      "outPathLength": .int(value.outPathLength),
      "outPath": .data(value.outPath),
      "advertisedName": .string(value.advertisedName),
      "lastAdvertisement": .date(value.lastAdvertisement),
      "latitude": .double(value.latitude),
      "longitude": .double(value.longitude),
      "lastModified": .date(value.lastModified)
    ])
  }

  static func contacts(_ value: [MeshContact]) -> JSONValue {
    .array(value.map(contact))
  }

  static func channelInfo(_ value: ChannelInfo) -> JSONValue {
    .object([
      "index": .int(value.index),
      "name": .string(value.name),
      "secret": .data(value.secret)
    ])
  }

  // MARK: - Messaging

  static func messageSentInfo(_ value: MessageSentInfo) -> JSONValue {
    .object([
      "route": .int(value.route),
      "expectedAck": .data(value.expectedAck),
      "suggestedTimeoutMs": .int(value.suggestedTimeoutMs)
    ])
  }

  static func contactMessage(_ value: ContactMessage) -> JSONValue {
    .object([
      "senderPublicKeyPrefix": .data(value.senderPublicKeyPrefix),
      "pathLength": .int(value.pathLength),
      "textType": .int(value.textType),
      "senderTimestamp": .date(value.senderTimestamp),
      "signature": .data(value.signature),
      "text": .string(value.text),
      "snr": .optionalDouble(value.snr)
    ])
  }

  static func channelMessage(_ value: ChannelMessage) -> JSONValue {
    .object([
      "channelIndex": .int(value.channelIndex),
      "pathLength": .int(value.pathLength),
      "textType": .int(value.textType),
      "senderTimestamp": .date(value.senderTimestamp),
      "text": .string(value.text),
      "snr": .optionalDouble(value.snr)
    ])
  }

  static func channelDatagram(_ value: ChannelDatagram) -> JSONValue {
    .object([
      "channelIndex": .int(value.channelIndex),
      "pathLength": .int(value.pathLength),
      "dataType": .int(value.dataType),
      "data": .data(value.data),
      "snr": .double(value.snr)
    ])
  }

  // MARK: - Network / diagnostics

  static func traceInfo(_ value: TraceInfo) -> JSONValue {
    .object([
      "tag": .int(value.tag),
      "authCode": .int(value.authCode),
      "flags": .int(value.flags),
      "pathLength": .int(value.pathLength),
      "path": .array(value.path.map(traceNode))
    ])
  }

  static func traceNode(_ value: TraceNode) -> JSONValue {
    .object([
      "hashBytes": .data(value.hashBytes),
      "snr": .double(value.snr)
    ])
  }

  static func pathInfo(_ value: PathInfo) -> JSONValue {
    .object([
      "publicKeyPrefix": .data(value.publicKeyPrefix),
      "outPathLength": .int(value.outPathLength),
      "outPath": .data(value.outPath),
      "inPathLength": .int(value.inPathLength),
      "inPath": .data(value.inPath)
    ])
  }

  static func discoverResponse(_ value: DiscoverResponse) -> JSONValue {
    .object([
      "nodeType": .int(value.nodeType),
      "snrIn": .double(value.snrIn),
      "snr": .double(value.snr),
      "rssi": .int(value.rssi),
      "pathLength": .int(value.pathLength),
      "tag": .data(value.tag),
      "publicKey": .data(value.publicKey)
    ])
  }

  static func advertPathResponse(_ value: AdvertPathResponse) -> JSONValue {
    .object([
      "recvTimestamp": .int(value.recvTimestamp),
      "pathLength": .int(value.pathLength),
      "path": .data(value.path)
    ])
  }

  static func rawDataInfo(_ value: RawDataInfo) -> JSONValue {
    .object([
      "snr": .double(value.snr),
      "rssi": .int(value.rssi),
      "payload": .data(value.payload)
    ])
  }

  static func logDataInfo(_ value: LogDataInfo) -> JSONValue {
    .object([
      "snr": .optionalDouble(value.snr),
      "rssi": .optionalInt(value.rssi),
      "payload": .data(value.payload)
    ])
  }

  static func controlDataInfo(_ value: ControlDataInfo) -> JSONValue {
    .object([
      "snr": .double(value.snr),
      "rssi": .int(value.rssi),
      "pathLength": .int(value.pathLength),
      "payloadType": .int(value.payloadType),
      "payload": .data(value.payload)
    ])
  }

  static func parsedRxLogData(_ value: ParsedRxLogData) -> JSONValue {
    .object([
      "snr": .optionalDouble(value.snr),
      "rssi": .optionalInt(value.rssi),
      "rawPayload": .data(value.rawPayload),
      "routeType": .int(value.routeType.rawValue),
      "payloadType": .int(value.payloadType.rawValue),
      "payloadVersion": .int(value.payloadVersion),
      "payloadTypeBits": .int(value.payloadTypeBits),
      "transportCode": .data(value.transportCode),
      "pathLength": .int(value.pathLength),
      "pathNodes": .array(value.pathNodes.map { .int($0) }),
      "packetPayload": .data(value.packetPayload),
      "senderPubkeyPrefix": .data(value.senderPubkeyPrefix),
      "recipientPubkeyPrefix": .data(value.recipientPubkeyPrefix),
      "packetHash": .string(value.packetHash)
    ])
  }

  // MARK: - Remote admin

  static func loginInfo(_ value: LoginInfo) -> JSONValue {
    .object([
      "permissions": .int(value.permissions),
      "isAdmin": .bool(value.isAdmin),
      "publicKeyPrefix": .data(value.publicKeyPrefix),
      "serverTime": .date(value.serverTime)
    ])
  }

  static func statusResponse(_ value: StatusResponse) -> JSONValue {
    .object([
      "layout": .string(layoutName(value.layout)),
      "publicKeyPrefix": .data(value.publicKeyPrefix),
      "battery": .int(value.battery),
      "txQueueLength": .int(value.txQueueLength),
      "noiseFloor": .int(value.noiseFloor),
      "lastRSSI": .int(value.lastRSSI),
      "packetsReceived": .int(value.packetsReceived),
      "packetsSent": .int(value.packetsSent),
      "airtime": .int(value.airtime),
      "uptime": .int(value.uptime),
      "sentFlood": .int(value.sentFlood),
      "sentDirect": .int(value.sentDirect),
      "receivedFlood": .int(value.receivedFlood),
      "receivedDirect": .int(value.receivedDirect),
      "fullEvents": .int(value.fullEvents),
      "lastSNR": .double(value.lastSNR),
      "directDuplicates": .int(value.directDuplicates),
      "floodDuplicates": .int(value.floodDuplicates),
      "rxAirtime": .int(value.rxAirtime),
      "receiveErrors": .int(value.receiveErrors),
      "roomServerPostedCount": .optionalInt(value.roomServerPostedCount),
      "roomServerPostPushCount": .optionalInt(value.roomServerPostPushCount)
    ])
  }

  private static func layoutName(_ layout: StatusResponse.Layout) -> String {
    switch layout {
    case .repeater: "repeater"
    case .roomServer: "roomServer"
    }
  }

  static func telemetryResponse(_ value: TelemetryResponse) -> JSONValue {
    // The Cayenne LPP frame is decoded here, by MeshCore's `LPPDecoder`, so the
    // wire parser has exactly one implementation and the consumer never
    // interprets bytes. `rawData` still crosses for the audit trail.
    .object([
      "publicKeyPrefix": .data(value.publicKeyPrefix),
      "tag": .data(value.tag),
      "rawData": .data(value.rawData),
      "points": .array(LPPDecoder.decode(value.rawData).map(lppDataPoint))
    ])
  }

  static func lppDataPoint(_ value: LPPDataPoint) -> JSONValue {
    .object([
      "channel": .int(value.channel),
      "typeRawValue": .int(value.type.rawValue),
      "value": lppValue(value.value)
    ])
  }

  /// A decoded LPP reading, discriminated by `kind` like the other boundary
  /// unions. `timestamp` rides as integer epoch seconds, which is what keeps a
  /// four-byte LPP time readable past January 2038: the wire field is unsigned,
  /// and only a signed 32-bit reader downstream could turn it negative.
  private static func lppValue(_ value: LPPValue) -> JSONValue {
    switch value {
    case let .digital(flag):
      .object(["kind": .string("digital"), "value": .bool(flag)])
    case let .integer(number):
      .object(["kind": .string("integer"), "value": .int(number)])
    case let .float(number):
      .object(["kind": .string("float"), "value": .double(number)])
    case let .vector3(x, y, z):
      .object(["kind": .string("vector3"), "x": .double(x), "y": .double(y), "z": .double(z)])
    case let .gps(latitude, longitude, altitude):
      .object([
        "kind": .string("gps"),
        "latitude": .double(latitude),
        "longitude": .double(longitude),
        "altitude": .double(altitude)
      ])
    case let .rgb(red, green, blue):
      .object(["kind": .string("rgb"), "red": .int(red), "green": .int(green), "blue": .int(blue)])
    case let .timestamp(date):
      .object(["kind": .string("timestamp"), "value": .date(date)])
    }
  }

  static func mmaResponse(_ value: MMAResponse) -> JSONValue {
    .object([
      "publicKeyPrefix": .data(value.publicKeyPrefix),
      "tag": .data(value.tag),
      "data": .array(value.data.map(mmaEntry))
    ])
  }

  private static func mmaEntry(_ value: MMAEntry) -> JSONValue {
    .object([
      "channel": .int(value.channel),
      "type": .string(value.type),
      "min": .double(value.min),
      "max": .double(value.max),
      "avg": .double(value.avg)
    ])
  }

  static func aclResponse(_ value: ACLResponse) -> JSONValue {
    .object([
      "publicKeyPrefix": .data(value.publicKeyPrefix),
      "tag": .data(value.tag),
      "entries": .array(value.entries.map { entry in
        .object([
          "keyPrefix": .data(entry.keyPrefix),
          "permissions": .int(entry.permissions)
        ])
      })
    ])
  }

  static func neighboursResponse(_ value: NeighboursResponse) -> JSONValue {
    .object([
      "publicKeyPrefix": .data(value.publicKeyPrefix),
      "tag": .data(value.tag),
      "totalCount": .int(value.totalCount),
      "neighbours": .array(value.neighbours.map { neighbour in
        .object([
          "publicKeyPrefix": .data(neighbour.publicKeyPrefix),
          "secondsAgo": .int(neighbour.secondsAgo),
          "snr": .double(neighbour.snr)
        ])
      })
    ])
  }

  // MARK: - Connection state

  static func connectionState(_ value: ConnectionState) -> JSONValue {
    switch value {
    case .disconnected:
      .object(["state": .string("disconnected")])
    case .connecting:
      .object(["state": .string("connecting")])
    case .connected:
      .object(["state": .string("connected")])
    case let .reconnecting(attempt):
      .object(["state": .string("reconnecting"), "attempt": .int(attempt)])
    case let .failed(error):
      .object(["state": .string("failed"), "error": transportError(error)])
    }
  }

  static func transportError(_ value: MeshTransportError) -> JSONValue {
    switch value {
    case .notConnected:
      .object(["kind": .string("notConnected")])
    case let .connectionFailed(reason):
      .object(["kind": .string("connectionFailed"), "detail": .string(reason)])
    case let .sendFailed(reason):
      .object(["kind": .string("sendFailed"), "detail": .string(reason)])
    case .deviceNotFound:
      .object(["kind": .string("deviceNotFound")])
    case .serviceNotFound:
      .object(["kind": .string("serviceNotFound")])
    case .characteristicNotFound:
      .object(["kind": .string("characteristicNotFound")])
    }
  }
}
