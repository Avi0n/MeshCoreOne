import Foundation
import MeshCore

/// A synchronous, callback-shaped facade over a single ``MeshCoreSession``.
///
/// MeshCore's API is actor-isolated and `async`; a JNI boundary can export
/// neither. This class bridges the gap:
///
/// - **Commands are fire-and-forget.** Each entry point takes primitive
///   arguments plus a caller-chosen `requestID`, enqueues an internal `Task`, and
///   returns immediately. The outcome arrives later on the ``BridgeResultListener``
///   keyed by that id. The calling thread is never blocked.
/// - **Events are pushed.** One internal task drains the session's event stream
///   and forwards every event to the ``BridgeEventListener`` as encoded bytes.
/// - **The transport lives in Kotlin.** Outbound frames go to the
///   ``BridgeSendListener``; inbound frames enter via ``receiveBytes(_:)``. The
///   physical link's lifecycle is reported through ``reportConnected()`` and
///   friends.
///
/// All boundary values are the versioned JSON envelope documented in
/// `ENCODING.md`. The type is `@unchecked Sendable`: it holds only immutable
/// references (the session and transport actors serialize their own state, the
/// listeners are `Sendable`), so it is safe to call from any thread.
public final class MeshCoreBridge: @unchecked Sendable {
  private let session: MeshCoreSession
  private let transport: KotlinTransport
  private let resultListener: any BridgeResultListener
  private let eventListener: any BridgeEventListener
  private let log: BridgeLog
  private let requests = BridgeRequests()

  private let eventForwardingTask: Task<Void, Never>

  /// Creates a bridge over a fresh session.
  ///
  /// - Parameters:
  ///   - sendListener: Receives outbound frames to write to the radio link.
  ///   - resultListener: Receives command outcomes.
  ///   - eventListener: Receives pushed device events.
  ///   - logListener: Receives bridge and (on non-Apple platforms) MeshCore logs.
  ///   - advertisesWriteWithoutResponse: Seed only. Live capability is
  ///     ``BridgeSendListener/advertisesWriteWithoutResponse`` after GATT
  ///     discovery (iOS `captureWriteWithoutResponseCapability`). Default
  ///     false matches Swift `MeshTransport` (ESP32-safe serial reads).
  ///   - clientIdentifier: The client id sent in the app-start handshake.
  public init(
    sendListener: any BridgeSendListener,
    resultListener: any BridgeResultListener,
    eventListener: any BridgeEventListener,
    logListener: any BridgeLogListener,
    advertisesWriteWithoutResponse: Bool = false,
    clientIdentifier: String = "MeshCore-Kotlin"
  ) {
    let transport = KotlinTransport(sendListener: sendListener)
    _ = advertisesWriteWithoutResponse
    let configuration = SessionConfiguration(clientIdentifier: clientIdentifier)
    let session = MeshCoreSession(transport: transport, configuration: configuration)
    let log = BridgeLog(listener: logListener)
    log.install()

    self.transport = transport
    self.session = session
    self.resultListener = resultListener
    self.eventListener = eventListener
    self.log = log

    // Subscribe before any command can run so no event is missed once the
    // device connects. Device traffic only flows after the connect handshake,
    // which cannot begin until a caller invokes startSession.
    eventForwardingTask = Task {
      let stream = await session.events()
      for await event in stream {
        eventListener.onEvent(eventBytes: MeshEventEncoder.encode(event).serialized())
      }
    }
  }

  deinit {
    eventForwardingTask.cancel()
    requests.cancelAll()
    let session = session
    Task { await session.stop() }
  }

  /// Cancels one command without abandoning its outstanding response slot.
  public func cancelRequest(requestID: Int64) {
    requests.cancel(requestID)
  }

  // MARK: - Transport lifecycle (from Kotlin)

  /// Reports that the physical link is established, unblocking a pending
  /// `startSession` handshake.
  public func reportConnected() {
    Task { await transport.reportConnected() }
  }

