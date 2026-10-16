import Foundation
import MeshCore

/// Encodes a `MeshEvent` into the versioned boundary envelope.
///
/// The switch is intentionally exhaustive with no `default`: adding a case to
/// `MeshEvent` must break this file's compilation so the boundary encoding can
/// never silently drop a new event. The `kind` string for each case is frozen
/// and mirrored by the Kotlin decoder; see `ENCODING.md`.
enum MeshEventEncoder {
  // One arm per MeshEvent case is the freeze: keeping every case in a single
  // exhaustive switch is what makes a new case a compile error. Splitting it to
  // satisfy the complexity rule would defeat that.
  // swiftlint:disable:next cyclomatic_complexity
  static func encode(_ event: MeshEvent) -> JSONValue {
    switch event {
    case let .connectionStateChanged(state):
      envelope("connectionStateChanged", PayloadEncoder.connectionState(state))
    case let .ok(value):
      envelope("ok", .object(["value": .optionalInt(value)]))
    case let .error(code):
      envelope("error", .object(["code": .optionalInt(code)]))
    case let .selfInfo(info):
      envelope("selfInfo", PayloadEncoder.selfInfo(info))
    case let .deviceInfo(caps):
      envelope("deviceInfo", PayloadEncoder.deviceCapabilities(caps))
    case let .battery(info):
      envelope("battery", PayloadEncoder.batteryInfo(info))
    case let .currentTime(time):
      envelope("currentTime", .object(["time": .date(time)]))
    case let .customVars(vars):
      envelope("customVars", .object(["vars": PayloadEncoder.customVars(vars)]))
    case let .channelInfo(info):
      envelope("channelInfo", PayloadEncoder.channelInfo(info))
    case let .statsCore(stats):
      envelope("statsCore", PayloadEncoder.coreStats(stats))
    case let .statsRadio(stats):
      envelope("statsRadio", PayloadEncoder.radioStats(stats))
    case let .statsPackets(stats):
      envelope("statsPackets", PayloadEncoder.packetStats(stats))
    case let .autoAddConfig(config):
      envelope("autoAddConfig", PayloadEncoder.autoAddConfig(config))
    case let .defaultFloodScope(scope):
      envelope("defaultFloodScope", .object(["scope": PayloadEncoder.defaultFloodScope(scope)]))
    case let .allowedRepeatFreq(ranges):
      envelope("allowedRepeatFreq", .object(["ranges": PayloadEncoder.frequencyRanges(ranges)]))
    case let .contactsStart(count):
      envelope("contactsStart", .object(["count": .int(count)]))
    case let .contact(contact):
      envelope("contact", PayloadEncoder.contact(contact))
    case let .contactsEnd(lastModified):
      envelope("contactsEnd", .object(["lastModified": .date(lastModified)]))
    case let .newContact(contact):
      envelope("newContact", PayloadEncoder.contact(contact))
    case let .contactDeleted(publicKey):
      envelope("contactDeleted", .object(["publicKey": .data(publicKey)]))
    case .contactsFull:
      envelope("contactsFull", .object([:]))
    case let .contactURI(uri):
      envelope("contactURI", .object(["uri": .string(uri)]))
    case let .messageSent(info):
      envelope("messageSent", PayloadEncoder.messageSentInfo(info))
    case let .contactMessageReceived(message):
      envelope("contactMessageReceived", PayloadEncoder.contactMessage(message))
    case let .channelMessageReceived(message):
      envelope("channelMessageReceived", PayloadEncoder.channelMessage(message))
    case let .channelDataReceived(datagram):
      envelope("channelDataReceived", PayloadEncoder.channelDatagram(datagram))
    case .noMoreMessages:
      envelope("noMoreMessages", .object([:]))
    case .messagesWaiting:
      envelope("messagesWaiting", .object([:]))
    case let .advertisement(publicKey):
      envelope("advertisement", .object(["publicKey": .data(publicKey)]))
    case let .pathUpdate(publicKey):
      envelope("pathUpdate", .object(["publicKey": .data(publicKey)]))
    case let .acknowledgement(code, tripTime):
      envelope("acknowledgement", .object(["code": .data(code), "tripTime": .optionalInt(tripTime)]))
    case let .traceData(info):
      envelope("traceData", PayloadEncoder.traceInfo(info))
    case let .pathResponse(info):
      envelope("pathResponse", PayloadEncoder.pathInfo(info))
    case let .loginSuccess(info):
      envelope("loginSuccess", PayloadEncoder.loginInfo(info))
    case let .loginFailed(publicKeyPrefix):
      envelope("loginFailed", .object(["publicKeyPrefix": .data(publicKeyPrefix)]))
    case let .statusResponse(response):
      envelope("statusResponse", PayloadEncoder.statusResponse(response))
    case let .telemetryResponse(response):
      envelope("telemetryResponse", PayloadEncoder.telemetryResponse(response))
    case let .binaryResponse(tag, data):
      envelope("binaryResponse", .object(["tag": .data(tag), "data": .data(data)]))
    case let .mmaResponse(response):
      envelope("mmaResponse", PayloadEncoder.mmaResponse(response))
    case let .aclResponse(response):
      envelope("aclResponse", PayloadEncoder.aclResponse(response))
    case let .neighboursResponse(response):
      envelope("neighboursResponse", PayloadEncoder.neighboursResponse(response))
    case let .signStart(maxLength):
      envelope("signStart", .object(["maxLength": .int(maxLength)]))
    case let .signature(signature):
      envelope("signature", .object(["signature": .data(signature)]))
    case let .disabled(reason):
      envelope("disabled", .object(["reason": .string(reason)]))
    case let .rawData(info):
      envelope("rawData", PayloadEncoder.rawDataInfo(info))
    case let .logData(info):
      envelope("logData", PayloadEncoder.logDataInfo(info))
    case let .rxLogData(info):
      envelope("rxLogData", PayloadEncoder.parsedRxLogData(info))
    case let .controlData(info):
      envelope("controlData", PayloadEncoder.controlDataInfo(info))
    case let .discoverResponse(response):
      envelope("discoverResponse", PayloadEncoder.discoverResponse(response))
    case let .advertPathResponse(response):
      envelope("advertPathResponse", PayloadEncoder.advertPathResponse(response))
    case let .tuningParamsResponse(response):
      envelope("tuningParamsResponse", PayloadEncoder.tuningParamsResponse(response))
    case let .privateKey(key):
      envelope("privateKey", .object(["privateKey": .data(key)]))
    case let .parseFailure(data, reason):
      envelope("parseFailure", .object(["data": .data(data), "reason": .string(reason)]))
    }
  }

  private static func envelope(_ kind: String, _ payload: JSONValue) -> JSONValue {
    BridgeProtocol.envelope(kind: kind, payload: payload)
  }
}
