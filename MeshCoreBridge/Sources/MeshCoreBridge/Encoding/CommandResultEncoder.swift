import Foundation
import MeshCore

/// Encodes command return values into the versioned boundary envelope.
///
/// Each result `kind` is frozen and mirrored by the Kotlin decoder (see
/// `ENCODING.md`). Result payloads reuse ``PayloadEncoder`` so a type that is
/// both an event associated value and a command result (for example
/// `BatteryInfo`) has one canonical wire form. Commands returning `Void` use
/// ``ok()``.
enum CommandResultEncoder {
  /// A successful command that returns no value.
  static func ok() -> JSONValue {
    BridgeProtocol.envelope(kind: "ok")
  }

  static func selfInfo(_ value: SelfInfo) -> JSONValue {
    envelope("selfInfo", PayloadEncoder.selfInfo(value))
  }

  static func deviceCapabilities(_ value: DeviceCapabilities) -> JSONValue {
    envelope("deviceCapabilities", PayloadEncoder.deviceCapabilities(value))
  }

  static func battery(_ value: BatteryInfo) -> JSONValue {
    envelope("battery", PayloadEncoder.batteryInfo(value))
  }

  static func time(_ value: Date) -> JSONValue {
    envelope("time", .object(["time": .date(value)]))
  }

  static func customVars(_ value: [String: String]) -> JSONValue {
    envelope("customVars", .object(["vars": PayloadEncoder.customVars(value)]))
  }

  static func channelInfo(_ value: ChannelInfo) -> JSONValue {
    envelope("channelInfo", PayloadEncoder.channelInfo(value))
  }

  static func channels(received: [ChannelInfo], missing: [UInt8]) -> JSONValue {
    envelope("channels", .object([
      "received": .array(received.map(PayloadEncoder.channelInfo)),
      "missing": .array(missing.map { .int($0) })
    ]))
  }

  static func contacts(_ value: [MeshContact], reportedTotal: Int? = nil) -> JSONValue {
    envelope("contacts", .object([
      "contacts": PayloadEncoder.contacts(value),
      "reportedTotal": .optionalInt(reportedTotal)
    ]))
  }

  static func contactOptional(_ value: MeshContact?) -> JSONValue {
    let payload: JSONValue = value.map(PayloadEncoder.contact) ?? .null
    return envelope("contactOptional", .object(["contact": payload]))
  }

  static func contactURI(_ value: String) -> JSONValue {
    envelope("contactURI", .object(["uri": .string(value)]))
  }

  static func messageSent(_ value: MessageSentInfo) -> JSONValue {
    envelope("messageSent", PayloadEncoder.messageSentInfo(value))
  }

  static func statusResponse(_ value: StatusResponse) -> JSONValue {
    envelope("statusResponse", PayloadEncoder.statusResponse(value))
  }

  static func telemetryResponse(_ value: TelemetryResponse) -> JSONValue {
    envelope("telemetryResponse", PayloadEncoder.telemetryResponse(value))
  }

  static func signature(_ value: Data) -> JSONValue {
    envelope("signature", .object(["signature": .data(value)]))
  }

  static func privateKey(_ value: Data) -> JSONValue {
    envelope("privateKey", .object(["privateKey": .data(value)]))
  }

  static func statsCore(_ value: CoreStats) -> JSONValue {
    envelope("statsCore", PayloadEncoder.coreStats(value))
  }

  static func statsRadio(_ value: RadioStats) -> JSONValue {
    envelope("statsRadio", PayloadEncoder.radioStats(value))
  }

  static func statsPackets(_ value: PacketStats) -> JSONValue {
    envelope("statsPackets", PayloadEncoder.packetStats(value))
  }

  static func allowedRepeatFreq(_ value: [FrequencyRange]) -> JSONValue {
    envelope("allowedRepeatFreq", .object(["ranges": PayloadEncoder.frequencyRanges(value)]))
  }

  static func autoAddConfig(_ value: AutoAddConfig) -> JSONValue {
    envelope("autoAddConfig", PayloadEncoder.autoAddConfig(value))
  }

  static func defaultFloodScope(_ value: DefaultFloodScope?) -> JSONValue {
    envelope("defaultFloodScope", .object(["scope": PayloadEncoder.defaultFloodScope(value)]))
  }

  static func mmaResponse(_ value: MMAResponse) -> JSONValue {
    envelope("mmaResponse", PayloadEncoder.mmaResponse(value))
  }

  static func aclResponse(_ value: ACLResponse) -> JSONValue {
    envelope("aclResponse", PayloadEncoder.aclResponse(value))
  }

  static func neighboursResponse(_ value: NeighboursResponse) -> JSONValue {
    envelope("neighboursResponse", PayloadEncoder.neighboursResponse(value))
  }

  static func ownerInfo(_ value: OwnerInfoResponse) -> JSONValue {
    envelope("ownerInfo", PayloadEncoder.ownerInfoResponse(value))
  }

  static func regions(_ value: [String]) -> JSONValue {
    envelope("regions", .object(["regions": .array(value.map { .string($0) })]))
  }

  /// Result of `sendNodeDiscoverRequest`: the 32-bit correlation tag used on the wire.
  static func discoverTag(_ tag: UInt32) -> JSONValue {
    envelope("discoverTag", .object(["tag": .integer(Int64(tag))]))
  }

  /// Result of `getMessage`. Discriminated by `type`; the drained message rides
  /// under `message`, or is absent for `noMoreMessages`.
  static func messageResult(_ value: MessageResult) -> JSONValue {
    switch value {
    case let .contactMessage(message):
      envelope("messageResult", .object([
        "type": .string("contactMessage"),
        "message": PayloadEncoder.contactMessage(message)
      ]))
    case let .channelMessage(message):
      envelope("messageResult", .object([
        "type": .string("channelMessage"),
        "message": PayloadEncoder.channelMessage(message)
      ]))
    case let .channelDatagram(datagram):
      envelope("messageResult", .object([
        "type": .string("channelDatagram"),
        "message": PayloadEncoder.channelDatagram(datagram)
      ]))
    case .noMoreMessages:
      envelope("messageResult", .object(["type": .string("noMoreMessages")]))
    }
  }

  private static func envelope(_ kind: String, _ payload: JSONValue) -> JSONValue {
    BridgeProtocol.envelope(kind: kind, payload: payload)
  }
}
