import Foundation

// MARK: - PacketParser

/// Stateless packet parser for decoding raw data into mesh events.
///
/// `PacketParser` acts as a central router that identifies the type of incoming
/// data based on its `ResponseCode` and delegates parsing to domain-specific handlers.
/// The leaf parsers it routes to live in the ``Parsers`` namespace.
///
/// Use the ``parse(_:)`` method to convert raw `Data` received from a transport
/// into a ``MeshEvent``.
public enum PacketParser {
  /// Parses raw binary data into a mesh event.
  ///
  /// - Parameter data: Raw data received from the device, including the response code byte.
  /// - Returns: A parsed ``MeshEvent``, or `.parseFailure` if the data is malformed or the code is unknown.
  ///
  /// ### Routing Logic
  /// 1. Extracts the first byte as the ``ResponseCode``.
  /// 2. Validates the code exists in the protocol.
  /// 3. Routes the remaining payload to a category-specific parser (Simple, Device, Contact, etc.).
  public static func parse(_ data: Data) -> MeshEvent {
    guard let firstByte = data.first else {
      return .parseFailure(data: data, reason: "Empty packet")
    }

    guard let code = ResponseCode(rawValue: firstByte) else {
      return .parseFailure(data: data, reason: "Unknown response code: 0x\(String(format: "%02X", firstByte))")
    }

    let payload = Data(data.dropFirst())

    // Route by category - eliminates giant switch, groups by domain
    switch code.category {
    case .simple:
      return parseSimpleResponse(code, payload)
    case .device:
      return parseDeviceResponse(code, payload)
    case .contact:
      return parseContactResponse(code, payload)
    case .message:
      return parseMessageResponse(code, payload)
    case .push:
      return parsePushNotification(code, payload)
    case .login:
      return parseLoginResponse(code, payload)
    case .signing:
      return parseSigningResponse(code, payload)
    case .misc:
      return parseMiscResponse(code, payload)
    }
  }
}

// MARK: - Simple Responses (inlined - trivial logic)

extension PacketParser {
  /// Handles trivial response codes like OK and Error.
  ///
  /// - Parameters:
  ///   - code: The response code.
  ///   - payload: The payload data.
  /// - Returns: A parsed mesh event.
  private static func parseSimpleResponse(_ code: ResponseCode, _ payload: Data) -> MeshEvent {
    switch code {
    case .ok:
      // OK can have optional payload (often a UInt32 value)
      if payload.count >= 4 {
        return .ok(value: payload.readUInt32LE(at: 0))
      }
      return .ok(value: nil)

    case .error:
      let errorCode = payload.first
      return .error(code: errorCode)

    default:
      return .parseFailure(data: payload, reason: "Unexpected code in simple response: \(code)")
    }
  }
}

// MARK: - Device Responses (mix of inline and extracted)

extension PacketParser {
  /// Handles responses related to local device information.
  ///
  /// - Parameters:
  ///   - code: The response code.
  ///   - payload: The payload data.
  /// - Returns: A parsed mesh event.
  private static func parseDeviceResponse(_ code: ResponseCode, _ payload: Data) -> MeshEvent {
    switch code {
    case .battery:
      // Inline - simple structure
      guard payload.count >= PacketSize.batteryMinimum else {
        return .parseFailure(
          data: payload,
          reason: "Battery response too short: \(payload.count) < \(PacketSize.batteryMinimum)"
        )
      }
      if payload.count > PacketSize.batteryMinimum, payload.count < PacketSize.batteryExtended {
        return .parseFailure(
          data: payload,
          reason: "Battery response has partial extended payload: \(payload.count) < \(PacketSize.batteryExtended)"
        )
      }
      let level = Int(payload.readUInt16LE(at: 0))
      var usedKB: Int?
      var totalKB: Int?
      if payload.count >= PacketSize.batteryExtended {
        usedKB = Int(payload.readUInt32LE(at: 2))
        totalKB = Int(payload.readUInt32LE(at: 6))
      }
      return .battery(BatteryInfo(level: level, usedStorageKB: usedKB, totalStorageKB: totalKB))

    case .currentTime:
      // Inline - trivial
      guard payload.count >= 4 else {
        return .parseFailure(
          data: payload,
          reason: "CurrentTime response too short: \(payload.count) < 4"
        )
      }
      let timestamp = payload.readUInt32LE(at: 0)
      return .currentTime(Date(timeIntervalSince1970: TimeInterval(timestamp)))

    case .disabled:
      return .disabled(reason: "private_key_export_disabled")

    case .selfInfo:
      // Extracted - complex (55+ bytes, many fields)
      return Parsers.SelfInfo.parse(payload)

    case .deviceInfo:
      // Extracted - complex (version-dependent, many fields)
      return Parsers.DeviceInfo.parse(payload)

    case .privateKey:
      // Extracted - needs validation
      return Parsers.PrivateKey.parse(payload)

    case .advertPath:
      return Parsers.AdvertPathResponse.parse(payload)

    case .tuningParams:
      return Parsers.TuningParamsResponse.parse(payload)

    case .autoAddConfig:
      guard payload.count >= 1 else {
        return .parseFailure(
          data: payload,
          reason: "AutoAddConfig response too short: \(payload.count) < 1"
        )
      }
      let maxHops: UInt8 = payload.count >= 2 ? payload[1] : 0
      return .autoAddConfig(AutoAddConfig(bitmask: payload[0], maxHops: maxHops))

    case .allowedRepeatFreq:
      return Parsers.AllowedRepeatFreq.parse(payload)

    case .defaultFloodScope:
      return Parsers.DefaultFloodScope.parse(payload)

    default:
      return .parseFailure(data: payload, reason: "Unexpected code in device response: \(code)")
    }
  }
}

