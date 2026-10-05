import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "NodeSettingsViewModel")

/// Shared logic for repeater and room settings view models.
/// Owns CLI transport, device info, radio, identity, contact info,
/// security, and device action methods.
@Observable
@MainActor
final class NodeSettingsViewModel {
  // MARK: - Session

  var session: RemoteNodeSessionDTO?

  // MARK: - Device Info

  var firmwareVersion: String?
  private var deviceTimeUTC: String?
  /// Positive means the node's clock is ahead of `now`.
  var clockDrift: TimeInterval?
  /// Reference clock used when measuring `clockDrift`.
  var now: () -> Date = Date.init
  var isLoadingDeviceInfo = false
  var deviceInfoError = false
  var deviceInfoLoaded: Bool {
    deviceTimeUTC != nil
  }

  var deviceTime: String? {
    guard let utcString = deviceTimeUTC else { return nil }
    return Self.convertUTCToLocal(utcString)
  }

  static func convertUTCToLocal(_ utcString: String) -> String {
    guard let date = NodeSettingsResponseParser.utcDate(fromClockResponse: utcString) else {
      return utcString
    }

    let timeString = date.formatted(date: .omitted, time: .shortened)
    let dateString = date.formatted(.dateTime.year(.twoDigits).month(.twoDigits).day(.twoDigits))
    return "\(timeString) - \(dateString)"
  }

  func applyDeviceTime(_ raw: String) {
    if let text = NodeSettingsResponseParser.clockResponseText(in: raw) {
      deviceTimeUTC = text
      clockDrift = NodeSettingsResponseParser.clockDrift(fromClockResponse: text, relativeTo: now())
    }
  }

  // MARK: - Identity

  var name: String?
  var latitude: Double?
  var longitude: Double?
  /// Last value assigned by a load or successful `set name`, not by typing.
  private(set) var nameBaseline: String?
  private(set) var originalName: String?
  var originalLatitude: Double?
  var originalLongitude: Double?
  var isLoadingIdentity = false
  var identityError = false
  var identityLoaded: Bool {
    originalLatitude != nil || originalLongitude != nil
  }

  var identitySettingsModified: Bool {
    (name != nameBaseline) ||
      (latitude != nil && latitude != originalLatitude) ||
      (longitude != nil && longitude != originalLongitude)
  }

  var nameError: String?
  var latitudeError: String?
  var longitudeError: String?

  // MARK: - Radio

  var frequency: Double?
  var bandwidth: Double?
  var spreadingFactor: Int?
  var codingRate: Int?
  var originalFrequency: Double?
  var originalBandwidth: Double?
  var originalSpreadingFactor: Int?
  var originalCodingRate: Int?
  var isLoadingRadio = false
  var radioError = false
  var radioLoaded: Bool {
    frequency != nil
  }

  var radioSettingsModified: Bool {
    (frequency != nil && frequency != originalFrequency) ||
      (bandwidth != nil && bandwidth != originalBandwidth) ||
      (spreadingFactor != nil && spreadingFactor != originalSpreadingFactor) ||
      (codingRate != nil && codingRate != originalCodingRate)
  }

  // MARK: - Contact Info

  /// Firmware limit on the `set owner.info` value length.
  static let ownerInfoMaxLength = 119

  var ownerInfo: String?
  private(set) var originalOwnerInfo: String?
  var isLoadingContactInfo = false
  var contactInfoError = false
  var contactInfoLoaded: Bool {
    originalOwnerInfo != nil
  }

  /// Gated on `contactInfoLoaded` so the text field's empty pre-fetch value can't
  /// enable Apply and wipe the node's owner info before the current value arrives.
  var contactInfoSettingsModified: Bool {
    contactInfoLoaded && ownerInfo != originalOwnerInfo
  }

  var ownerInfoCharCount: Int {
    (ownerInfo ?? "").count
  }

  var isOwnerInfoTooLong: Bool {
    ownerInfoCharCount > Self.ownerInfoMaxLength
  }

  // MARK: - Security

  var newPassword: String = ""
  var confirmPassword: String = ""

  // MARK: - Expansion State

  var isDeviceInfoExpanded = false
  var isRadioExpanded = false
  var isIdentityExpanded = false
  var isContactInfoExpanded = false
  var isSecurityExpanded = false

  // MARK: - Global State

  var isApplying = false
  var isRebooting = false
  var errorMessage: String?
  var successMessage: String?
  var showSuccessAlert = false
  var identityApplySuccess = false
  var contactInfoApplySuccess = false
  var changePasswordSuccess = false
  var isSendingAdvert = false

