import Foundation
import MC1Services
import Observation

/// Radio CLI WiFi settings for a paired 1.18.0 radio.
@Observable
@MainActor
final class RadioWiFiViewModel {
  private static let connectedStatus = "connected"
  private static let enabledValue = "1"
  private static let disabledValue = "0"
  private static let minSSIDBytes = 1
  private static let maxSSIDBytes = 32
  private static let maxPasswordBytes = 63

  /// Which surface presents `errorMessage`. One error has one presenter.
  enum RecoverySurface: Equatable {
    case connection
    case wifiPage
    case editor
  }

  private var settingsServiceProvider: @MainActor () -> SettingsService? = { nil }
  private var connectionStateProvider: @MainActor () -> DeviceConnectionState = { .disconnected }
  private var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  private var currentTransportTypeProvider: @MainActor () -> TransportType? = { nil }
  private var rebootAction: @MainActor () async throws -> Void = {}

  /// The settings service whose rows are still due. A read starts only while the
  /// editor is closed and this view model is idle, and only that service may replace the rows.
  private var owedSettingsService: SettingsService?

  /// Radio currently displayed. A different id clears the previous radio before the next probe.
  private var adoptedRadioID: UUID?
  /// Service currently displayed. A replacement must probe again before its fields are writable.
  private var adoptedServiceID: ObjectIdentifier?
  /// Service that parsed `wifi.enabled`. Writes use this, not a default or a previous service.
  private var confirmedRadioID: UUID?
  private var confirmedServiceID: ObjectIdentifier?
  private var enabledConfirmed = false
  private var networkConfirmed = false
  private var credentialsConfirmed = false
  private var ignoreEditorClose = false

  private(set) var wifiSettingsSupport: ConnectionLinkRows.WiFiSettingsSupport = .unknown
  private(set) var isLoading = false
  private(set) var isWriting = false
  var errorMessage: String?
  private(set) var statusConnected = false
  private(set) var ipAddress: String?
  private(set) var networkName: String?
  private(set) var wifiEnabled = false
  private(set) var rebootPending = false
  var editorOpen = false {
    didSet {
      guard ignoreEditorClose == false else { return }
      guard oldValue, editorOpen == false else { return }
      Task { await startOwedRead() }
    }
  }

  var ssidField = ""
  var passwordField = ""
  var showingWiFiWarning = false
  var showingClearConfirm = false
  var showingRebootConfirm = false

  private var loadedSSID = ""
  private var loadedPassword = ""
  private var pendingWiFiAction: WiFiWarningAction?

  /// The SSID, password, and whether the password differs from the loaded one.
  /// Captured when Save is tapped. Later edits do not change the commands.
  private struct WiFiCredentialSubmission: Equatable {
    var ssid: String
    var password: String
    var passwordChanged: Bool
  }

  private struct CommandOwner: Equatable {
    var radioID: UUID?
    var serviceID: ObjectIdentifier
  }

  private enum WiFiWarningAction {
    case save(WiFiCredentialSubmission)
    case disable
  }

  private enum EnabledProbe {
    case enabled(Bool)
    case unavailable
    case invalid
  }

  private var settingsService: SettingsService? {
    settingsServiceProvider()
  }

  private var transportIsWiFi: Bool {
    currentTransportTypeProvider() == .wifi
  }

  var showsSection: Bool {
    wifiSettingsSupport == .supported
  }

  var isBusy: Bool {
    isLoading || isWriting
  }

  var canRunCommands: Bool {
    guard connectionStateProvider() == .ready, isBusy == false else { return false }
    guard enabledConfirmed, let confirmedServiceID, let confirmedRadioID else { return false }
    guard let settings = settingsService, ObjectIdentifier(settings) == confirmedServiceID else { return false }
    return connectedDeviceProvider()?.radioID == confirmedRadioID
  }

  var canEditNetwork: Bool {
    canRunCommands && credentialsConfirmed
  }

  var canSave: Bool {
    guard canEditNetwork else { return false }
    guard ssidField != loadedSSID || passwordField != loadedPassword else { return false }
    let nameBytes = Self.utf8ByteCount(ssidField)
    guard nameBytes >= Self.minSSIDBytes, nameBytes <= Self.maxSSIDBytes else { return false }
    return Self.utf8ByteCount(passwordField) <= Self.maxPasswordBytes
  }

  var canClear: Bool {
    networkConfirmed && networkName != nil && canRunCommands
  }