// MARK: - Contact Responses

extension PacketParser {
  /// Handles responses related to contact list management.
  ///
  /// - Parameters:
  ///   - code: The response code.
  ///   - payload: The payload data.
  /// - Returns: A parsed mesh event.
  private static func parseContactResponse(_ code: ResponseCode, _ payload: Data) -> MeshEvent {
    switch code {
    case .contactStart:
      guard payload.count >= PacketSize.contactsStartMinimum else {
        return .parseFailure(
          data: payload,
          reason: "ContactStart response too short: \(payload.count) < \(PacketSize.contactsStartMinimum)"
        )
      }
      return .contactsStart(count: Int(payload.readUInt32LE(at: 0)))

    case .contact:
      // Extracted - 147 bytes, many fields
      return Parsers.Contact.parse(payload)

    case .contactEnd:
      // ContactEnd may include last modified timestamp
      if payload.count >= 4 {
        let lastMod = Date(timeIntervalSince1970: TimeInterval(payload.readUInt32LE(at: 0)))
        return .contactsEnd(lastModified: lastMod)
      }
      return .contactsEnd(lastModified: Date())

    case .contactURI:
      // Inline - simple transformation
      let hex = payload.hexString
      return .contactURI("meshcore://\(hex)")

    default:
      return .parseFailure(data: payload, reason: "Unexpected code in contact response: \(code)")
    }
  }
}

// MARK: - Message Responses

extension PacketParser {
  /// Handles responses related to direct and channel messaging.
  ///
  /// - Parameters:
  ///   - code: The response code.
  ///   - payload: The payload data.
  /// - Returns: A parsed mesh event.
  private static func parseMessageResponse(_ code: ResponseCode, _ payload: Data) -> MeshEvent {
    switch code {
    case .messageSent:
      // Inline - simple structure
      guard payload.count >= PacketSize.messageSentMinimum else {
        return .parseFailure(
          data: payload,
          reason: "MessageSent response too short: \(payload.count) < \(PacketSize.messageSentMinimum)"
        )
      }
      return .messageSent(MessageSentInfo(
        route: payload[0],
        expectedAck: Data(payload[1..<5]),
        suggestedTimeoutMs: payload.readUInt32LE(at: 5)
      ))

    case .noMoreMessages:
      return .noMoreMessages

    case .contactMessageReceived:
      return Parsers.ContactMessage.parse(payload, version: .v1)

    case .contactMessageReceivedV3:
      return Parsers.ContactMessage.parse(payload, version: .v3)

    case .channelMessageReceived:
      return Parsers.ChannelMessage.parse(payload, version: .v1)

    case .channelMessageReceivedV3:
      return Parsers.ChannelMessage.parse(payload, version: .v3)

    case .channelDataReceived:
      return Parsers.ChannelDatagram.parse(payload)

    default:
      return .parseFailure(data: payload, reason: "Unexpected code in message response: \(code)")
    }
  }
}

// MARK: - Push Notifications