  /// Reports that the connect attempt failed.
  public func reportConnectFailed(message: String) {
    Task { await transport.reportConnectFailed(message) }
  }

  /// Reports that the link dropped after being established.
  public func reportDisconnected() {
    Task { await transport.reportDisconnected() }
  }

  /// Delivers one complete inbound frame from the radio link.
  public func receiveBytes(_ bytes: [UInt8]) {
    transport.deliverReceived(bytes)
  }

  // MARK: - Session lifecycle

  /// Connects and runs the app-start handshake. Succeeds with the device's
  /// `selfInfo`. Requires the caller to drive the link up via
  /// ``reportConnected()`` in response to the ``BridgeSendListener`` traffic.
  ///
  /// Kotlin `getSelfInfo` uses this same entry. A live session's `start()` is a
  /// no-op, so a second call re-sends app-start and returns the fresh snapshot.
  public func startSession(requestID: Int64) {
    dispatch(requestID) { [session] in
      if await session.currentSelfInfo != nil {
        return try await CommandResultEncoder.selfInfo(session.refreshSelfInfo())
      }
      try await session.start()
      guard let info = await session.currentSelfInfo else {
        throw MeshCoreError.invalidResponse(expected: "selfInfo", got: "nil")
      }
      return CommandResultEncoder.selfInfo(info)
    }
  }

  /// Stops the session and disconnects the transport.
  public func stopSession(requestID: Int64) {
    dispatch(requestID) { [session] in
      await session.stop()
      return CommandResultEncoder.ok()
    }
  }

  // MARK: - Device configuration