  /// When true, revert uncommitted edits once every settings Apply flag is idle.
  private(set) var discardDraftWhenIdle = false
  /// When true, drop cached settings once every settings Apply flag is idle.
  private var clearCachedSettingsWhenIdle = false
  /// Room sets this to room-access / behavior Apply; repeater leaves the default.
  var otherSettingsApplyInFlight: () -> Bool = { false }
  /// Full revert including repeater/room fields. Configure wires this.
  var onRevertUncommittedSettingsEdits: () -> Void = {}
  /// Repeater and room sections collapse with the shared ones.
  var onCollapseExtraSettingsSections: () -> Void = {}
  /// Repeater and room cached values clear with the shared ones.
  var onClearCachedExtraSettings: () -> Void = {}

  // MARK: - Service Closures

  private var sendCommandClosure: ((UUID, String, Duration) async throws -> String)?
  private var sendRawCommandClosure: ((UUID, String, Duration) async throws -> String)?

  /// Called when firmware version or node info needs pre-fetching.
  /// Repeater sets this to binary requestOwnerInfo; Room sets this to CLI `ver`.
  var onPreFetchNodeInfo: (() async -> Void)?

  // MARK: - Load / Apply field ownership

  /// Fields an Apply may own so an older load cannot overwrite them.
  enum OwnedSettingsField: Hashable {
    case name, latitude, longitude, ownerInfo, radio
    case advertInterval, floodAdvertInterval, floodMaxHops, repeaterEnabled
    case guestPassword, allowReadOnly
  }

  /// Captured when a settings load starts. A later visit does not match.
  struct SettingsVisitToken: Equatable {
    fileprivate let generation: UInt
    fileprivate let acceptsLoads: Bool
  }

  /// Snapshot of field revisions captured when a load starts.
  struct SettingsLoadTicket {
    fileprivate let revisions: [OwnedSettingsField: Int]
    fileprivate let visit: SettingsVisitToken
  }

  /// Loads taken while the sheet is open. Ending the visit bumps the generation
  /// so a reply from the previous visit cannot refill the next one.
  private var settingsVisitGeneration: UInt = 0
  private var settingsVisitAcceptsLoads = true

  private var fieldRevision: [OwnedSettingsField: Int] = [:]
  private var applyOwnedFields: Set<OwnedSettingsField> = []
  private var loadTicketByQuery: [String: SettingsLoadTicket] = [:]

  func captureSettingsVisit() -> SettingsVisitToken {
    SettingsVisitToken(generation: settingsVisitGeneration, acceptsLoads: settingsVisitAcceptsLoads)
  }

  func isSettingsVisitCurrent(_ visit: SettingsVisitToken) -> Bool {
    visit.acceptsLoads && visit.generation == settingsVisitGeneration
  }

  func isSettingsVisitCurrent(query: String) -> Bool {
    guard let ticket = loadTicketByQuery[query] else { return false }
    return isSettingsVisitCurrent(ticket.visit)
  }

  func beginSettingsLoad(fields: [OwnedSettingsField]) -> SettingsLoadTicket {
    SettingsLoadTicket(
      revisions: Dictionary(uniqueKeysWithValues: fields.map { ($0, fieldRevision[$0, default: 0]) }),
      visit: captureSettingsVisit()
    )
  }

  /// Skip section flags when the load's visit has ended, so a late finish
  /// cannot clear a newer visit's spinner or restore its error.
  func finishSettingsLoad(
    _ visit: SettingsVisitToken,
    timedOut: Bool,
    setError: (Bool) -> Void,
    setLoading: (Bool) -> Void
  ) {
    guard isSettingsVisitCurrent(visit) else { return }
    if timedOut { setError(true) }
    setLoading(false)
  }

  func beginSettingsLoad(query: String, fields: [OwnedSettingsField]) -> SettingsLoadTicket {
    let ticket = beginSettingsLoad(fields: fields)
    loadTicketByQuery[query] = ticket
    return ticket
  }

  func isSettingsLoadCurrent(_ ticket: SettingsLoadTicket, field: OwnedSettingsField) -> Bool {
    guard isSettingsVisitCurrent(ticket.visit) else { return false }
    return ticket.revisions[field] == fieldRevision[field, default: 0]
  }

