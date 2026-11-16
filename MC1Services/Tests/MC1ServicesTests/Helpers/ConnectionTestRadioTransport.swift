import Foundation
@testable import MC1Services
import MeshCore

/// Drives production session framing and connection ownership with a bounded radio model.
actor ConnectionTestRadioTransport: iOSMeshTransport, WiFiMeshTransport {
  private enum Gate: Hashable { case connect, submission, completion, disconnect, commandSubmission(UInt8) }
  private var heldGates: Set<Gate> = []
  private var waiters: [Gate: [CheckedContinuation<Void, Never>]] = [:]
  private var heldReplyCodes: Set<UInt8> = []
  private var heldReplies: [UInt8: [Data]] = [:]
  private var rejection: [UInt8: (remaining: Int, code: UInt8)] = [:]
  private var cliResponses: [String: String] = [:]
  private var cliResponder: (@Sendable (String) -> String?)?
  private var heldCLICommands: Set<String> = []
  private var heldCLIReplies: [String: [Data]] = [:]
  private var responsePackets: [UInt8: [Data]] = [:]
  private var responseSequences: [UInt8: [[Data]]] = [:]
  private var channels: [UInt8: (name: String, secret: Data)] = [:]
  private var contacts: [Data: Data] = [:]
  private var devicePublicKeys: [UUID: Data] = [:]
  private var identityImportPublicKey: Data?
  private var importedPublicKey: Data?
  private var selfInfoName = "Test Radio"
  private var selfInfoTxPower: UInt8 = 22
  private var selfInfoCoordinates = Data(repeating: 0, count: 8)
  private var selfInfoOtherParams = Data(repeating: 0, count: 4)
  private var selfInfoRadio = Data(PacketBuilder.setRadio(
    frequency: 915, bandwidth: 125, spreadingFactor: 7, codingRate: 5
  ).dropFirst())
  private var holdsLoginResults = false
  private var heldLoginResults: [Data] = []
  private var stream: AsyncStream<Data>
  private var continuation: AsyncStream<Data>.Continuation
  private var streamNeedsRenewal = false
  private var deviceID: UUID?
  private var generation: UInt64 = 0
  private var originTime = CFAbsoluteTimeGetCurrent()
  private var binding: SessionTransportBinding?
  private var lifecycleHandler: (@Sendable (BLELifecycleEvent) -> Void)?
  private var socketDisconnectionHandler: (@Sendable (Error?, CFAbsoluteTime) -> Void)?
  private var connectError: (any Error)?
  private var sendError: (any Error)?
  private var commandSendErrors: [UInt8: any Error] = [:]
  private var postSubmissionSendErrors: [UInt8: any Error] = [:]
  private var recovering = false
  let publicKey: Data
  let firmwareVersion: UInt8
  let maxChannels: UInt8
  private(set) var connectionInfo: (host: String, port: UInt16)?
  private(set) var isConnected = false
  private(set) var connectCount = 0
  private(set) var disconnectCount = 0
  private(set) var sentData: [Data] = []
  private var lastYieldedByCommand: [UInt8: Data] = [:]
  private(set) var yieldedChannelInfoCount = 0
  private(set) var failedSendCount = 0
  private(set) var sessionDeviceIDs: [UUID] = []
  private(set) var cliCommands: [String] = []
  private(set) var timeReads = 0
  private(set) var timeWrites = 0
  private(set) var channelWriteIndices: [UInt8] = []
  private(set) var unconfiguredChannelSendCount = 0
  var radioTime = Date()

  init(publicKey: Data = Data(repeating: 1, count: 32), firmwareVersion: UInt8 = 14, maxChannels: UInt8 = 1) {
    self.publicKey = publicKey
    self.firmwareVersion = firmwareVersion
    self.maxChannels = maxChannels
    (stream, continuation) = AsyncStream.makeStream(of: Data.self)
  }

  var receivedData: AsyncStream<Data> {
    stream
  }

  var supportsPipelinedReads: Bool {
    true
  }

  var connectIsHeld: Bool {
    waiters[.connect]?.isEmpty == false
  }

  var submissionIsHeld: Bool {
    waiters[.submission]?.isEmpty == false
  }

  var sendCompletionIsHeld: Bool {
    waiters[.completion]?.isEmpty == false
  }

  var disconnectIsHeld: Bool {
    waiters[.disconnect]?.isEmpty == false
  }

  var hasLifecycleHandler: Bool {
    lifecycleHandler != nil
  }

  var heldLoginResultCount: Int {
    heldLoginResults.count
  }

  var lifecycleOrigin: BLELifecycleOrigin? {
    guard let deviceID, isConnected || recovering else { return nil }
    return BLELifecycleOrigin(deviceID: deviceID, generation: generation, eventTime: originTime)
  }

  func primePhysicalLink(deviceID: UUID, generation: UInt64 = 1, recovering: Bool = false) {
    self.deviceID = deviceID
    self.generation = generation
    originTime = CFAbsoluteTimeGetCurrent()
    self.recovering = recovering
    isConnected = !recovering
  }

  func releaseAllGates() {
    for gate in heldGates {
      release(gate)
    }
    let replies = heldReplies.values.flatMap(\.self)
    heldReplies.removeAll()
    heldReplyCodes.removeAll()
    for reply in replies {
      continuation.yield(reply)
    }
    let cliReplies = heldCLIReplies.values.flatMap(\.self)
    heldCLICommands.removeAll()
    heldCLIReplies.removeAll()
    for reply in cliReplies {
      continuation.yield(reply)
    }
    releaseLoginResults()
  }

  func setDeviceID(_ id: UUID) {
    deviceID = id
  }

  func setPublicKey(_ key: Data, for deviceID: UUID) {
    devicePublicKeys[deviceID] = key
  }

  func setIdentityImportPublicKey(_ key: Data) {
    identityImportPublicKey = key
  }

  func setConnectionInfo(host: String, port: UInt16) {
    connectionInfo = (host, port)
  }

  func setConnectError(_ error: (any Error)?) {
    connectError = error
  }

  func setSendError(_ error: (any Error)?) {
    sendError = error
  }

  func setSendError(_ error: (any Error)?, for command: CommandCode) {
    commandSendErrors[command.rawValue] = error
  }

  func failNextSendAfterSubmission(_ command: CommandCode, error: any Error) {
    postSubmissionSendErrors[command.rawValue] = error
  }

  func setCLIResponse(_ command: String, response: String) {
    cliResponses[command] = response
  }

  func setCLIResponder(_ responder: @escaping @Sendable (String) -> String?) {
    cliResponder = responder
  }

  func holdCLIReplies(for command: String) {
    heldCLICommands.insert(command)
  }

  func heldCLIReplyCount(for command: String) -> Int {
    heldCLIReplies[command]?.count ?? 0
  }

  func releaseCLIReplies(for command: String) {
    heldCLICommands.remove(command)
    for reply in heldCLIReplies.removeValue(forKey: command) ?? [] {
      continuation.yield(reply)
    }
  }

  static func splitCLICommand(_ command: String) -> (prefix: String, command: String) {
    let bytes = Array(command.utf8.prefix(while: { $0 != 0 }).drop(while: { $0 == 0x20 }))
    guard bytes.count > 4, bytes[2] == 0x7C else {
      return ("", String(decoding: bytes, as: UTF8.self))
    }
    return (String(decoding: bytes.prefix(3), as: UTF8.self), String(decoding: bytes.dropFirst(3), as: UTF8.self))
  }

  func setChannel(index: UInt8, name: String, secret: Data) {
    channels[index] = (name, secret)
  }

  func setContact(_ contact: MeshContact) {
    _ = responses(to: PacketBuilder.updateContact(contact))
  }

  func channelMatches(index: UInt8, secret: Data) -> Bool {
    channels[index]?.secret == secret
  }

  func isChannelConfigured(index: UInt8) -> Bool {
    guard let channel = channels[index] else { return false }
    return !channel.name.isEmpty || channel.secret.contains { $0 != 0 }
  }

  func setResponsePackets(for command: CommandCode, packets: [Data]) {
    responsePackets[command.rawValue] = packets
  }

  func enqueueResponsePackets(for command: CommandCode, packets: [Data]) {
    responseSequences[command.rawValue, default: []].append(packets)
  }

  /// The last command's delivered reply. A held or missing reply is `nil` so a test can tell it from a frame that can still match.
  func latestCommandExchange() -> (sent: Bool, response: Data?) {
    guard let code = sentData.last?.first else { return (false, nil) }
    return (true, lastYieldedByCommand[code])
  }

  func setRadioTime(_ date: Date) {
    radioTime = date
  }

  func setTelemetryModeBase(_ mode: UInt8) {
    let mask = OtherParamsIntent.telemetryModeMask
    selfInfoOtherParams[2] = (selfInfoOtherParams[2] & ~mask) | (mode & mask)
  }

  func rejectNext(_ command: CommandCode, count: Int = 1, code: UInt8 = 1) {
    rejection[command.rawValue] = (count, code)
  }

  func holdConnect() {
    heldGates.insert(.connect)
  }

  func releaseConnect() {
    release(.connect)
  }

  func holdDisconnect() {
    heldGates.insert(.disconnect)
  }

  func releaseDisconnect() {
    release(.disconnect)
  }

  func holdNextSubmission() {
    heldGates.insert(.submission)
  }

  func releaseSubmission() {
    release(.submission)
  }

  func holdNextSendCompletion() {
    heldGates.insert(.completion)
  }

  func releaseSendCompletion() {
    release(.completion)
  }

  func holdReplies(for command: CommandCode) {
    heldReplyCodes.insert(command.rawValue)
  }

  func heldReplyCount(for command: CommandCode) -> Int {
    heldReplies[command.rawValue]?.count ?? 0
  }

  func holdSubmissions(for command: CommandCode) {
    heldGates.insert(.commandSubmission(command.rawValue))
  }

  func submissionIsHeld(for command: CommandCode) -> Bool {
    waiters[.commandSubmission(command.rawValue)]?.isEmpty == false
  }

  func releaseSubmissions(for command: CommandCode) {
    release(.commandSubmission(command.rawValue))
  }

  func holdLoginResults() {
    holdsLoginResults = true
  }

  func releaseLoginResults() {
    holdsLoginResults = false
    let results = heldLoginResults
    heldLoginResults.removeAll()
    for result in results {
      continuation.yield(result)
    }
  }

  func releaseReplies(for command: CommandCode) {
    heldReplyCodes.remove(command.rawValue)
    for reply in heldReplies.removeValue(forKey: command.rawValue) ?? [] {
      continuation.yield(reply)
    }
  }

  func makeSessionTransport(deviceID: UUID, binding: SessionTransportBinding) -> SessionBoundTransport {
    try? binding.performIfActive {
      if self.binding !== binding { self.binding?.revoke() }
      self.deviceID = deviceID
      self.sessionDeviceIDs.append(deviceID)
      self.binding = binding
    }
    return SessionBoundTransport(transport: self, binding: binding)
  }

  func connect() async throws {
    try await performConnect(binding: nil)
  }

  func connect(sessionBinding: SessionTransportBinding) async throws {
    try sessionBinding.performIfActive {
      if binding !== sessionBinding { binding?.revoke(); binding = sessionBinding }
    }
    try await performConnect(binding: sessionBinding)
  }

  private func performConnect(binding: SessionTransportBinding?) async throws {
    try binding?.checkActive()
    if isConnected { return }
    connectCount += 1
    await wait(.connect)
    try binding?.checkActive()
    if let connectError { throw connectError }
    if streamNeedsRenewal { refreshDataStream() }
    let submit = {
      self.isConnected = true
      self.recovering = false
      self.generation += 1
      self.originTime = CFAbsoluteTimeGetCurrent()
    }
    if let binding { try binding.performIfActive(submit) } else { submit() }
  }

  func disconnect() async {
    disconnectCount += 1
    await wait(.disconnect)
    isConnected = false
    recovering = false
    continuation.finish()
    streamNeedsRenewal = true
  }

  func disconnect(sessionBinding: SessionTransportBinding) async {
    guard binding === sessionBinding else { return }
    await disconnect()
  }

  func cancelPendingEstablishment(sessionBinding: SessionTransportBinding) {
    guard binding === sessionBinding, !isConnected else { return }
    release(.connect)
  }

  func cancelUnsubmittedWrites(sessionBinding: SessionTransportBinding) {}
  func refreshDataStream() {
    continuation.finish()
    (stream, continuation) = AsyncStream.makeStream(of: Data.self)
    streamNeedsRenewal = false
  }

  func refreshDataStream(sessionBinding: SessionTransportBinding) {
    guard binding === sessionBinding, sessionBinding.isActive else { return }
    refreshDataStream()
  }

  func receivedData(sessionBinding: SessionTransportBinding) -> AsyncStream<Data> {
    guard binding === sessionBinding, sessionBinding.isActive else { return AsyncStream { $0.finish() } }
    return stream
  }

  func send(_ data: Data) async throws {
    try await performSend(data, binding: nil)
  }

  func send(_ data: Data, sessionBinding: SessionTransportBinding) async throws {
    try await performSend(data, binding: sessionBinding)
  }

  func sendWithoutResponse(_ data: Data, sessionBinding: SessionTransportBinding) async throws {
    try await performSend(data, binding: sessionBinding)
  }

  private func performSend(_ data: Data, binding: SessionTransportBinding?) async throws {
    if let code = data.first { await wait(.commandSubmission(code)) }
    await wait(.submission)
    try binding?.checkActive()
    guard isConnected else { throw MeshTransportError.notConnected }
    if let error = data.first.flatMap({ commandSendErrors[$0] }) {
      failedSendCount += 1
      throw error
    }
    if let sendError {
      failedSendCount += 1
      throw sendError
    }
    let submit = {
      let responses = self.receiveFramedCommand(data)
      guard let code = data.first else { return }
      var delivered: Data?
      for response in responses {
        if self.holdsLoginResults, response.first == ResponseCode.loginSuccess.rawValue {
          self.heldLoginResults.append(response)
        } else if code == CommandCode.runCliCommand.rawValue,
                  self.heldCLICommands.contains(Self.splitCLICommand(String(decoding: data.dropFirst(), as: UTF8.self)).command) {
          let command = Self.splitCLICommand(String(decoding: data.dropFirst(), as: UTF8.self)).command
          self.heldCLIReplies[command, default: []].append(response)
        } else if self.heldReplyCodes.contains(code) {
          self.heldReplies[code, default: []].append(response)
        } else {
          delivered = response
          if code == CommandCode.getChannel.rawValue, response.first == ResponseCode.channelInfo.rawValue {
            self.yieldedChannelInfoCount += 1
          }
          self.continuation.yield(response)
        }
      }
      if let delivered {
        self.lastYieldedByCommand[code] = delivered
      } else if !self.heldReplyCodes.contains(code) {
        self.lastYieldedByCommand.removeValue(forKey: code)
      }
    }
    if let binding { try binding.performIfActive(submit) } else { submit() }
    let postSubmissionError = data.first.flatMap { postSubmissionSendErrors.removeValue(forKey: $0) }
    await wait(.completion)
    if let postSubmissionError {
      failedSendCount += 1
      throw postSubmissionError
    }
  }

  func setLifecycleHandler(_ handler: @escaping @Sendable (BLELifecycleEvent) -> Void) {
    lifecycleHandler = handler
  }

  func setDisconnectionHandler(_ handler: @escaping @Sendable (Error?, CFAbsoluteTime) -> Void) {
    socketDisconnectionHandler = handler
  }

  func simulateAutoReconnecting(deviceID: UUID) {
    self.deviceID = deviceID
    binding?.revoke()
    isConnected = false
    recovering = true
    generation += 1
    originTime = CFAbsoluteTimeGetCurrent()
    continuation.finish()
    lifecycleHandler?(.autoReconnecting(lifecycleOrigin!, errorInfo: "radio link lost"))
  }

  func simulateReconnected() {
    guard let deviceID else { return }
    isConnected = true
    recovering = false
    refreshDataStream()
    let origin = BLELifecycleOrigin(deviceID: deviceID, generation: generation, eventTime: CFAbsoluteTimeGetCurrent())
    lifecycleHandler?(.reconnected(origin))
  }

  func simulateRemoteLoss(error: BLEError? = nil) {
    let timestamp = CFAbsoluteTimeGetCurrent()
    binding?.revoke()
    if let deviceID {
      let origin = BLELifecycleOrigin(deviceID: deviceID, generation: generation, eventTime: timestamp)
      lifecycleHandler?(.remoteLinkLost(origin))
      lifecycleHandler?(.disconnected(origin, error: error))
    }
    isConnected = false
    continuation.finish()
    streamNeedsRenewal = true
    socketDisconnectionHandler?(error, timestamp)
  }

  func simulateSocketReceiveLoss(error: (any Error)? = MeshTransportError.notConnected) {
    isConnected = false
    continuation.finish()
    streamNeedsRenewal = true
    socketDisconnectionHandler?(error, CFAbsoluteTimeGetCurrent())
  }

  func inject(_ packet: Data) {
    continuation.yield(packet)
  }

  func receiveFramedCommand(_ data: Data) -> [Data] {
    sentData.append(data)
    return responses(to: data)
  }

  private func wait(_ gate: Gate) async {
    guard heldGates.contains(gate) else { return }
    await withCheckedContinuation { waiters[gate, default: []].append($0) }
  }

  private func release(_ gate: Gate) {
    heldGates.remove(gate)
    let pending = waiters.removeValue(forKey: gate) ?? []
    for continuation in pending {
      continuation.resume()
    }
  }

  private func responses(to data: Data) -> [Data] {
    guard let rawCode = data.first, let code = CommandCode(rawValue: rawCode) else { return [] }
    if responseSequences[rawCode]?.isEmpty == false {
      return responseSequences[rawCode]!.removeFirst()
    }
    if let reject = rejection[rawCode], reject.remaining > 0 {
      rejection[rawCode] = (reject.remaining - 1, reject.code)
      return [Data([ResponseCode.error.rawValue, reject.code])]
    }
    if let packets = responsePackets[rawCode] { return packets }
    if let response = configurationResponse(to: code, data: data) { return [response] }
    switch code {
    case .deviceQuery:
      var reply = Data([ResponseCode.deviceInfo.rawValue, firmwareVersion, 1, maxChannels])
      reply.append(Data(repeating: 0, count: 76))
      return [reply]
    case .getContacts:
      let count = UInt32(contacts.count).littleEndian
      let start = Data([ResponseCode.contactStart.rawValue]) + withUnsafeBytes(of: count) { Data($0) }
      let rows = contacts.sorted { $0.key.lexicographicallyPrecedes($1.key) }.map {
        Data([ResponseCode.contact.rawValue]) + $0.value
      }
      return [start] + rows + [Data([ResponseCode.contactEnd.rawValue, 1, 0, 0, 0])]
    case .getContactByKey:
      let key = Data(data.dropFirst().prefix(ProtocolLimits.publicKeySize))
      guard let contact = contacts[key] else {
        return [Data([ResponseCode.error.rawValue, ProtocolError.notFound.rawValue])]
      }
      return [Data([ResponseCode.contact.rawValue]) + contact]
    case .updateContact:
      guard data.count >= 144 else { return [Data([ResponseCode.error.rawValue, 1])] }
      let key = Data(data.dropFirst().prefix(32))
      contacts[key] = Data(data.dropFirst().prefix(143)) + Data([1, 0, 0, 0])
      return [Data([ResponseCode.ok.rawValue])]
    case .getChannel:
      let index = data.count > 1 ? data[1] : 0
      let channel = channels[index]
      let name = Data((channel?.name ?? "").utf8.prefix(32))
      let secret = Data((channel?.secret ?? Data()).prefix(16))
      return [Data([ResponseCode.channelInfo.rawValue, index])
        + name + Data(repeating: 0, count: 32 - name.count)
        + secret + Data(repeating: 0, count: 16 - secret.count)]
    case .setChannel:
      guard data.count >= 50, data[1] < maxChannels else {
        return [Data([ResponseCode.error.rawValue, 1])]
      }
      let index = data[1]
      let name = String(decoding: data.dropFirst(2).prefix(32).prefix { $0 != 0 }, as: UTF8.self)
      channels[index] = (name, Data(data.dropFirst(34).prefix(16)))
      channelWriteIndices.append(index)
      return [Data([ResponseCode.ok.rawValue])]
    case .sendChannelMessage:
      if data.count > 2, !isChannelConfigured(index: data[2]) { unconfiguredChannelSendCount += 1 }
      return [Data([ResponseCode.ok.rawValue])]
    case .getMessage: return [Data([ResponseCode.noMoreMessages.rawValue])]
    case .getAutoAddConfig: return [Data([ResponseCode.autoAddConfig.rawValue, 0])]
    case .getBattery: return [Data([ResponseCode.battery.rawValue, 0x74, 0x0E])]
    case .exportPrivateKey: return [Data([ResponseCode.disabled.rawValue])]
    case .getTime:
      timeReads += 1
      var reply = Data([ResponseCode.currentTime.rawValue])
      reply.append(withUnsafeBytes(of: UInt32(max(0, radioTime.timeIntervalSince1970)).littleEndian) { Data($0) })
      return [reply]
    case .setTime:
      timeWrites += 1
      if data.count >= 5 {
        let seconds = data.dropFirst().prefix(4).enumerated().reduce(UInt32(0)) { value, element in
          value | (UInt32(element.element) << (8 * element.offset))
        }
        radioTime = Date(timeIntervalSince1970: TimeInterval(seconds))
      }
      return [Data([ResponseCode.ok.rawValue])]
    case .runCliCommand:
      let command = String(decoding: data.dropFirst(), as: UTF8.self)
      cliCommands.append(command)
      let parsed = Self.splitCLICommand(command)
      if parsed.command == PacketBuilder.powerOffCommand, cliResponses[command] == nil { return [] }
      let firmwareCommand = String(decoding: command.utf8.prefix(while: { $0 != 0 }), as: UTF8.self)
      if let text = cliResponder?(firmwareCommand) {
        return [Data([ResponseCode.cliReply.rawValue]) + Data(text.utf8)]
      }
      let body = cliResponses[command] ?? cliResponses[parsed.command]
        ?? (parsed.command.hasPrefix("get ") ? "> 0" : "OK")
      let text = parsed.prefix.isEmpty || body.hasPrefix(parsed.prefix) ? body : parsed.prefix + body
      return [Data([ResponseCode.cliReply.rawValue]) + Data(text.utf8)]
    case .sendLogin:
      let publicKey = Data(data.dropFirst().prefix(ProtocolLimits.publicKeySize))
      guard publicKey.count == ProtocolLimits.publicKeySize, contacts[publicKey] != nil else {
        return [Data([ResponseCode.error.rawValue, ProtocolError.notFound.rawValue])]
      }
      let destination = Data(publicKey.prefix(6))
      return [
        Data([ResponseCode.messageSent.rawValue, 0, 1, 2, 3, 4, 0x88, 0x13, 0, 0]),
        Data([ResponseCode.loginSuccess.rawValue, 0]) + destination
      ]
    default: return [Data([ResponseCode.ok.rawValue])]
    }
  }

  private func configurationResponse(to code: CommandCode, data: Data) -> Data? {
    switch code {
    case .importPrivateKey:
      if let identityImportPublicKey {
        importedPublicKey = identityImportPublicKey
        if let deviceID { devicePublicKeys[deviceID] = identityImportPublicKey }
      }
      return Data([ResponseCode.ok.rawValue])
    case .setName:
      selfInfoName = String(decoding: data.dropFirst().prefix { $0 != 0 }, as: UTF8.self)
      return Data([ResponseCode.ok.rawValue])
    case .setCoordinates:
      if data.count >= 9 { selfInfoCoordinates = Data(data.dropFirst().prefix(8)) }
      return Data([ResponseCode.ok.rawValue])
    case .setOtherParams:
      if data.count >= 4 {
        if data.count >= 5 { selfInfoOtherParams[0] = data[4] }
        selfInfoOtherParams[1] = data[3]
        selfInfoOtherParams[2] = data[2]
        selfInfoOtherParams[3] = data[1]
      }
      return Data([ResponseCode.ok.rawValue])
    case .setRadio:
      if data.count >= 11 { selfInfoRadio = Data(data.dropFirst().prefix(10)) }
      return Data([ResponseCode.ok.rawValue])
    case .setTxPower:
      if data.count >= 2 { selfInfoTxPower = data[1] }
      return Data([ResponseCode.ok.rawValue])
    case .appStart: return selfInfoPacket()
    default: return nil
    }
  }

  private func selfInfoPacket() -> Data {
    var reply = Data([ResponseCode.selfInfo.rawValue, 1, selfInfoTxPower, 22])
    reply.append(deviceID.flatMap { devicePublicKeys[$0] } ?? importedPublicKey ?? publicKey)
    reply.append(selfInfoCoordinates)
    reply.append(selfInfoOtherParams)
    reply.append(selfInfoRadio)
    reply.append(contentsOf: selfInfoName.utf8)
    return reply
  }
}