  var editorFooter: String {
    let nameBytes = Self.utf8ByteCount(ssidField)
    if nameBytes < Self.minSSIDBytes {
      return L10n.Settings.RadioWiFi.Editor.emptyName
    }
    if nameBytes > Self.maxSSIDBytes {
      return L10n.Settings.RadioWiFi.Editor.nameLength
    }
    if Self.utf8ByteCount(passwordField) > Self.maxPasswordBytes {
      return L10n.Settings.RadioWiFi.Editor.passwordLength
    }
    return L10n.Settings.RadioWiFi.Editor.footer
  }

  var clearMessage: String {
    if transportIsWiFi {
      return L10n.Settings.RadioWiFi.Alert.Clear.wifiMessage
    }
    return L10n.Settings.RadioWiFi.Alert.Clear.message
  }

  var rebootMessage: String {
    guard wifiEnabled else {
      return L10n.Settings.RadioWiFi.Alert.Reboot.disabled
    }
    if let networkName {
      return L10n.Settings.RadioWiFi.Alert.Reboot.join(networkName)
    }
    return L10n.Settings.RadioWiFi.Alert.Reboot.restart
  }

  var linkWarningTitle: String {
    switch pendingWiFiAction {
    case .save:
      L10n.Settings.RadioWiFi.Alert.Save.title
    case .disable, nil:
      L10n.Settings.RadioWiFi.Alert.TurnOff.title
    }
  }

  var linkWarningConfirmTitle: String {
    switch pendingWiFiAction {
    case .save:
      L10n.Localizable.Common.save
    case .disable, nil:
      L10n.Settings.RadioWiFi.Alert.TurnOff.confirm
    }
  }

  func recoverySurface(wifiPageVisible: Bool) -> RecoverySurface? {
    Self.recoverySurface(
      errorMessage: errorMessage,
      editorOpen: editorOpen,
      wifiPageVisible: wifiPageVisible
    )
  }

  static func recoverySurface(
    errorMessage: String?,
    editorOpen: Bool,
    wifiPageVisible: Bool
  ) -> RecoverySurface? {
    guard errorMessage != nil else { return nil }
    if editorOpen { return .editor }
    if wifiPageVisible { return .wifiPage }
    return .connection
  }

  func configure(
    settingsService: @escaping @MainActor () -> SettingsService?,
    connectionState: @escaping @MainActor () -> DeviceConnectionState,
    connectedDevice: @escaping @MainActor () -> DeviceDTO?,
    currentTransportType: @escaping @MainActor () -> TransportType?,
    reboot: @escaping @MainActor () async throws -> Void
  ) {
    settingsServiceProvider = settingsService
    connectionStateProvider = connectionState
    connectedDeviceProvider = connectedDevice
    currentTransportTypeProvider = currentTransportType
    rebootAction = reboot
  }

  /// Clears the live link rows while the phone is down, or owes one snapshot for the current service.
  func noteSessionReadiness() async {
    adoptCurrentRadio()
    guard connectionStateProvider() == .ready, let settings = settingsService else {
      statusConnected = false
      ipAddress = nil
      owedSettingsService = nil
      return
    }
    adoptService(settings)
    owedSettingsService = settings
    await startOwedRead()
  }

  /// Reads again when the editor is closed and no read or write is running. A busy editor keeps the debt.
  func refresh() async {
    await noteSessionReadiness()
  }

  func load() async {
    guard !isBusy else { return }
    adoptCurrentRadio()
    let device = connectedDeviceProvider()
    guard device?.supportsRadioCLI == true else {
      markRadioCLIUnavailable()
      return
    }
    guard let settings = settingsService else {
      wifiSettingsSupport = .unknown
      return
    }
    adoptService(settings)
    await performSnapshot(from: settings)
    await continueOwedRead()
  }

  func loadEditor() async {
    guard !isBusy else { return }
    ssidField = loadedSSID
    passwordField = loadedPassword
    guard let settings = settingsService else { return }
    let radioID = connectedDeviceProvider()?.radioID
    isLoading = true
    do {
      let password = try await settings.getRadioCLIValue(RadioCLIKey.wifiPassword)
      if matches(settings, radioID: radioID), Task.isCancelled == false {
        loadedPassword = Self.storedPassword(password)
        passwordField = loadedPassword
        await refreshRebootPending(from: settings, radioID: radioID)
      }
    } catch is CancellationError {
      // Cancellation leaves the password fields and does not set an error.
    } catch {
      if matches(settings, radioID: radioID) {
        errorMessage = error.userFacingMessage
      }
    }
    isLoading = false
    await continueOwedRead()
  }

  func setWiFiEnabled(_ enabled: Bool) async {
    guard canRunCommands else { return }
    if !enabled, transportIsWiFi {
      presentLinkWarning(.disable)
      return
    }
    await writeEnabled(enabled)
  }

