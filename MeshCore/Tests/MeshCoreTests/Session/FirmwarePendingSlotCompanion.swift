import Foundation
@testable import MeshCore

/// Companion pending-slot matcher matching firmware clearPendingReqs,
/// onContactResponse, onContactPathRecv, and BaseChatMesh::onContactPathRecv.
actor FirmwarePendingSlotCompanion {
  enum CommandKind: Sendable, Equatable {
    case login
    case status
    case telemetry
    case binary
    case pathDiscovery
    case keepAlive
  }

  struct SentRecord: Sendable {
    let id: Int
    let kind: CommandKind
    let publicKey: Data
    let tag: Data
    let password: String?
    let route: UInt8
    let suggestedTimeoutMs: UInt32
  }

  private(set) var loginFailCount = 0
  private(set) var statusReplyCount = 0
  private(set) var pathDiscoveryFallthroughCount = 0
  private(set) var unhandledPayloads: [Data] = []
  private(set) var acceptedWrites: [SentRecord] = []

  var consumedCount: Int {
    consumed
  }

  private var consumed = 0
  private var nextTag: UInt32 = 1
  private var pendingLogin: Data?
  private var pendingStatus: Data?
  private var pendingTelemetry: Data?
  private var pendingDiscovery: Data?
  private var pendingRequest: Data?
  private var routeByKey: [Data: UInt8] = [:]
  private var suggestedTimeoutMsByKey: [Data: UInt32] = [:]

  func configureContact(_ publicKey: Data, route: UInt8, suggestedTimeoutMs: UInt32) {
    routeByKey[publicKey] = route
    suggestedTimeoutMsByKey[publicKey] = suggestedTimeoutMs
  }

  func ackNewCommands(on transport: MockTransport) async {
    let sent = await transport.sentData
    while consumed < sent.count {
      let frame = sent[consumed]
      consumed += 1
      guard let acknowledgement = accept(frame) else { continue }
      await transport.simulateReceive(acknowledgement)
    }
  }

  func deliver(from publicKey: Data, body: Data, on transport: MockTransport) async {
    var contactData = pendingRequest ?? pendingTelemetry ?? Data(repeating: 0, count: Self.tagLength)
    contactData.append(body)
    let frame = classify(publicKey: publicKey, data: contactData)
    await transport.simulateReceive(frame)
  }

  func deliver(replyingTo recordID: Int, body: Data, on transport: MockTransport) async {
    guard let record = acceptedWrites.first(where: { $0.id == recordID }) else { return }
    if record.kind == .pathDiscovery {
      await deliverPathDiscovery(replyingTo: recordID, outPathLength: 1, outPath: Data([0xAB]), on: transport)
      return
    }
    var contactData = record.tag
    contactData.append(body)
    let frame = classify(publicKey: record.publicKey, data: contactData)
    await transport.simulateReceive(frame)
  }

  func deliverPathDiscovery(
    replyingTo recordID: Int,
    outPathLength: UInt8,
    outPath: Data,
    on transport: MockTransport
  ) async {
    guard let record = acceptedWrites.first(where: { $0.id == recordID }),
          record.kind == .pathDiscovery else { return }
    if let pendingDiscovery, pendingDiscovery == record.tag {
      self.pendingDiscovery = nil
      let prefix6 = Data(record.publicKey.prefix(Self.eventPrefixLength))
      var frame = Data([ResponseCode.pathDiscoveryResponse.rawValue, 0x00])
      frame.append(prefix6)
      frame.append(outPathLength)
      frame.append(outPath)
      frame.append(0x00)
      await transport.simulateReceive(frame)
      return
    }

    // Replaced or cleared pending_discovery falls through BaseChatMesh::onContactPathRecv:
    // pushes 0x81 pathUpdate and passes extra response bytes to onContactResponse.
    pathDiscoveryFallthroughCount += 1
    let pathUpdateFrame = Data([ResponseCode.pathUpdate.rawValue]) + record.publicKey
    await transport.simulateReceive(pathUpdateFrame)
    let fallbackFrame = classify(publicKey: record.publicKey, data: record.tag + Data([Self.serverLoginOK]))
    await transport.simulateReceive(fallbackFrame)
  }

  private func accept(_ frame: Data) -> Data? {
    guard let opcode = frame.first, let command = CommandCode(rawValue: opcode) else { return nil }
    let keyStart = PacketBuilder.publicKeySize
    switch command {
    case .sendLogin where frame.count >= 1 + keyStart:
      let publicKey = Data(frame[1..<(1 + keyStart)])
      let prefix = Data(publicKey.prefix(Self.tagLength))
      let password = String(data: frame.dropFirst(1 + keyStart), encoding: .utf8)
      clearPending()
      pendingLogin = prefix
      let route = routeByKey[publicKey] ?? 0
      let timeoutMs = suggestedTimeoutMsByKey[publicKey] ?? 0
      acceptedWrites.append(SentRecord(
        id: acceptedWrites.count,
        kind: .login,
        publicKey: publicKey,
        tag: prefix,
        password: password,
        route: route,
        suggestedTimeoutMs: timeoutMs
      ))
      return sentFrame(route: route, tag: prefix, suggestedTimeoutMs: timeoutMs)

    case .sendStatusRequest where frame.count >= 1 + keyStart:
      let publicKey = Data(frame[1..<(1 + keyStart)])
      let prefix = Data(publicKey.prefix(Self.tagLength))
      let tag = makeTag()
      clearPending()
      pendingStatus = prefix
      let route = routeByKey[publicKey] ?? 0
      let timeoutMs = suggestedTimeoutMsByKey[publicKey] ?? 0
      acceptedWrites.append(SentRecord(
        id: acceptedWrites.count,
        kind: .status,
        publicKey: publicKey,
        tag: tag,
        password: nil,
        route: route,
        suggestedTimeoutMs: timeoutMs
      ))
      return sentFrame(route: route, tag: tag, suggestedTimeoutMs: timeoutMs)

    case .getSelfTelemetry where frame.count >= Self.telemetryKeyOffset + keyStart:
      let publicKey = Data(frame[Self.telemetryKeyOffset..<(Self.telemetryKeyOffset + keyStart)])
      let tag = makeTag()
      clearPending()
      pendingTelemetry = tag
      let route = routeByKey[publicKey] ?? 0
      let timeoutMs = suggestedTimeoutMsByKey[publicKey] ?? 0
      acceptedWrites.append(SentRecord(
        id: acceptedWrites.count,
        kind: .telemetry,
        publicKey: publicKey,
        tag: tag,
        password: nil,
        route: route,
        suggestedTimeoutMs: timeoutMs
      ))
      return sentFrame(route: route, tag: tag, suggestedTimeoutMs: timeoutMs)

    case .pathDiscovery where frame.count >= 2 + keyStart:
      let publicKey = Data(frame[2..<(2 + keyStart)])
      let tag = makeTag()
      clearPending()
      pendingDiscovery = tag
      let route = routeByKey[publicKey] ?? MessageSentInfo.floodRoute
      let timeoutMs = suggestedTimeoutMsByKey[publicKey] ?? 0
      acceptedWrites.append(SentRecord(
        id: acceptedWrites.count,
        kind: .pathDiscovery,
        publicKey: publicKey,
        tag: tag,
        password: nil,
        route: route,
        suggestedTimeoutMs: timeoutMs
      ))
      return sentFrame(route: route, tag: tag, suggestedTimeoutMs: timeoutMs)

    case .binaryRequest where frame.count >= 1 + keyStart + 1:
      let publicKey = Data(frame[1..<(1 + keyStart)])
      let reqType = frame[1 + keyStart]
      let tag = makeTag()
      clearPending()
      pendingRequest = tag
      let route = routeByKey[publicKey] ?? 0
      let timeoutMs = suggestedTimeoutMsByKey[publicKey] ?? 0
      let kind: CommandKind = reqType == BinaryRequestType.keepAlive.rawValue ? .keepAlive : .binary
      acceptedWrites.append(SentRecord(
        id: acceptedWrites.count,
        kind: kind,
        publicKey: publicKey,
        tag: tag,
        password: nil,
        route: route,
        suggestedTimeoutMs: timeoutMs
      ))
      return sentFrame(route: route, tag: tag, suggestedTimeoutMs: timeoutMs)

    default:
      return nil
    }
  }

  /// Same order as companion `onContactResponse`: login, status, telemetry, binary, then unhandled.
  private func classify(publicKey: Data, data: Data) -> Data {
    let prefix4 = Data(publicKey.prefix(Self.tagLength))
    let prefix6 = Data(publicKey.prefix(Self.eventPrefixLength))
    let tag = data.count >= Self.tagLength ? Data(data.prefix(Self.tagLength)) : Data()
    let body = data.count > Self.tagLength ? Data(data.dropFirst(Self.tagLength)) : Data()

    if let pendingLogin, pendingLogin == prefix4 {
      self.pendingLogin = nil
      if body.starts(with: Data("OK".utf8)) || body.first == Self.serverLoginOK {
        return loginFrame(success: true, prefix: prefix6)
      }
      loginFailCount += 1
      return loginFrame(success: false, prefix: prefix6)
    }

    if !body.isEmpty, let pendingStatus, pendingStatus == prefix4 {
      self.pendingStatus = nil
      statusReplyCount += 1
      return Data([ResponseCode.statusResponse.rawValue, 0]) + prefix6 + body
    }

    if !body.isEmpty, let pendingTelemetry, tag == pendingTelemetry {
      self.pendingTelemetry = nil
      return Data([ResponseCode.telemetryResponse.rawValue, 0]) + prefix6 + body
    }

    if !body.isEmpty, let pendingRequest, tag == pendingRequest {
      self.pendingRequest = nil
      return Data([ResponseCode.binaryResponse.rawValue, 0]) + tag + body
    }

    unhandledPayloads.append(data)
    return Data([ResponseCode.binaryResponse.rawValue, 0]) + data
  }

  private func clearPending() {
    pendingLogin = nil
    pendingStatus = nil
    pendingTelemetry = nil
    pendingDiscovery = nil
    pendingRequest = nil
  }

  private func makeTag() -> Data {
    let tag = nextTag
    nextTag += 1
    var data = Data()
    data.appendLittleEndian(tag)
    return data
  }

  private func sentFrame(route: UInt8, tag: Data, suggestedTimeoutMs: UInt32) -> Data {
    var frame = Data([ResponseCode.messageSent.rawValue, route])
    frame.append(tag.prefix(Self.tagLength))
    frame.appendLittleEndian(suggestedTimeoutMs)
    return frame
  }

  private func loginFrame(success: Bool, prefix: Data) -> Data {
    let code: ResponseCode = success ? .loginSuccess : .loginFailed
    return Data([code.rawValue, 0]) + prefix
  }

  private static let tagLength = 4
  private static let eventPrefixLength = 6
  private static let telemetryKeyOffset = 4
  private static let serverLoginOK: UInt8 = 0
}