  func isSettingsLoadCurrent(query: String, field: OwnedSettingsField) -> Bool {
    guard let ticket = loadTicketByQuery[query] else { return false }
    return isSettingsLoadCurrent(ticket, field: field)
  }

  func takeApplyOwnership(of fields: [OwnedSettingsField]) {
    for field in fields {
      fieldRevision[field, default: 0] += 1
      applyOwnedFields.insert(field)
    }
  }

  func releaseApplyOwnership(of fields: [OwnedSettingsField]) {
    applyOwnedFields.subtract(fields)
  }

  /// Unticketed writers (placeholder seed, direct `setNodeInfo`) skip fields Apply owns.
  /// Ticketed writers use the revision captured when that load started.
  func allowsSettingsLoadWrite(_ field: OwnedSettingsField, ticket: SettingsLoadTicket?) -> Bool {
    if let ticket {
      return isSettingsLoadCurrent(ticket, field: field)
    }
    guard settingsVisitAcceptsLoads else { return false }
    return !applyOwnedFields.contains(field)
  }

  // MARK: - Discard on exit

  var hasUncommittedSharedSettingsEdits: Bool {
    identitySettingsModified
      || radioSettingsModified
      || contactInfoSettingsModified
      || !newPassword.isEmpty
      || !confirmPassword.isEmpty
  }

  func revertUncommittedSharedSettingsEdits() {
    name = nameBaseline
    latitude = originalLatitude
    longitude = originalLongitude
    if let originalFrequency {
      frequency = originalFrequency
    }
    if let originalBandwidth {
      bandwidth = originalBandwidth
    }
    if let originalSpreadingFactor {
      spreadingFactor = originalSpreadingFactor
    }
    if let originalCodingRate {
      codingRate = originalCodingRate
    }
    if contactInfoLoaded {
      ownerInfo = originalOwnerInfo
    }
    newPassword = ""
    confirmPassword = ""
    nameError = nil
    latitudeError = nil
    longitudeError = nil
  }

  func noteSettingsDisappeared() {
    if settingsVisitAcceptsLoads {
      settingsVisitAcceptsLoads = false
      settingsVisitGeneration &+= 1
    }
    collapseSettingsSections()
    if isApplying || otherSettingsApplyInFlight() {
      discardDraftWhenIdle = true
      clearCachedSettingsWhenIdle = true
    } else {
      onRevertUncommittedSettingsEdits()
      clearCachedSettingsNow()
    }
  }

  func noteSettingsAppeared() {
    discardDraftWhenIdle = false
    clearCachedSettingsWhenIdle = false
    guard !settingsVisitAcceptsLoads else { return }
    settingsVisitGeneration &+= 1
    settingsVisitAcceptsLoads = true
  }

  func revertAbandonedDraftIfIdle() {
    guard discardDraftWhenIdle || clearCachedSettingsWhenIdle else { return }
    guard !isApplying, !otherSettingsApplyInFlight() else { return }
    let shouldRevert = discardDraftWhenIdle
    let shouldClear = clearCachedSettingsWhenIdle
    discardDraftWhenIdle = false
    clearCachedSettingsWhenIdle = false
    if shouldRevert {
      onRevertUncommittedSettingsEdits()
    }
    if shouldClear {
      clearCachedSettingsNow()
    }
  }

  private func collapseSettingsSections() {
    isDeviceInfoExpanded = false
    isRadioExpanded = false
    isIdentityExpanded = false
    isContactInfoExpanded = false
    isSecurityExpanded = false
    onCollapseExtraSettingsSections()
  }

  private func clearCachedSettingsNow() {
    clearCachedSharedSettings()
    onClearCachedExtraSettings()
  }

  private func clearCachedSharedSettings() {
    firmwareVersion = nil
    deviceTimeUTC = nil
    clockDrift = nil
    isLoadingDeviceInfo = false
    deviceInfoError = false

    name = nil
    nameBaseline = nil
    originalName = nil
    latitude = nil
    longitude = nil
    originalLatitude = nil
    originalLongitude = nil
    isLoadingIdentity = false
    identityError = false
    nameError = nil
    latitudeError = nil
    longitudeError = nil

    frequency = nil
    bandwidth = nil
    spreadingFactor = nil
    codingRate = nil
    originalFrequency = nil
    originalBandwidth = nil
    originalSpreadingFactor = nil
    originalCodingRate = nil
    isLoadingRadio = false
    radioError = false

    ownerInfo = nil
    originalOwnerInfo = nil
    isLoadingContactInfo = false
    contactInfoError = false
  }