  func requestSave() async {
    guard canSave else { return }
    let submission = WiFiCredentialSubmission(
      ssid: ssidField,
      password: passwordField,
      passwordChanged: passwordField != loadedPassword
    )
    if transportIsWiFi {
      presentLinkWarning(.save(submission))
      return
    }
    await performSave(submission)
  }

  func requestClear() {
    guard canClear else { return }
    showingClearConfirm = true
  }

  func continuePastWiFiWarning() async {
    let action = pendingWiFiAction
    pendingWiFiAction = nil
    showingWiFiWarning = false
    switch action {
    case let .save(submission):
      await performSave(submission)
    case .disable:
      await writeEnabled(false)
    case nil:
      break
    }
  }

  func cancelWiFiWarning() {
    pendingWiFiAction = nil
    showingWiFiWarning = false
  }

  private func presentLinkWarning(_ action: WiFiWarningAction) {
    pendingWiFiAction = action
    showingWiFiWarning = true
  }

  func confirmClear() async {
    guard canClear else { return }
    showingClearConfirm = false
    guard let settings = settingsService else {
      errorMessage = SettingsServiceError.notConnected.userFacingMessage
      return
    }
    let radioID = connectedDeviceProvider()?.radioID
    isWriting = true
    do {
      try await settings.setRadioCLIValue(RadioCLIKey.wifiClear, to: nil)
      if matches(settings, radioID: radioID) {
        loadedSSID = ""
        loadedPassword = ""
        ssidField = ""
        passwordField = ""
        networkName = nil
        networkConfirmed = true
        credentialsConfirmed = true
        await refreshRebootPending(from: settings, radioID: radioID)
      }
    } catch {
      if matches(settings, radioID: radioID) {
        errorMessage = error.userFacingMessage
      }
    }
    isWriting = false
    await continueOwedRead()
  }

  func requestReboot() {
    guard canRunCommands, rebootPending else { return }
    showingRebootConfirm = true
  }

  func cancelReboot() {
    showingRebootConfirm = false
  }

  func confirmReboot() async {
    showingRebootConfirm = false
    guard canRunCommands else { return }
    let radioID = connectedDeviceProvider()?.radioID
    let serviceID = settingsService.map(ObjectIdentifier.init)
    do {
      try await rebootAction()
    } catch {
      guard connectedDeviceProvider()?.radioID == radioID else { return }
      guard settingsService.map(ObjectIdentifier.init) == serviceID else { return }
      switch error {
      case BLEError.notConnected, BLEError.operationTimeout:
        // The radio reboots without a reply and drops the link.
        break
      case WiFiTransportError.notConnected, WiFiTransportError.sendTimeout:
        break
      default:
        errorMessage = error.userFacingMessage
      }
    }
  }

  private func performSave(_ submission: WiFiCredentialSubmission) async {
    guard canRunCommands else { return }
    guard let settings = settingsService else {
      errorMessage = SettingsServiceError.notConnected.userFacingMessage
      return
    }
    let radioID = connectedDeviceProvider()?.radioID
    isWriting = true
    let ssidChanged = submission.ssid != loadedSSID
    do {
      if ssidChanged || submission.passwordChanged {
        try await settings.setRadioCLIValue(RadioCLIKey.wifiSSID, to: submission.ssid)
        if matches(settings, radioID: radioID) {
          loadedSSID = submission.ssid
          networkName = Self.displayedNetworkName(loadedSSID)
          networkConfirmed = true
          credentialsConfirmed = true
        }
      }
      if submission.passwordChanged, matches(settings, radioID: radioID) {
        try await settings.setRadioCLIValue(RadioCLIKey.wifiPassword, to: submission.password)
        if matches(settings, radioID: radioID) {
          loadedPassword = submission.password
        }
      }
      if matches(settings, radioID: radioID) {
        await refreshRebootPending(from: settings, radioID: radioID)
        closeEditor()
      }
    } catch {
      if matches(settings, radioID: radioID) {
        errorMessage = error.userFacingMessage
        await refreshRebootPending(from: settings, radioID: radioID)
      }
    }
    isWriting = false
    await continueOwedRead()
  }

  private func writeEnabled(_ enabled: Bool) async {
    guard canRunCommands else { return }
    guard let settings = settingsService else {
      errorMessage = SettingsServiceError.notConnected.userFacingMessage
      return
    }
    let radioID = connectedDeviceProvider()?.radioID
    isWriting = true
    do {
      try await settings.setRadioCLIValue(
        RadioCLIKey.wifiEnabled,
        to: enabled ? Self.enabledValue : Self.disabledValue
      )
      if matches(settings, radioID: radioID) {
        wifiEnabled = enabled
        await refreshRebootPending(from: settings, radioID: radioID)
      }
    } catch {
      if matches(settings, radioID: radioID) {
        errorMessage = error.userFacingMessage
      }
    }
    isWriting = false
    await continueOwedRead()
  }