  public func queryDevice(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.deviceCapabilities(session.queryDevice()) }
  }

  public func getBattery(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.battery(session.getBattery()) }
  }

  public func getTime(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.time(session.getTime()) }
  }

  public func setName(requestID: Int64, name: String) {
    dispatch(requestID) { [session] in
      try await session.setName(name)
      return CommandResultEncoder.ok()
    }
  }

  public func setCoordinates(requestID: Int64, latitude: Double, longitude: Double) {
    dispatch(requestID) { [session] in
      try await session.setCoordinates(latitude: latitude, longitude: longitude)
      return CommandResultEncoder.ok()
    }
  }

  public func setTime(requestID: Int64, timestampEpoch: Int64) {
    let timestamp = Date(timeIntervalSince1970: TimeInterval(timestampEpoch))
    dispatch(requestID) { [session] in
      try await session.setTime(timestamp)
      return CommandResultEncoder.ok()
    }
  }

  public func sendAdvertisement(requestID: Int64, flood: Bool) {
    dispatch(requestID) { [session] in
      try await session.sendAdvertisement(flood: flood)
      return CommandResultEncoder.ok()
    }
  }

  /// Enables the session's internal auto-drain of pending messages on
  /// `messagesWaiting`. Drained messages still surface as events; this only
  /// controls whether the Swift session pulls them automatically.
  public func startAutoMessageFetching(requestID: Int64) {
    dispatch(requestID) { [session] in
      await session.startAutoMessageFetching()
      return CommandResultEncoder.ok()
    }
  }

  public func stopAutoMessageFetching(requestID: Int64) {
    dispatch(requestID) { [session] in
      await session.stopAutoMessageFetching()
      return CommandResultEncoder.ok()
    }
  }

  public func setTxPower(requestID: Int64, power: Int32) {
    dispatch(requestID) { [session] in
      try await session.setTxPower(Int8(clamping: power))
      return CommandResultEncoder.ok()
    }
  }

  public func reboot(requestID: Int64) {
    dispatch(requestID) { [session] in
      try await session.reboot()
      return CommandResultEncoder.ok()
    }
  }

  public func getCustomVars(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.customVars(session.getCustomVars()) }
  }

  public func setCustomVar(requestID: Int64, key: String, value: String) {
    dispatch(requestID) { [session] in
      try await session.setCustomVar(key: key, value: value)
      return CommandResultEncoder.ok()
    }
  }

  public func exportPrivateKey(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.privateKey(session.exportPrivateKey()) }
  }

  public func importPrivateKey(requestID: Int64, key: [UInt8]) {
    dispatch(requestID) { [session] in
      try await session.importPrivateKey(Data(key))
      return CommandResultEncoder.ok()
    }
  }

  public func setDevicePin(requestID: Int64, pin: Int64) {
    dispatch(requestID) { [session] in
      try await session.setDevicePin(UInt32(truncatingIfNeeded: pin))
      return CommandResultEncoder.ok()
    }
  }

  public func setPathHashMode(requestID: Int64, mode: Int32) {
    dispatch(requestID) { [session] in
      try await session.setPathHashMode(UInt8(clamping: mode))
      return CommandResultEncoder.ok()
    }
  }

  public func factoryReset(requestID: Int64) {
    dispatch(requestID) { [session] in
      try await session.factoryReset()
      return CommandResultEncoder.ok()
    }
  }

  /// Configures the LoRa radio. Pass `clientRepeat` as `-1` for "unset" (omit),
  /// `0` for false, `1` for true.
  public func setRadio(requestID: Int64, frequency: Double, bandwidth: Double, spreadingFactor: Int32, codingRate: Int32, clientRepeat: Int32) {
    let repeatFlag: Bool? = clientRepeat < 0 ? nil : (clientRepeat != 0)
    dispatch(requestID) { [session] in
      try await session.setRadio(
        frequency: frequency,
        bandwidth: bandwidth,
        spreadingFactor: UInt8(clamping: spreadingFactor),
        codingRate: UInt8(clamping: codingRate),
        clientRepeat: repeatFlag
      )
      return CommandResultEncoder.ok()
    }
  }

  /// Sets granular device params. Pass `multiAcks` as `-1` for "unset" (omit).
  public func setOtherParams(
    requestID: Int64,
    manualAddContacts: Bool,
    telemetryModeEnvironment: Int32,
    telemetryModeLocation: Int32,
    telemetryModeBase: Int32,
    advertisementLocationPolicy: Int32,
    multiAcks: Int32
  ) {
    let acks: UInt8? = multiAcks < 0 ? nil : UInt8(clamping: multiAcks)
    dispatch(requestID) { [session] in
      try await session.setOtherParams(
        manualAddContacts: manualAddContacts,
        telemetryModeEnvironment: UInt8(clamping: telemetryModeEnvironment),
        telemetryModeLocation: UInt8(clamping: telemetryModeLocation),
        telemetryModeBase: UInt8(clamping: telemetryModeBase),
        advertisementLocationPolicy: UInt8(clamping: advertisementLocationPolicy),
        multiAcks: acks
      )
      return CommandResultEncoder.ok()
    }
  }

  public func getAutoAddConfig(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.autoAddConfig(session.getAutoAddConfig()) }
  }

  public func setAutoAddConfig(requestID: Int64, bitmask: Int32, maxHops: Int32) {
    let config = AutoAddConfig(bitmask: UInt8(clamping: bitmask), maxHops: UInt8(clamping: maxHops))
    dispatch(requestID) { [session] in
      try await session.setAutoAddConfig(config)
      return CommandResultEncoder.ok()
    }
  }

  public func getDefaultFloodScope(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.defaultFloodScope(session.getDefaultFloodScope()) }
  }

  /// Persists a default flood scope. `scopeType` selects the `FloodScope` case:
  /// `"disabled"`, `"channelName"` (uses `scopeText`), `"rawKey"` (uses
  /// `scopeKey` bytes), or `"region"` (uses `scopeText`).
  public func setDefaultFloodScope(requestID: Int64, name: String, scopeType: String, scopeText: String, scopeKey: [UInt8]) {
    dispatch(requestID) { [session] in
      let scope: FloodScope
      switch scopeType {
      case "disabled": scope = .disabled
      case "channelName": scope = .channelName(scopeText)
      case "rawKey": scope = .rawKey(Data(scopeKey))
      case "region": scope = .region(scopeText)
      default: throw MeshCoreError.invalidInput("unknown flood scope type \"\(scopeType)\"")
      }
      try await session.setDefaultFloodScope(name: name, scope: scope)
      return CommandResultEncoder.ok()
    }
  }

  /// Pushes a session-scoped flood key (firmware sub-command 0). `scopeType` selects the
  /// `FloodScope` case exactly as `setDefaultFloodScope`: `"disabled"`, `"channelName"`
  /// (uses `scopeText`), `"rawKey"` (uses `scopeKey` bytes), or `"region"` (uses `scopeText`).
  public func setFloodScope(requestID: Int64, scopeType: String, scopeText: String, scopeKey: [UInt8]) {
    dispatch(requestID) { [session] in
      let scope: FloodScope
      switch scopeType {
      case "disabled": scope = .disabled
      case "channelName": scope = .channelName(scopeText)
      case "rawKey": scope = .rawKey(Data(scopeKey))
      case "region": scope = .region(scopeText)
      default: throw MeshCoreError.invalidInput("unknown flood scope type \"\(scopeType)\"")
      }
      try await session.setFloodScope(scope)
      return CommandResultEncoder.ok()
    }
  }

  /// Forces un-scoped flood broadcasts (firmware sub-command 1, requires firmware v12+). Older
  /// firmware rejects it as `deviceError`; the caller gates on capability before invoking.
  public func setFloodScopeUnscoped(requestID: Int64) {
    dispatch(requestID) { [session] in
      try await session.setFloodScopeUnscoped()
      return CommandResultEncoder.ok()
    }
  }

  public func getRepeatFreq(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.allowedRepeatFreq(session.getRepeatFreq()) }
  }

  public func getStatsCore(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.statsCore(session.getStatsCore()) }
  }

  public func getStatsRadio(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.statsRadio(session.getStatsRadio()) }
  }

  public func getStatsPackets(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.statsPackets(session.getStatsPackets()) }
  }

  // MARK: - Contacts

  /// Fetches contacts modified since `sinceEpoch` seconds (pass a negative value
  /// for the full list).
  public func getContacts(requestID: Int64, sinceEpoch: Int64) {
    let since = sinceEpoch < 0 ? nil : Date(timeIntervalSince1970: TimeInterval(sinceEpoch))
    dispatch(requestID) { [session] in
      let result = try await session.getContactsReportingTotal(since: since)
      return CommandResultEncoder.contacts(result.contacts, reportedTotal: result.reportedTotal)
    }
  }

  public func getContact(requestID: Int64, publicKey: [UInt8]) {
    dispatch(requestID) { [session] in
      try await CommandResultEncoder.contactOptional(session.getContact(publicKey: Data(publicKey)))
    }
  }

  public func removeContact(requestID: Int64, publicKey: [UInt8]) {
    dispatch(requestID) { [session] in
      try await session.removeContact(publicKey: Data(publicKey))
      return CommandResultEncoder.ok()
    }
  }

  public func resetPath(requestID: Int64, publicKey: [UInt8]) {
    dispatch(requestID) { [session] in
      try await session.resetPath(publicKey: Data(publicKey))
      return CommandResultEncoder.ok()
    }
  }

  /// Exports a contact URI. Pass an empty `publicKey` to export this device's own
  /// contact card.
  public func exportContact(requestID: Int64, publicKey: [UInt8]) {
    let key = publicKey.isEmpty ? nil : Data(publicKey)
    dispatch(requestID) { [session] in try await CommandResultEncoder.contactURI(session.exportContact(publicKey: key)) }
  }

  public func importContact(requestID: Int64, cardData: [UInt8]) {
    dispatch(requestID) { [session] in
      try await session.importContact(cardData: Data(cardData))
      return CommandResultEncoder.ok()
    }
  }

  /// Adds a contact. `contactBytes` is a bridge `contact` envelope (the same
  /// shape a `contact` event or `contacts` result carries) — see
  /// ``ContactDecoder``. `addContact` takes a full `MeshContact`, so the Kotlin
  /// side round-trips the contact it already holds rather than reassembling it
  /// from a wide argument list.
  public func addContactEncoded(requestID: Int64, contactBytes: [UInt8]) {
    dispatch(requestID) { [session] in
      let contact = try ContactDecoder.decode(envelope: contactBytes)
      try await session.addContact(contact)
      return CommandResultEncoder.ok()
    }
  }

  /// Updates a contact's flags. `contactBytes` is a bridge `contact` envelope.
  public func changeContactFlags(requestID: Int64, contactBytes: [UInt8], flags: Int32) {
    dispatch(requestID) { [session] in
      let contact = try ContactDecoder.decode(envelope: contactBytes)
      try await session.changeContactFlags(contact, flags: ContactFlags(rawValue: UInt8(clamping: flags)))
      return CommandResultEncoder.ok()
    }
  }

  public func shareContact(requestID: Int64, publicKey: [UInt8]) {
    dispatch(requestID) { [session] in
      try await session.shareContact(publicKey: Data(publicKey))
      return CommandResultEncoder.ok()
    }
  }

  public func sendPathDiscovery(requestID: Int64, destination: [UInt8]) {
    dispatch(requestID) { [session] in
      try await CommandResultEncoder.messageSent(session.sendPathDiscovery(to: Data(destination)))
    }
  }

  /// Active mesh node discovery (`nodeDiscoverRequest`). Returns the correlation tag for
  /// matching subsequent `discoverResponse` events. `tag` / `sinceEpoch` use `-1` for unset
  /// (firmware generates a tag; no `since` filter).
  public func sendNodeDiscoverRequest(
    requestID: Int64,
    filter: Int32,
    prefixOnly: Bool,
    tag: Int64,
    sinceEpoch: Int64
  ) {
    dispatch(requestID) { [session] in
      let actualTag: UInt32? = tag < 0 ? nil : UInt32(clamping: UInt64(tag))
      let since: Date? = sinceEpoch < 0 ? nil : Date(timeIntervalSince1970: TimeInterval(sinceEpoch))
      let result = try await session.sendNodeDiscoverRequest(
        filter: UInt8(clamping: filter),
        prefixOnly: prefixOnly,
        tag: actualTag,
        since: since
      )
      return CommandResultEncoder.discoverTag(result)
    }
  }

  /// Queries a repeater for its region list. `contactBytes` is a bridge `contact`
  /// envelope; the contact must carry a full 32-byte public key.
  public func requestRegions(requestID: Int64, contactBytes: [UInt8]) {
    dispatch(requestID) { [session] in
      let contact = try ContactDecoder.decode(envelope: contactBytes)
      return try await CommandResultEncoder.regions(session.requestRegions(from: contact))
    }
  }

  // MARK: - Channels

  public func getChannel(requestID: Int64, index: Int32) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.channelInfo(session.getChannel(index: UInt8(clamping: index))) }
  }

  public func getChannels(requestID: Int64, indices: [UInt8]) {
    dispatch(requestID) { [session] in
      let result = try await session.getChannels(indices: indices)
      return CommandResultEncoder.channels(received: result.received, missing: result.missing)
    }
  }

  public func setChannel(requestID: Int64, index: Int32, name: String, secret: [UInt8]) {
    dispatch(requestID) { [session] in
      try await session.setChannel(index: UInt8(clamping: index), name: name, secret: Data(secret))
      return CommandResultEncoder.ok()
    }
  }

  // MARK: - Messaging

  public func sendMessage(requestID: Int64, destination: [UInt8], text: String, timestampEpoch: Int64, attempt: Int32) {
    let timestamp = Date(timeIntervalSince1970: TimeInterval(timestampEpoch))
    dispatch(requestID) { [session] in
      let info = try await session.sendMessage(
        to: Data(destination),
        text: text,
        timestamp: timestamp,
        attempt: UInt8(clamping: attempt)
      )
      return CommandResultEncoder.messageSent(info)
    }
  }

  public func sendChannelMessage(requestID: Int64, channel: Int32, text: String, timestampEpoch: Int64) {
    let timestamp = Date(timeIntervalSince1970: TimeInterval(timestampEpoch))
    dispatch(requestID) { [session] in
      try await session.sendChannelMessage(channel: UInt8(clamping: channel), text: text, timestamp: timestamp)
      return CommandResultEncoder.ok()
    }
  }

  /// Applies a resolved flood scope and sends in one serialized Swift operation.
  public func sendChannelMessageWithScope(
    requestID: Int64,
    channel: Int32,
    text: String,
    timestampEpoch: Int64,
    scopeType: String,
    scopeText: String
  ) {
    let timestamp = Date(timeIntervalSince1970: TimeInterval(timestampEpoch))
    dispatch(requestID) { [session] in
      let scope: ResolvedFloodScope
      switch scopeType {
      case "disabled": scope = .scope(.disabled)
      case "region": scope = .scope(.region(scopeText))
      case "unscoped": scope = .unscoped
      default: throw MeshCoreError.invalidInput("unknown resolved flood scope type \"\(scopeType)\"")
      }
      try await session.sendChannelMessage(
        channel: UInt8(clamping: channel), text: text, timestamp: timestamp, floodScope: scope
      )
      return CommandResultEncoder.ok()
    }
  }

  public func getMessage(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.messageResult(session.getMessage()) }
  }

  // MARK: - Remote nodes / diagnostics

  /// Requests a status frame. `nodeType` is a `ContactType` raw value and selects the
  /// firmware status layout: a room server packs its posted and post-push counters into
  /// the field a repeater uses for RX airtime, so the layout must be chosen at request
  /// time. An unrecognized value falls back to the repeater layout.
  public func requestStatus(requestID: Int64, publicKey: [UInt8], nodeType: Int32) {
    let type = ContactType(rawValue: UInt8(clamping: nodeType)) ?? .repeater
    dispatch(requestID) { [session] in
      try await CommandResultEncoder.statusResponse(session.requestStatus(from: Data(publicKey), type: type))
    }
  }

  public func requestTelemetry(requestID: Int64, publicKey: [UInt8]) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.telemetryResponse(session.requestTelemetry(from: Data(publicKey))) }
  }

  public func getSelfTelemetry(requestID: Int64) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.telemetryResponse(session.getSelfTelemetry()) }
  }

  public func requestMMA(requestID: Int64, publicKey: [UInt8], startEpoch: Int64, endEpoch: Int64) {
    let start = Date(timeIntervalSince1970: TimeInterval(startEpoch))
    let end = Date(timeIntervalSince1970: TimeInterval(endEpoch))
    dispatch(requestID) { [session] in
      try await CommandResultEncoder.mmaResponse(session.requestMMA(from: Data(publicKey), start: start, end: end))
    }
  }

  public func requestACL(requestID: Int64, publicKey: [UInt8]) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.aclResponse(session.requestACL(from: Data(publicKey))) }
  }

  public func requestNeighbours(requestID: Int64, publicKey: [UInt8], count: Int32, offset: Int32, orderBy: Int32, pubkeyPrefixLength: Int32) {
    dispatch(requestID) { [session] in
      let response = try await session.requestNeighbours(
        from: Data(publicKey),
        count: UInt8(clamping: count),
        offset: UInt16(clamping: offset),
        orderBy: UInt8(clamping: orderBy),
        pubkeyPrefixLength: UInt8(clamping: pubkeyPrefixLength)
      )
      return CommandResultEncoder.neighboursResponse(response)
    }
  }

  public func fetchAllNeighbours(requestID: Int64, publicKey: [UInt8], orderBy: Int32, pubkeyPrefixLength: Int32) {
    dispatch(requestID) { [session] in
      let response = try await session.fetchAllNeighbours(
        from: Data(publicKey),
        orderBy: UInt8(clamping: orderBy),
        pubkeyPrefixLength: UInt8(clamping: pubkeyPrefixLength)
      )
      return CommandResultEncoder.neighboursResponse(response)
    }
  }

  public func requestOwnerInfo(requestID: Int64, publicKey: [UInt8]) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.ownerInfo(session.requestOwnerInfo(from: Data(publicKey))) }
  }

  public func sendKeepAlive(requestID: Int64, publicKey: [UInt8], syncSince: Int64) {
    dispatch(requestID) { [session] in
      let info = try await session.sendKeepAlive(to: Data(publicKey), syncSince: UInt32(truncatingIfNeeded: syncSince))
      return CommandResultEncoder.messageSent(info)
    }
  }

  /// Sends a trace. Pass a negative `tag`/`authCode` to let the firmware/library
  /// choose one; pass an empty `path` for the default single-byte path.
  public func sendTrace(requestID: Int64, tag: Int64, authCode: Int64, flags: Int32, path: [UInt8]) {
    let resolvedTag = tag < 0 ? nil : UInt32(truncatingIfNeeded: tag)
    let resolvedAuth = authCode < 0 ? nil : UInt32(truncatingIfNeeded: authCode)
    let resolvedPath = path.isEmpty ? nil : Data(path)
    dispatch(requestID) { [session] in
      let info = try await session.sendTrace(
        tag: resolvedTag,
        authCode: resolvedAuth,
        flags: UInt8(clamping: flags),
        path: resolvedPath
      )
      return CommandResultEncoder.messageSent(info)
    }
  }

  public func sendLogin(requestID: Int64, destination: [UInt8], password: String) {
    dispatch(requestID) { [session] in
      try await CommandResultEncoder.messageSent(session.sendLogin(to: Data(destination), password: password))
    }
  }

  public func sendLogout(requestID: Int64, destination: [UInt8]) {
    dispatch(requestID) { [session] in
      try await session.sendLogout(to: Data(destination))
      return CommandResultEncoder.ok()
    }
  }

  public func sendCommand(requestID: Int64, destination: [UInt8], command: String, timestampEpoch: Int64) {
    let timestamp = Date(timeIntervalSince1970: TimeInterval(timestampEpoch))
    dispatch(requestID) { [session] in
      try await CommandResultEncoder.messageSent(session.sendCommand(to: Data(destination), command: command, timestamp: timestamp))
    }
  }

  // MARK: - Signing

  public func sign(requestID: Int64, data: [UInt8]) {
    dispatch(requestID) { [session] in try await CommandResultEncoder.signature(session.sign(Data(data))) }
  }

  // MARK: - Dispatch

  private func dispatch(_ requestID: Int64, _ operation: @escaping @Sendable () async throws -> JSONValue) {
    let resultListener = resultListener
    let transport = transport
    requests.dispatch(requestID) {
      do {
        try Task.checkCancellation()
        let result = try await operation()
        resultListener.onSuccess(requestID: requestID, resultBytes: result.serialized())
      } catch {
        var classified = BridgeErrorCode.classify(error)
        if error is CancellationError, await !transport.isConnected {
          classified = BridgeErrorCode.classify(MeshTransportError.notConnected)
        }
        resultListener.onFailure(requestID: requestID, errorCode: classified.code.rawValue, message: classified.message)
      }
    }
  }
}