extension PacketParser {
  /// Handles asynchronous push notifications from the device.
  ///
  /// - Parameters:
  ///   - code: The response code.
  ///   - payload: The payload data.
  /// - Returns: A parsed mesh event.
  private static func parsePushNotification(_ code: ResponseCode, _ payload: Data) -> MeshEvent {
    switch code {
    case .ack:
      guard payload.count >= PacketSize.ackMinimum else {
        return .parseFailure(
          data: payload,
          reason: "Ack response too short: \(payload.count) < \(PacketSize.ackMinimum)"
        )
      }
      let code = Data(payload.prefix(PacketSize.ackMinimum))
      let tripTime: UInt32? = payload.count >= PacketSize.ackWithTripTime
        ? payload.readUInt32LE(at: PacketSize.ackMinimum)
        : nil
      return .acknowledgement(code: code, tripTime: tripTime)

    case .messagesWaiting:
      return .messagesWaiting

    case .advertisement:
      return Parsers.Advertisement.parse(payload)

    case .newAdvertisement:
      return Parsers.NewAdvertisement.parse(payload)

    case .pathUpdate:
      return Parsers.PathUpdate.parse(payload)

    case .statusResponse:
      return Parsers.StatusResponse.parse(payload)

    case .telemetryResponse:
      return Parsers.TelemetryResponse.parse(payload)

    case .binaryResponse:
      return Parsers.BinaryResponse.parse(payload)

    case .pathDiscoveryResponse:
      return Parsers.PathDiscoveryResponse.parse(payload)

    case .controlData:
      return Parsers.ControlData.parse(payload)

    case .contactDeleted:
      return Parsers.ContactDeleted.parse(payload)

    case .contactsFull:
      return Parsers.ContactsFull.parse(payload)

    default:
      return .parseFailure(data: payload, reason: "Unexpected code in push notification: \(code)")
    }
  }
}

// MARK: - Login Responses

extension PacketParser {
  /// Handles authentication-related responses.
  ///
  /// - Parameters:
  ///   - code: The response code.
  ///   - payload: The payload data.
  /// - Returns: A parsed mesh event.
  private static func parseLoginResponse(_ code: ResponseCode, _ payload: Data) -> MeshEvent {
    switch code {
    case .loginSuccess:
      return Parsers.LoginSuccess.parse(payload)

    case .loginFailed:
      // 1 reserved byte + optional 6-byte pubkey prefix
      let pubkeyPrefix: Data? = payload.count >= 7 ? Data(payload[1..<7]) : nil
      return .loginFailed(publicKeyPrefix: pubkeyPrefix)

    default:
      return .parseFailure(data: payload, reason: "Unexpected code in login response: \(code)")
    }
  }
}

// MARK: - Signing Responses

extension PacketParser {
  /// Handles responses from the cryptographic signing engine.
  ///
  /// - Parameters:
  ///   - code: The response code.
  ///   - payload: The payload data.
  /// - Returns: A parsed mesh event.
  private static func parseSigningResponse(_ code: ResponseCode, _ payload: Data) -> MeshEvent {
    switch code {
    case .signStart:
      // Per Python reader.py:716-719: 1 reserved + 4 bytes max_length
      guard payload.count >= PacketSize.signStartMinimum else {
        return .parseFailure(
          data: payload,
          reason: "SignStart response too short: \(payload.count) < \(PacketSize.signStartMinimum)"
        )
      }
      return .signStart(maxLength: Int(payload.readUInt32LE(at: 1)))

    case .signature:
      return Parsers.Signature.parse(payload)

    default:
      return .parseFailure(data: payload, reason: "Unexpected code in signing response: \(code)")
    }
  }
}

// MARK: - Misc Responses

extension PacketParser {
  /// Handles utility responses like statistics and channel info.
  ///
  /// - Parameters:
  ///   - code: The response code.
  ///   - payload: The payload data.
  /// - Returns: A parsed mesh event.
  private static func parseMiscResponse(_ code: ResponseCode, _ payload: Data) -> MeshEvent {
    switch code {
    case .stats:
      guard payload.count >= 1 else {
        return .parseFailure(data: payload, reason: "Stats response too short: \(payload.count) < 1")
      }
      let statsType = payload[0]
      let statsPayload = Data(payload.dropFirst())

      switch statsType {
      case StatsType.core.rawValue:
        return Parsers.CoreStats.parse(statsPayload)
      case StatsType.radio.rawValue:
        return Parsers.RadioStats.parse(statsPayload)
      case StatsType.packets.rawValue:
        return Parsers.PacketStats.parse(statsPayload)
      default:
        return .parseFailure(data: payload, reason: "Unknown stats type: \(statsType)")
      }

    case .channelInfo:
      return Parsers.ChannelInfo.parse(payload)

    case .customVars:
      return Parsers.CustomVars.parse(payload)

    case .rawData:
      return Parsers.RawData.parse(payload)

    case .logData:
      return Parsers.LogData.parse(payload)

    case .traceData:
      return Parsers.TraceData.parse(payload)

    default:
      return .parseFailure(data: payload, reason: "Unexpected code in misc response: \(code)")
    }
  }
}