  private func performSnapshot(from settings: SettingsService) async {
    let owner = CommandOwner(
      radioID: connectedDeviceProvider()?.radioID,
      serviceID: ObjectIdentifier(settings)
    )
    isLoading = true
    defer { isLoading = false }
    do {
      let enabled = try await settings.getRadioCLIValue(RadioCLIKey.wifiEnabled)
      guard shouldApplySnapshot(owner) else { return }
      switch Self.classifyEnabled(enabled) {
      case .unavailable:
        applyUnavailable()
        return
      case .invalid:
        applyReadFailure(owner: owner, error: SettingsServiceError.invalidResponse)
        return
      case let .enabled(isOn):
        applySupported(owner: owner, isOn: isOn)
      }
      let ssid = try await settings.getRadioCLIValue(RadioCLIKey.wifiSSID)
      guard shouldApplySnapshot(owner) else { return }
      let status = try await settings.getRadioCLIValue(RadioCLIKey.wifiStatus)
      guard shouldApplySnapshot(owner) else { return }
      let ip = try await settings.getRadioCLIValue(RadioCLIKey.wifiIP)
      guard shouldApplySnapshot(owner) else { return }
      let rebootPending = await settings.radioWiFiRebootPending
      guard shouldApplySnapshot(owner) else { return }
      applyDetails(ssid: ssid, status: status, ip: ip, rebootPending: rebootPending)
    } catch is CancellationError {
      // Cancellation leaves the previous rows and does not set an error.
    } catch {
      guard shouldApplySnapshot(owner) else { return }
      applyReadFailure(owner: owner, error: error)
    }
  }

  private func shouldApplySnapshot(_ owner: CommandOwner) -> Bool {
    guard Task.isCancelled == false else { return false }
    guard matches(serviceID: owner.serviceID, radioID: owner.radioID) else { return false }
    if let owed = owedSettingsService, ObjectIdentifier(owed) != owner.serviceID {
      return false
    }
    return true
  }

  /// Starts the owed read when the editor is closed, the phone is ready, and no read or write is running.
  /// A cancelled caller starts that read on a new task so it does not drop the service a replacement caller just owed.
  private func continueOwedRead() async {
    if Task.isCancelled {
      Task { await self.startOwedRead(hopIfCancelled: false) }
      return
    }
    await startOwedRead(hopIfCancelled: true)
  }

  private func startOwedRead() async {
    await startOwedRead(hopIfCancelled: true)
  }

  private func startOwedRead(hopIfCancelled: Bool) async {
    if Task.isCancelled {
      if hopIfCancelled {
        Task { await self.startOwedRead(hopIfCancelled: false) }
      }
      return
    }
    guard connectionStateProvider() == .ready else { return }
    guard editorOpen == false, isBusy == false else { return }
    guard let owed = owedSettingsService else { return }
    guard let settings = settingsService, settings === owed else {
      owedSettingsService = nil
      return
    }
    let device = connectedDeviceProvider()
    if let radioID = device?.radioID, radioID != adoptedRadioID {
      owedSettingsService = nil
      return
    }
    guard device?.supportsRadioCLI == true else {
      owedSettingsService = nil
      markRadioCLIUnavailable()
      return
    }
    owedSettingsService = nil
    await performSnapshot(from: owed)
    await continueOwedRead()
  }

  private func adoptCurrentRadio() {
    guard let radioID = connectedDeviceProvider()?.radioID, radioID != adoptedRadioID else { return }
    resetForNewRadio(radioID)
  }

  private func adoptService(_ settings: SettingsService) {
    let serviceID = ObjectIdentifier(settings)
    guard serviceID != adoptedServiceID else { return }
    invalidateForService(serviceID)
  }

  private func resetForNewRadio(_ radioID: UUID) {
    adoptedRadioID = radioID
    adoptedServiceID = nil
    owedSettingsService = nil
    wifiSettingsSupport = .unknown
    clearConfirmedFields()
    clearDisplayedNetwork()
    errorMessage = nil
    closeEditor()
  }

  private func invalidateForService(_ serviceID: ObjectIdentifier) {
    adoptedServiceID = serviceID
    wifiSettingsSupport = .unknown
    clearConfirmedFields()
    clearDisplayedNetwork()
    errorMessage = nil
    closeEditor()
  }

