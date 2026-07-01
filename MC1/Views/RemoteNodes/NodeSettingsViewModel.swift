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

  // MARK: - Identity

  var name: String?
  var latitude: Double?
  var longitude: Double?
  private(set) var originalName: String?
  private(set) var originalLatitude: Double?
  private(set) var originalLongitude: Double?
  var isLoadingIdentity = false
  var identityError = false
  var identityLoaded: Bool {
    originalLatitude != nil || originalLongitude != nil
  }

  var identitySettingsModified: Bool {
    (name != nil && name != originalName) ||
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
  var txPower: Int?
  var isLoadingRadio = false
  var radioError = false
  var radioLoaded: Bool {
    frequency != nil || txPower != nil
  }

  var radioSettingsModified = false

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

  // MARK: - Service Closures

  private var sendCommandClosure: ((UUID, String, Duration) async throws -> String)?
  private var sendRawCommandClosure: ((UUID, String, Duration) async throws -> String)?

  /// Called when firmware version or node info needs pre-fetching.
  /// Repeater sets this to binary requestOwnerInfo; Room sets this to CLI `ver`.
  var onPreFetchNodeInfo: (() async -> Void)?

  // MARK: - Configuration

  func configure(
    session: RemoteNodeSessionDTO,
    sendCommand: @escaping (UUID, String, Duration) async throws -> String,
    sendRawCommand: @escaping (UUID, String, Duration) async throws -> String
  ) {
    self.session = session
    sendCommandClosure = sendCommand
    sendRawCommandClosure = sendRawCommand
  }

  /// Set name and owner info from an external source (e.g., binary protocol pre-fetch)
  func setNodeInfo(firmwareVersion: String?, name: String?, ownerInfo: String?) {
    if let firmwareVersion { self.firmwareVersion = firmwareVersion }
    if let name {
      self.name = name
      originalName = name
    }
    if let ownerInfo {
      self.ownerInfo = ownerInfo
      originalOwnerInfo = ownerInfo
    }
  }

  func cleanup() {
    sendCommandClosure = nil
    sendRawCommandClosure = nil
    onPreFetchNodeInfo = nil
  }

  // MARK: - CLI Transport

  func sendAndWait(
    _ command: String,
    timeout: Duration = .seconds(5),
    rawMatching: Bool = false
  ) async throws -> String {
    guard let session, let sendCmd = rawMatching ? sendRawCommandClosure : sendCommandClosure else {
      throw NodeSettingsError.noService
    }

    let response = try await sendCmd(session.id, command, timeout)
    logger.debug("Command '\(command)' response: \(response.prefix(50))")
    return response
  }

  // MARK: - Fetch Methods

  func fetchDeviceInfo() async {
    isLoadingDeviceInfo = true
    deviceInfoError = false

    if firmwareVersion == nil {
      await onPreFetchNodeInfo?()
    }

    if firmwareVersion == nil {
      do {
        let response = try await sendAndWait("ver")
        if case let .version(version) = CLIResponse.parse(response, forQuery: "ver") {
          firmwareVersion = version
        }
      } catch {
        if case RemoteNodeError.timeout = error {
          deviceInfoError = true
        }
        logger.warning("Failed to get firmware version: \(error)")
      }
    }

    do {
      let response = try await sendAndWait("clock")
      if case let .deviceTime(time) = CLIResponse.parse(response, forQuery: "clock") {
        deviceTimeUTC = time
      }
    } catch {
      if case RemoteNodeError.timeout = error {
        deviceInfoError = true
      }
      logger.warning("Failed to get device time: \(error)")
    }

    isLoadingDeviceInfo = false
  }

  func fetchIdentity() async {
    isLoadingIdentity = true
    identityError = false
    var hadTimeout = false

    if originalName == nil {
      await onPreFetchNodeInfo?()
    }

    if originalName == nil {
      do {
        let response = try await sendAndWait("get name")
        if case let .name(n) = CLIResponse.parse(response, forQuery: "get name") {
          name = n
          originalName = n
        }
      } catch {
        if case RemoteNodeError.timeout = error { hadTimeout = true }
        logger.warning("Failed to get name: \(error)")
      }
    }

    do {
      let response = try await sendAndWait("get lat")
      if case let .latitude(lat) = CLIResponse.parse(response, forQuery: "get lat") {
        latitude = lat
        originalLatitude = lat
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get latitude: \(error)")
    }

    do {
      let response = try await sendAndWait("get lon")
      if case let .longitude(lon) = CLIResponse.parse(response, forQuery: "get lon") {
        longitude = lon
        originalLongitude = lon
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get longitude: \(error)")
    }

    if hadTimeout {
      identityError = true
    }

    isLoadingIdentity = false
  }

  func fetchRadioSettings() async {
    isLoadingRadio = true
    radioError = false
    var hadTimeout = false

    do {
      let response = try await sendAndWait("get tx")
      if case let .txPower(power) = CLIResponse.parse(response, forQuery: "get tx") {
        txPower = power
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get TX power: \(error)")
    }

    do {
      let response = try await sendAndWait("get radio")
      if case let .radio(freq, bw, sf, cr) = CLIResponse.parse(response, forQuery: "get radio") {
        frequency = freq
        bandwidth = bw
        spreadingFactor = sf
        codingRate = cr
      }
    } catch {
      if case RemoteNodeError.timeout = error { hadTimeout = true }
      logger.warning("Failed to get radio settings: \(error)")
    }

    if hadTimeout {
      radioError = true
    }

    isLoadingRadio = false
  }

  func fetchContactInfo() async {
    if originalOwnerInfo == nil {
      await onPreFetchNodeInfo?()
    }
    if originalOwnerInfo != nil { return }

    isLoadingContactInfo = true
    contactInfoError = false

    do {
      let response = try await sendAndWait("get owner.info")
      if case let .ownerInfo(info) = CLIResponse.parse(response, forQuery: "get owner.info") {
        let displayText = NodeSettingsResponseParser.displayOwnerInfo(fromWire: info)
        ownerInfo = displayText
        originalOwnerInfo = displayText
      }
    } catch {
      if case RemoteNodeError.timeout = error {
        contactInfoError = true
      }
      logger.warning("Failed to get owner info: \(error)")
    }

    isLoadingContactInfo = false
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
    guard let frequency, let bandwidth, let spreadingFactor, let codingRate, let txPower else {
      errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioNotLoaded
      return
    }

    isApplying = true
    errorMessage = nil

    do {
      var allSucceeded = true

      let radioCommand = "set radio \(frequency),\(bandwidth),\(spreadingFactor),\(codingRate)"
      let radioResponse = try await sendAndWait(radioCommand)
      if case .ok = CLIResponse.parse(radioResponse) {
      } else {
        allSucceeded = false
      }

      let txCommand = "set tx \(txPower)"
      let txResponse = try await sendAndWait(txCommand)
      if case .ok = CLIResponse.parse(txResponse) {
      } else {
        allSucceeded = false
      }

      if allSucceeded {
        radioSettingsModified = false
        successMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioAppliedSuccess
        showSuccessAlert = true
      } else {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioApplyFailed
      }
    } catch {
      errorMessage = error.userFacingMessage
    }

    isApplying = false
  }

  func applyIdentitySettings() async {
    let validation = Self.validateIdentityFields(name: name, latitude: latitude, longitude: longitude)
    nameError = validation.name
    latitudeError = validation.latitude
    longitudeError = validation.longitude
    if validation.hasErrors { return }

    isApplying = true
    errorMessage = nil

    do {
      var allSucceeded = true

      if let name, name != originalName {
        let response = try await sendAndWait("set name \(name)")
        if case .ok = CLIResponse.parse(response) {
          originalName = name
        } else {
          allSucceeded = false
        }
      }

      if let latitude, latitude != originalLatitude {
        let response = try await sendAndWait("set lat \(latitude)")
        if case .ok = CLIResponse.parse(response) {
          originalLatitude = latitude
        } else {
          allSucceeded = false
        }
      }

      if let longitude, longitude != originalLongitude {
        let response = try await sendAndWait("set lon \(longitude)")
        if case .ok = CLIResponse.parse(response) {
          originalLongitude = longitude
        } else {
          allSucceeded = false
        }
      }

      if allSucceeded {
        await flashSuccess(
          setApplying: { isApplying = $0 },
          setSuccess: { identityApplySuccess = $0 }
        )
        return
      } else {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch {
      errorMessage = error.userFacingMessage
    }

    isApplying = false
  }

  func applyContactInfoSettings() async {
    isApplying = true
    errorMessage = nil

    do {
      let pipeText = NodeSettingsResponseParser.wireOwnerInfo(fromDisplay: ownerInfo ?? "")
      let response = try await sendAndWait("set owner.info \(pipeText)")
      if case .ok = CLIResponse.parse(response) {
        originalOwnerInfo = ownerInfo
        await flashSuccess(
          setApplying: { isApplying = $0 },
          setSuccess: { contactInfoApplySuccess = $0 }
        )
        return
      } else {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch {
      errorMessage = error.userFacingMessage
    }

    isApplying = false
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

    do {
      let response = try await sendAndWait("password \(newPassword)", rawMatching: true)
      if NodeSettingsResponseParser.isPasswordChangeSuccessful(response) {
        newPassword = ""
        confirmPassword = ""
        await flashSuccess(
          setApplying: { isApplying = $0 },
          setSuccess: { changePasswordSuccess = $0 }
        )
        return
      } else {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.passwordChangeFailed
      }
    } catch {
      errorMessage = error.userFacingMessage
    }

    isApplying = false
  }

  // MARK: - Device Actions

  func reboot() async {
    guard session != nil else { return }

    isRebooting = true
    errorMessage = nil

    do {
      _ = try await sendAndWait("reboot")
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

    do {
      let response = try await sendAndWait("clock sync")
      switch NodeSettingsResponseParser.classifyClockSyncResponse(response) {
      case .synced:
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

    isApplying = false
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

  // MARK: - Late Response Handling

  /// The settings fields a late response may still fill, ordered so numeric
  /// fields are tried before the free-form name (which matches any text).
  private var missingLateResponseFields: [NodeSettingsResponseParser.SettingsField] {
    var fields: [NodeSettingsResponseParser.SettingsField] = []
    if !isLoadingRadio, radioError {
      if frequency == nil { fields.append(.radio) }
      if txPower == nil { fields.append(.txPower) }
    }
    if !isLoadingDeviceInfo, deviceInfoError {
      if firmwareVersion == nil { fields.append(.firmwareVersion) }
      if deviceTimeUTC == nil { fields.append(.deviceTime) }
    }
    if !isLoadingIdentity, identityError {
      if originalLatitude == nil { fields.append(.latitude) }
      if originalLongitude == nil { fields.append(.longitude) }
      if originalName == nil { fields.append(.name) }
    }
    if !isLoadingContactInfo, contactInfoError {
      if originalOwnerInfo == nil { fields.append(.ownerInfo) }
    }
    return fields
  }

  /// Handle late CLI responses for shared sections.
  /// Returns `true` if the response was consumed.
  func handleCommonLateResponse(_ response: String) -> Bool {
    let value = NodeSettingsResponseParser.firstSettingsValue(
      in: response,
      checking: missingLateResponseFields
    )
    guard let value else { return false }

    switch value {
    case let .radio(frequency, bandwidth, spreadingFactor, codingRate):
      self.frequency = frequency
      self.bandwidth = bandwidth
      self.spreadingFactor = spreadingFactor
      self.codingRate = codingRate
      radioError = false
      logger.info("Late response: received radio settings")
    case let .txPower(power):
      txPower = power
      radioError = false
      logger.info("Late response: received TX power")
    case let .firmwareVersion(version):
      firmwareVersion = version
      deviceInfoError = false
      logger.info("Late response: received firmware version")
    case let .deviceTime(time):
      deviceTimeUTC = time
      deviceInfoError = false
      logger.info("Late response: received device time")
    case let .latitude(latitude):
      self.latitude = latitude
      originalLatitude = latitude
      identityError = false
      logger.info("Late response: received latitude")
    case let .longitude(longitude):
      self.longitude = longitude
      originalLongitude = longitude
      identityError = false
      logger.info("Late response: received longitude")
    case let .name(name):
      self.name = name
      originalName = name
      identityError = false
      logger.info("Late response: received name")
    case let .ownerInfo(info):
      let displayText = NodeSettingsResponseParser.displayOwnerInfo(fromWire: info)
      ownerInfo = displayText
      originalOwnerInfo = displayText
      contactInfoError = false
      logger.info("Late response: received owner info")
    }
    return true
  }
}