  // MARK: - Configuration

  func configure(
    session: RemoteNodeSessionDTO,
    sendCommand: @escaping (UUID, String, Duration) async throws -> String,
    sendRawCommand: @escaping (UUID, String, Duration) async throws -> String
  ) {
    self.session = session
    sendCommandClosure = sendCommand
    sendRawCommandClosure = sendRawCommand
    registerSharedLateRecovery()
  }

  /// Set name and owner info from an external source (e.g., binary protocol pre-fetch)
  func setNodeInfo(
    firmwareVersion: String?,
    name: String?,
    ownerInfo: String?,
    loadTicket: SettingsLoadTicket? = nil
  ) {
    if let loadTicket {
      guard isSettingsVisitCurrent(loadTicket.visit) else { return }
    } else if !settingsVisitAcceptsLoads {
      return
    }
    if let firmwareVersion { self.firmwareVersion = firmwareVersion }
    if let name, allowsSettingsLoadWrite(.name, ticket: loadTicket) {
      self.name = name
      nameBaseline = name
      originalName = name
    }
    if let ownerInfo, allowsSettingsLoadWrite(.ownerInfo, ticket: loadTicket) {
      self.ownerInfo = ownerInfo
      originalOwnerInfo = ownerInfo
    }
  }

  /// Contact display name as a first-open placeholder. Does not set `originalName`.
  func adoptPlaceholderName(_ sessionName: String) {
    guard allowsSettingsLoadWrite(.name, ticket: nil) else { return }
    name = sessionName
    nameBaseline = sessionName
  }

  func cleanup() {
    sendCommandClosure = nil
    sendRawCommandClosure = nil
    onPreFetchNodeInfo = nil
    onRevertUncommittedSettingsEdits = {}
    onCollapseExtraSettingsSections = {}
    onClearCachedExtraSettings = {}
    otherSettingsApplyInFlight = { false }
    settingsVisitGeneration &+= 1
    settingsVisitAcceptsLoads = true
    clearCachedSettingsWhenIdle = false
    unansweredQueries.removeAll()
    recentResponses.removeAll()
    lateRecoveryAppliers.removeAll()
    loadTicketByQuery.removeAll()
    fieldRevision.removeAll()
    applyOwnedFields.removeAll()
    discardDraftWhenIdle = false
  }

  // MARK: - CLI Transport

  func sendAndWait(
    _ command: String,
    timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
    rawMatching: Bool = false
  ) async throws -> String {
    guard let session, let sendCmd = rawMatching ? sendRawCommandClosure : sendCommandClosure else {
      throw NodeSettingsError.noService
    }

    do {
      let response = try await sendCmd(session.id, command, timeout)
      logger.debug("Command '\(command)' response: \(response.prefix(50))")
      rememberSeenResponse(response)
      unansweredQueries.remove(command)
      return response
    } catch RemoteNodeError.timeout {
      if CLIResponse.isStructuredQuery(command) {
        unansweredQueries.insert(command)
      }
      throw RemoteNodeError.timeout
    }
  }

  // MARK: - Fetch Methods