  private func markRadioCLIUnavailable() {
    wifiSettingsSupport = .unknown
    enabledConfirmed = false
    networkConfirmed = false
    credentialsConfirmed = false
    confirmedRadioID = nil
    confirmedServiceID = nil
  }

  private func clearConfirmedFields() {
    enabledConfirmed = false
    networkConfirmed = false
    credentialsConfirmed = false
    confirmedRadioID = nil
    confirmedServiceID = nil
    rebootPending = false
    statusConnected = false
    ipAddress = nil
    pendingWiFiAction = nil
    showingWiFiWarning = false
    showingClearConfirm = false
    showingRebootConfirm = false
  }

  private func clearDisplayedNetwork() {
    networkName = nil
    wifiEnabled = false
    loadedSSID = ""
    loadedPassword = ""
    ssidField = ""
    passwordField = ""
  }

  private func closeEditor() {
    ignoreEditorClose = true
    editorOpen = false
    ignoreEditorClose = false
  }

  private func applySupported(owner: CommandOwner, isOn: Bool) {
    if let radioID = owner.radioID {
      adoptedRadioID = radioID
    }
    adoptedServiceID = owner.serviceID
    confirmedRadioID = owner.radioID
    confirmedServiceID = owner.serviceID
    enabledConfirmed = true
    wifiSettingsSupport = .supported
    wifiEnabled = isOn
  }

  private func applyUnavailable() {
    wifiSettingsSupport = .unavailable
    enabledConfirmed = false
    networkConfirmed = false
    credentialsConfirmed = false
    confirmedRadioID = nil
    confirmedServiceID = nil
    statusConnected = false
    ipAddress = nil
    rebootPending = false
    clearDisplayedNetwork()
    errorMessage = nil
  }

  private func applyDetails(ssid: String?, status: String?, ip: String?, rebootPending: Bool) {
    networkConfirmed = true
    credentialsConfirmed = true
    loadedSSID = Self.storedSSID(ssid)
    networkName = Self.displayedNetworkName(loadedSSID)
    let statusText = status?.trimmingCharacters(in: .whitespacesAndNewlines)
    statusConnected = statusText == Self.connectedStatus
    ipAddress = Self.displayedIP(ip)
    self.rebootPending = rebootPending
    errorMessage = nil
  }

  /// Support from this service stays. Association and the IP do not, so a switch cannot use them.
  private func applyReadFailure(owner: CommandOwner, error: Error) {
    errorMessage = error.userFacingMessage
    statusConnected = false
    ipAddress = nil
    let stillSupported = wifiSettingsSupport == .supported
      && enabledConfirmed
      && confirmedServiceID == owner.serviceID
      && confirmedRadioID == owner.radioID
    if stillSupported { return }
  }

  private func matches(_ settings: SettingsService, radioID: UUID?) -> Bool {
    matches(serviceID: ObjectIdentifier(settings), radioID: radioID)
  }

  private func matches(serviceID: ObjectIdentifier, radioID: UUID?) -> Bool {
    guard let settings = settingsService, ObjectIdentifier(settings) == serviceID else { return false }
    return connectedDeviceProvider()?.radioID == radioID
  }

  private func refreshRebootPending(from settings: SettingsService, radioID: UUID?) async {
    let pending = await settings.radioWiFiRebootPending
    guard matches(settings, radioID: radioID) else { return }
    rebootPending = pending
  }

  private static func classifyEnabled(_ value: String?) -> EnabledProbe {
    guard let value else { return .unavailable }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed == enabledValue { return .enabled(true) }
    if trimmed == disabledValue { return .enabled(false) }
    return .invalid
  }

  private static func utf8ByteCount(_ value: String) -> Int {
    Data(value.utf8).count
  }

  /// The SSID reply, unchanged. `(not set)` and an empty value mean no network.
  private static func storedSSID(_ value: String?) -> String {
    guard let value, !value.isEmpty, value != RadioCLIReply.notSet else { return "" }
    return value
  }

  /// The password reply, unchanged. The password has no sentinel.
  private static func storedPassword(_ value: String?) -> String {
    value ?? ""
  }

  private static func displayedNetworkName(_ ssid: String) -> String? {
    if ssid.isEmpty || ssid == RadioCLIReply.notSet { return nil }
    return ssid
  }

  /// `(not connected)` and an empty value mean no address. Other text is kept.
  private static func displayedIP(_ value: String?) -> String? {
    guard let value, !value.isEmpty, value != RadioCLIReply.notConnected else { return nil }
    return value
  }
}