  func fetchDeviceInfo() async {
    let visit = captureSettingsVisit()
    guard isSettingsVisitCurrent(visit) else { return }
    isLoadingDeviceInfo = true
    deviceInfoError = false
    var hadTimeout = false

    if firmwareVersion == nil {
      await onPreFetchNodeInfo?()
    }

    if firmwareVersion == nil {
      do {
        let response = try await sendAndWait("ver")
        if isSettingsVisitCurrent(visit),
           case let .version(version) = CLIResponse.parse(response, forQuery: "ver") {
          firmwareVersion = version
        }
      } catch {
        if case RemoteNodeError.timeout = error { hadTimeout = true }
        logger.warning("Failed to get firmware version: \(error)")
      }
    }

    let clockTicket = beginSettingsLoad(query: "clock", fields: [])
    do {
      let response = try await sendAndWait("clock")
      if isSettingsVisitCurrent(clockTicket.visit),
         case let .deviceTime(time) = CLIResponse.parse(response, forQuery: "clock") {
        applyDeviceTime(time)
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get device time: \(error)")
    }

    finishSettingsLoad(
      visit,
      timedOut: hadTimeout,
      setError: { deviceInfoError = $0 },
      setLoading: { isLoadingDeviceInfo = $0 }
    )
  }

  func fetchIdentity() async {
    let visit = captureSettingsVisit()
    guard isSettingsVisitCurrent(visit) else { return }
    isLoadingIdentity = true
    identityError = false
    var hadTimeout = false

    if originalName == nil {
      await onPreFetchNodeInfo?()
    }

    if originalName == nil {
      let nameTicket = beginSettingsLoad(query: "get name", fields: [.name])
      do {
        let response = try await sendAndWait("get name")
        if isSettingsLoadCurrent(nameTicket, field: .name),
           case let .name(n) = CLIResponse.parse(response, forQuery: "get name") {
          name = n
          nameBaseline = n
          originalName = n
        }
      } catch {
        if case RemoteNodeError.timeout = error { hadTimeout = true }
        logger.warning("Failed to get name: \(error)")
      }
    }

    let latTicket = beginSettingsLoad(query: "get lat", fields: [.latitude])
    do {
      let response = try await sendAndWait("get lat")
      if isSettingsLoadCurrent(latTicket, field: .latitude),
         case let .latitude(lat) = CLIResponse.parse(response, forQuery: "get lat") {
        latitude = lat
        originalLatitude = lat
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get latitude: \(error)")
    }

    let lonTicket = beginSettingsLoad(query: "get lon", fields: [.longitude])
    do {
      let response = try await sendAndWait("get lon")
      if isSettingsLoadCurrent(lonTicket, field: .longitude),
         case let .longitude(lon) = CLIResponse.parse(response, forQuery: "get lon") {
        longitude = lon
        originalLongitude = lon
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get longitude: \(error)")
    }

    finishSettingsLoad(
      visit,
      timedOut: hadTimeout,
      setError: { identityError = $0 },
      setLoading: { isLoadingIdentity = $0 }
    )
  }

  func fetchRadioSettings() async {
    let visit = captureSettingsVisit()
    guard isSettingsVisitCurrent(visit) else { return }
    isLoadingRadio = true
    radioError = false
    var hadTimeout = false

    let radioTicket = beginSettingsLoad(query: "get radio", fields: [.radio])
    do {
      let response = try await sendAndWait("get radio")
      if isSettingsLoadCurrent(radioTicket, field: .radio),
         case let .radio(freq, bw, sf, cr) = CLIResponse.parse(response, forQuery: "get radio") {
        adoptRadioValues(frequency: freq, bandwidth: bw, spreadingFactor: sf, codingRate: cr)
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get radio settings: \(error)")
    }

    finishSettingsLoad(
      visit,
      timedOut: hadTimeout,
      setError: { radioError = $0 },
      setLoading: { isLoadingRadio = $0 }
    )
  }

  func adoptRadioValues(frequency: Double, bandwidth: Double, spreadingFactor: Int, codingRate: Int) {
    self.frequency = frequency
    self.bandwidth = bandwidth
    self.spreadingFactor = spreadingFactor
    self.codingRate = codingRate
    originalFrequency = frequency
    originalBandwidth = bandwidth
    originalSpreadingFactor = spreadingFactor
    originalCodingRate = codingRate
  }

  func fetchContactInfo() async {
    let visit = captureSettingsVisit()
    guard isSettingsVisitCurrent(visit) else { return }
    if originalOwnerInfo == nil {
      await onPreFetchNodeInfo?()
    }
    if originalOwnerInfo != nil { return }
    guard isSettingsVisitCurrent(visit) else { return }

    isLoadingContactInfo = true
    contactInfoError = false

    let ownerTicket = beginSettingsLoad(query: "get owner.info", fields: [.ownerInfo])
    do {
      let response = try await sendAndWait("get owner.info")
      if isSettingsLoadCurrent(ownerTicket, field: .ownerInfo),
         case let .ownerInfo(info) = CLIResponse.parse(response, forQuery: "get owner.info") {
        let displayText = NodeSettingsResponseParser.displayOwnerInfo(fromWire: info)
        ownerInfo = displayText
        originalOwnerInfo = displayText
      }
    } catch {
      if case RemoteNodeError.timeout = error, isSettingsVisitCurrent(visit) {
        contactInfoError = true
      }
      logger.warning("Failed to get owner info: \(error)")
    }

    finishSettingsLoad(
      visit,
      timedOut: false,
      setError: { _ in },
      setLoading: { isLoadingContactInfo = $0 }
    )
  }

  // MARK: - Success Flash

  /// How long an Apply button shows its success state before returning to idle.
  static let successFlashDuration: Duration = .seconds(1.5)

  /// Drop the section's applying flag and flash its success indicator for
  /// `successFlashDuration`. The closures target the section's own state, which
  /// may live on this shared view model or on the owning view model.
  func flashSuccess(setApplying: (Bool) -> Void, setSuccess: (Bool) -> Void) async {
    withAnimation {
      setApplying(false)
      setSuccess(true)
    }
    try? await Task.sleep(for: Self.successFlashDuration)
    withAnimation { setSuccess(false) }
  }

  // MARK: - Apply Methods

  func applyRadioSettings() async {
    guard let frequency, let bandwidth, let spreadingFactor, let codingRate else {
      errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioNotLoaded
      return
    }
    guard radioSettingsModified else { return }

    let snapshotFrequency = frequency
    let snapshotBandwidth = bandwidth
    let snapshotSpreadingFactor = spreadingFactor
    let snapshotCodingRate = codingRate
    let owned: [OwnedSettingsField] = [.radio]
    takeApplyOwnership(of: owned)
    isApplying = true
    errorMessage = nil
    defer {
      isApplying = false
      releaseApplyOwnership(of: owned)
      revertAbandonedDraftIfIdle()
    }

    do {
      let radioCommand =
        "set radio \(snapshotFrequency),\(snapshotBandwidth),\(snapshotSpreadingFactor),\(snapshotCodingRate)"
      let radioResponse = try await sendAndWait(radioCommand)
      if case .ok = CLIResponse.parse(radioResponse) {
        originalFrequency = snapshotFrequency
        originalBandwidth = snapshotBandwidth
        originalSpreadingFactor = snapshotSpreadingFactor
        originalCodingRate = snapshotCodingRate
        successMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioAppliedSuccess
        showSuccessAlert = true
      } else {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioApplyFailed
      }
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  func applyIdentitySettings() async {
    let validation = Self.validateIdentityFields(name: name, latitude: latitude, longitude: longitude)
    nameError = validation.name
    latitudeError = validation.latitude
    longitudeError = validation.longitude
    if validation.hasErrors { return }

    let snapshotName = name
    let snapshotLatitude = latitude
    let snapshotLongitude = longitude
    let owned: [OwnedSettingsField] = [.name, .latitude, .longitude]
    takeApplyOwnership(of: owned)
    isApplying = true
    errorMessage = nil
    defer {
      isApplying = false
      releaseApplyOwnership(of: owned)
      revertAbandonedDraftIfIdle()
    }

    do {
      var allSucceeded = true

      if let snapshotName, snapshotName != nameBaseline {
        let response = try await sendAndWait("set name \(snapshotName)")
        if case .ok = CLIResponse.parse(response) {
          originalName = snapshotName
          nameBaseline = snapshotName
        } else {
          allSucceeded = false
        }
      }

      if let snapshotLatitude, snapshotLatitude != originalLatitude {
        let response = try await sendAndWait("set lat \(snapshotLatitude)")
        if case .ok = CLIResponse.parse(response) {
          originalLatitude = snapshotLatitude
        } else {
          allSucceeded = false
        }
      }

      if let snapshotLongitude, snapshotLongitude != originalLongitude {
        let response = try await sendAndWait("set lon \(snapshotLongitude)")
        if case .ok = CLIResponse.parse(response) {
          originalLongitude = snapshotLongitude
        } else {
          allSucceeded = false
        }
      }

      if allSucceeded {
        await flashSuccess(
          setApplying: { isApplying = $0 },
          setSuccess: { identityApplySuccess = $0 }
        )
      } else {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  func applyContactInfoSettings() async {
    let snapshotOwnerInfo = ownerInfo
    let owned: [OwnedSettingsField] = [.ownerInfo]
    takeApplyOwnership(of: owned)
    isApplying = true
    errorMessage = nil
    defer {
      isApplying = false
      releaseApplyOwnership(of: owned)
      revertAbandonedDraftIfIdle()
    }

    do {
      let pipeText = NodeSettingsResponseParser.wireOwnerInfo(fromDisplay: snapshotOwnerInfo ?? "")
      let response = try await sendAndWait("set owner.info \(pipeText)")
      if case .ok = CLIResponse.parse(response) {
        originalOwnerInfo = snapshotOwnerInfo
        await flashSuccess(
          setApplying: { isApplying = $0 },
          setSuccess: { contactInfoApplySuccess = $0 }
        )
      } else {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  // MARK: - Location Picker

  func setLocationFromPicker(latitude: Double, longitude: Double) {
    self.latitude = latitude
    self.longitude = longitude
  }

  // MARK: - Security

  func changePassword() async {
    guard !newPassword.isEmpty else {
      errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.passwordEmpty
      return
    }
    guard newPassword == confirmPassword else {
      errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.passwordMismatch
      return
    }

    isApplying = true
    errorMessage = nil
    defer {
      isApplying = false
      revertAbandonedDraftIfIdle()
    }

    do {
      let response = try await sendAndWait("password \(newPassword)", rawMatching: true)
      if NodeSettingsResponseParser.isPasswordChangeSuccessful(response) {
        newPassword = ""
        confirmPassword = ""
        await flashSuccess(
          setApplying: { isApplying = $0 },
          setSuccess: { changePasswordSuccess = $0 }
        )
      } else {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.passwordChangeFailed
      }
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  // MARK: - Device Actions

  func reboot() async {
    guard session != nil else { return }

    isRebooting = true
    errorMessage = nil

    // Firmware reboots without replying, so a timeout is the expected outcome.
    do {
      _ = try await sendAndWait("reboot", timeout: RemoteOperationTimeoutPolicy.fireAndForgetCLI)
      successMessage = L10n.RemoteNodes.RemoteNodes.Settings.rebootSent
      showSuccessAlert = true
    } catch RemoteNodeError.timeout {
      successMessage = L10n.RemoteNodes.RemoteNodes.Settings.rebootSent
      showSuccessAlert = true
    } catch {
      errorMessage = error.userFacingMessage
    }

    isRebooting = false
  }

  func forceAdvert() async {
    isSendingAdvert = true
    defer { isSendingAdvert = false }
    do {
      _ = try await sendAndWait("advert")
      successMessage = L10n.RemoteNodes.RemoteNodes.Settings.advertSent
      showSuccessAlert = true
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  func syncTime() async {
    isApplying = true
    errorMessage = nil
    defer {
      isApplying = false
      revertAbandonedDraftIfIdle()
    }

    do {
      let response = try await sendAndWait(
        RemoteCLICommandRewriter.rewrite(RemoteCLICommandRewriter.clockSyncCommand)
      )
      switch NodeSettingsResponseParser.classifyClockSyncResponse(response) {
      case .synced:
        if NodeSettingsResponseParser.clockResponseText(in: response) != nil {
          applyDeviceTime(response)
        } else {
          clockDrift = nil
        }
        successMessage = L10n.RemoteNodes.RemoteNodes.Settings.timeSynced
        showSuccessAlert = true
      case .clockAhead:
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.clockAheadError
      case let .failed(message):
        errorMessage = message.isEmpty ? L10n.RemoteNodes.RemoteNodes.Settings.syncTimeFailed : message
      case .unexpected:
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.unexpectedResponse(response)
      }
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  // MARK: - Shared Validation

  /// Firmware-accepted ranges for the behavior fields; 0 means disabled for
  /// the two intervals and is validated separately.
  static let advertIntervalMinutesRange = 60...240
  static let floodIntervalHoursRange = 3...168
  static let floodMaxHopsRange = 0...64

  struct BehaviorValidationErrors {
    var advertInterval: String?
    var floodInterval: String?
    var floodMaxHops: String?
    var hasErrors: Bool {
      advertInterval != nil || floodInterval != nil || floodMaxHops != nil
    }
  }

  static func validateBehaviorFields(
    advertInterval: Int?,
    floodInterval: Int?,
    floodMaxHops: Int?
  ) -> BehaviorValidationErrors {
    var errors = BehaviorValidationErrors()
    if let interval = advertInterval, interval != 0, !advertIntervalMinutesRange.contains(interval) {
      errors.advertInterval = L10n.RemoteNodes.RemoteNodes.Settings.advertIntervalValidation
    }
    if let interval = floodInterval, interval != 0, !floodIntervalHoursRange.contains(interval) {
      errors.floodInterval = L10n.RemoteNodes.RemoteNodes.Settings.floodIntervalValidation
    }
    if let hops = floodMaxHops, !floodMaxHopsRange.contains(hops) {
      errors.floodMaxHops = L10n.RemoteNodes.RemoteNodes.Settings.floodMaxValidation
    }
    return errors
  }

  struct IdentityValidationErrors {
    var name: String?
    var latitude: String?
    var longitude: String?
    var hasErrors: Bool {
      name != nil || latitude != nil || longitude != nil
    }
  }

  /// Rejects out-of-range coordinates rather than clamping, so a mistyped value surfaces to the
  /// user instead of firmware silently normalizing it. Ranges and the name byte cap come from
  /// `PacketBuilder` and `ProtocolLimits`, matching the binary write path.
  static func validateIdentityFields(
    name: String?,
    latitude: Double?,
    longitude: Double?
  ) -> IdentityValidationErrors {
    var errors = IdentityValidationErrors()
    if let name, name.utf8.count > ProtocolLimits.maxUsableNameBytes {
      errors.name = L10n.RemoteNodes.RemoteNodes.Settings.nameValidation(ProtocolLimits.maxUsableNameBytes)
    }
    if let latitude, !latitude.isFinite || !PacketBuilder.latitudeRange.contains(latitude) {
      errors.latitude = L10n.RemoteNodes.RemoteNodes.Settings.latitudeValidation
    }
    if let longitude, !longitude.isFinite || !PacketBuilder.longitudeRange.contains(longitude) {
      errors.longitude = L10n.RemoteNodes.RemoteNodes.Settings.longitudeValidation
    }
    return errors
  }

  // MARK: - Late Reply Recovery

  /// Structured queries this screen sent that timed out unanswered.
  private var unansweredQueries: Set<String> = []

  /// Recently observed reply texts; a mesh duplicate of an already-answered
  /// command must not be recovered for a different query.
  private var recentResponses: [String] = []
  private static let recentResponsesLimit = 16

  /// Appliers for recovered late replies, keyed by query.
  private var lateRecoveryAppliers: [String: (CLIResponse) -> Void] = [:]

  /// Registers a field applier for a recoverable query. Repeater and room
  /// view models add their own section fields on top of the shared ones.
  func registerLateRecovery(query: String, apply: @escaping (CLIResponse) -> Void) {
    lateRecoveryAppliers[query] = apply
  }

  /// Test seam: mark a structured query as timed-out so late recovery can run.
  func markUnansweredQueryForTesting(_ query: String) {
    unansweredQueries.insert(query)
  }

  private func rememberSeenResponse(_ response: String) {
    recentResponses.append(response)
    if recentResponses.count > Self.recentResponsesLimit {
      recentResponses.removeFirst()
    }
  }

  /// Adopt an out-of-band CLI reply for the one unanswered query it can only
  /// belong to. The reply already spent its airtime, so recovering it beats
  /// refetching; ambiguous or duplicate replies are ignored.
  func handleCommonLateResponse(_ response: String) {
    guard !recentResponses.contains(response) else { return }
    guard let recovered = NodeSettingsResponseParser.recoveredResponse(
      response,
      unansweredQueries: unansweredQueries
    ), let apply = lateRecoveryAppliers[recovered.query] else { return }

    unansweredQueries.remove(recovered.query)
    rememberSeenResponse(response)
    apply(recovered.value)
    logger.info("Recovered late response for '\(recovered.query)'")
  }

  private var identitySectionComplete: Bool {
    originalName != nil && originalLatitude != nil && originalLongitude != nil
  }

  private func registerSharedLateRecovery() {
    registerLateRecovery(query: "get radio") { [weak self] value in
      guard let self,
            isSettingsLoadCurrent(query: "get radio", field: .radio),
            case let .radio(frequency, bandwidth, spreadingFactor, codingRate) = value else { return }
      adoptRadioValues(
        frequency: frequency,
        bandwidth: bandwidth,
        spreadingFactor: spreadingFactor,
        codingRate: codingRate
      )
      radioError = false
    }
    registerLateRecovery(query: "get lat") { [weak self] value in
      guard let self,
            isSettingsLoadCurrent(query: "get lat", field: .latitude),
            case let .latitude(latitude) = value else { return }
      self.latitude = latitude
      originalLatitude = latitude
      identityError = !identitySectionComplete
    }
    registerLateRecovery(query: "get lon") { [weak self] value in
      guard let self,
            isSettingsLoadCurrent(query: "get lon", field: .longitude),
            case let .longitude(longitude) = value else { return }
      self.longitude = longitude
      originalLongitude = longitude
      identityError = !identitySectionComplete
    }
    registerLateRecovery(query: "clock") { [weak self] value in
      guard let self,
            isSettingsVisitCurrent(query: "clock"),
            case let .deviceTime(time) = value else { return }
      applyDeviceTime(time)
      deviceInfoError = firmwareVersion == nil
    }
  }
}
