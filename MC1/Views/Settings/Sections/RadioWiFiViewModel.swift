import Foundation
import MC1Services
import Observation

@Observable
@MainActor
final class RadioWiFiViewModel {
  enum RecoverySurface: Equatable { case connection, wifiPage, editor }

  struct Context: Equatable, Sendable {
    let radioID: UUID
    let serviceID: ObjectIdentifier
    let wifiContextID: UUID
  }

  struct ObservedWiFiState {
    var enabled = false
    var enabledRevision: UInt64?
    var networkName: String?
    var networkRevision: UInt64?
    var networkNameIsCurrent = false
    var associated: Bool?
    var ipAddress: String?
  }

  struct ReadDebt: Equatable {
    let owner: Context
    let revision: UInt64
  }

  struct Editor {
    let id: UUID
    var baseline: RadioWiFiCredentialBaseline?
    var ssid = ""
    var password = ""
    var ssidEdited = false
    var passwordEdited = false
  }

  enum Operation {
    case snapshot(UUID, Context)
    case editor(UUID, Context, UUID)
    case write(UUID, Context)

    var id: UUID {
      switch self {
      case let .snapshot(id, _), let .editor(id, _, _), let .write(id, _): id
      }
    }
  }

  struct Submission: Sendable {
    let owner: Context
    let baseline: RadioWiFiCredentialBaseline
    let ssid: String
    let password: String
  }

  enum AlertAction: Sendable { case wifiWarning, clear, reboot }

  enum Confirmation {
    case save(Submission)
    case disable(Context, UInt64)
    case clear(Context, UInt64)
    case reboot(Context, UInt64)
  }

  private var settingsServiceProvider: @MainActor () -> SettingsService? = { nil }
  private var connectionStateProvider: @MainActor () -> DeviceConnectionState = { .disconnected }
  private var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  private var currentTransportTypeProvider: @MainActor () -> TransportType? = { nil }

  var context: Context?
  var enabledContext: Context?
  var observation = ObservedWiFiState()
  var editor: Editor?
  var operation: Operation?
  var confirmation: Confirmation?
  var owedRead: ReadDebt?
  var refreshTask: Task<Void, Never>?
  var refreshTaskID: UUID?

  private(set) var wifiSettingsSupport: ConnectionLinkRows.WiFiSettingsSupport = .unknown
  private(set) var editorOpen = false
  var errorMessage: String?

  var settingsService: SettingsService? {
    settingsServiceProvider()
  }

  var connectionState: DeviceConnectionState {
    connectionStateProvider()
  }

  var connectedDevice: DeviceDTO? {
    connectedDeviceProvider()
  }

  var currentTransportType: TransportType? {
    currentTransportTypeProvider()
  }

  var showsSection: Bool {
    wifiSettingsSupport == .supported
  }

  var isBusy: Bool {
    operation != nil
  }

  var isWriting: Bool {
    if case .write = operation { true } else { false }
  }

  var isLoading: Bool {
    isBusy && !isWriting
  }

  var wifiEnabled: Bool {
    observation.enabled
  }

  var networkName: String? {
    observation.networkName
  }

  var statusConnected: Bool {
    observation.associated == true
  }

  var networkValue: String {
    networkName ?? (observation.networkRevision == nil
      ? L10n.Settings.DeviceInfo.unknown : L10n.Settings.RadioWiFi.Network.notSet)
  }

  var statusValue: String {
    switch observation.associated {
    case nil: L10n.Settings.DeviceInfo.unknown
    case true: L10n.Settings.RadioWiFi.Status.connected
    case false: L10n.Settings.RadioWiFi.Status.notConnected
    }
  }

  var ipAddress: String? {
    observation.ipAddress
  }

  var rebootPending: Bool {
    guard let context, context == enabledContext, let binding = binding(for: context) else { return false }
    return binding.state.rebootPending
  }

  var ssidField: String {
    get { editor?.ssid ?? "" }
    set {
      ensureEditor()
      editor?.ssid = newValue
      editor?.ssidEdited = true
    }
  }

  var passwordField: String {
    get { editor?.password ?? "" }
    set {
      ensureEditor()
      editor?.password = newValue
      editor?.passwordEdited = true
    }
  }

  var canRunCommands: Bool {
    guard !isBusy, let context, enabledContext == context, observation.enabledRevision != nil else { return false }
    return matches(context)
  }

  var canEditNetwork: Bool {
    canRunCommands && showsSection
  }

  var needsEditorRead: Bool {
    guard let editor else { return false }
    guard let baseline = editor.baseline, let context, let binding = binding(for: context) else { return true }
    if let owedRead, owedRead.owner == context, owedRead.revision > baseline.revision { return true }
    return binding.contextID != baseline.contextID || binding.state.stableRevision() != baseline.revision
  }

  var canClear: Bool {
    guard canRunCommands, let context, let binding = binding(for: context),
          let revision = binding.state.stableRevision() else { return false }
    return networkName != nil && observation.networkNameIsCurrent && observation.networkRevision == revision
  }

  var canSave: Bool {
    guard canEditNetwork, !needsEditorRead, let context, let binding = binding(for: context),
          let editor, let baseline = editor.baseline, baseline.editorID == editor.id,
          binding.contextID == baseline.contextID, binding.state.stableRevision() == baseline.revision else { return false }
    let changed = !RadioWiFiCredentialBaseline.hasSameBytes(editor.ssid, baseline.ssid)
      || !RadioWiFiCredentialBaseline.hasSameBytes(editor.password, baseline.password)
    return changed && RadioWiFiCredentialBaseline.validCredentials(ssid: editor.ssid, password: editor.password)
  }

  var editorFooter: String {
    let bytes = ssidField.utf8.count
    if bytes < RadioWiFiCredentialBaseline.minSSIDBytes { return L10n.Settings.RadioWiFi.Editor.emptyName }
    if bytes > RadioWiFiCredentialBaseline.maxSSIDBytes { return L10n.Settings.RadioWiFi.Editor.nameLength }
    if passwordField.utf8.count > RadioWiFiCredentialBaseline.maxPasswordBytes {
      return L10n.Settings.RadioWiFi.Editor.passwordLength
    }
    if !RadioWiFiCredentialBaseline.isRepresentable(ssidField)
      || !RadioWiFiCredentialBaseline.isRepresentable(passwordField) {
      return L10n.Settings.RadioWiFi.Editor.unsupportedCharacter
    }
    return L10n.Settings.RadioWiFi.Editor.footer
  }

  var clearMessage: String {
    currentTransportType == .wifi
      ? L10n.Settings.RadioWiFi.Alert.Clear.wifiMessage : L10n.Settings.RadioWiFi.Alert.Clear.message
  }

  var rebootMessage: String {
    guard let context, enabledContext == context, let binding = binding(for: context),
          let revision = binding.state.stableRevision(), observation.enabledRevision == revision else {
      return L10n.Settings.RadioWiFi.Alert.Reboot.restart
    }
    guard wifiEnabled else { return L10n.Settings.RadioWiFi.Alert.Reboot.disabled }
    guard observation.networkNameIsCurrent, observation.networkRevision == revision else {
      return L10n.Settings.RadioWiFi.Alert.Reboot.restart
    }
    return networkName.map(L10n.Settings.RadioWiFi.Alert.Reboot.join) ?? L10n.Settings.RadioWiFi.Alert.Reboot.restart
  }

  var showingWiFiWarning: Bool {
    get {
      switch confirmation { case .save, .disable: true; default: false }
    }
    set { if !newValue { cancelWiFiWarning() } }
  }

  var showingClearConfirm: Bool {
    get { if case .clear = confirmation { true } else { false } }
    set { if !newValue, case .clear = confirmation { confirmation = nil } }
  }

  var showingRebootConfirm: Bool {
    get { if case .reboot = confirmation { true } else { false } }
    set { if !newValue { cancelReboot() } }
  }

  var linkWarningTitle: String {
    if case .save = confirmation { L10n.Settings.RadioWiFi.Alert.Save.title } else {
      L10n.Settings.RadioWiFi.Alert.TurnOff.title
    }
  }

  var linkWarningConfirmTitle: String {
    if case .save = confirmation { L10n.Localizable.Common.save } else {
      L10n.Settings.RadioWiFi.Alert.TurnOff.confirm
    }
  }

  func recoverySurface(wifiPageVisible: Bool) -> RecoverySurface? {
    Self.recoverySurface(errorMessage: errorMessage, editorOpen: editorOpen, wifiPageVisible: wifiPageVisible)
  }

  static func recoverySurface(errorMessage: String?, editorOpen: Bool, wifiPageVisible: Bool) -> RecoverySurface? {
    guard errorMessage != nil else { return nil }
    return editorOpen ? .editor : (wifiPageVisible ? .wifiPage : .connection)
  }

  func configure(
    settingsService: @escaping @MainActor () -> SettingsService?,
    connectionState: @escaping @MainActor () -> DeviceConnectionState,
    connectedDevice: @escaping @MainActor () -> DeviceDTO?,
    currentTransportType: @escaping @MainActor () -> TransportType?
  ) {
    settingsServiceProvider = settingsService
    connectionStateProvider = connectionState
    connectedDeviceProvider = connectedDevice
    currentTransportTypeProvider = currentTransportType
  }

  func matches(_ owner: Context) -> Bool {
    connectionState == .ready && matchesContext(owner)
  }

  func matchesContext(_ owner: Context) -> Bool {
    binding(for: owner) != nil
  }

  func binding(for owner: Context) -> RadioWiFiBinding? {
    guard connectedDevice?.radioID == owner.radioID, let settingsService,
          ObjectIdentifier(settingsService) == owner.serviceID else { return nil }
    let binding = settingsService.radioWiFiBinding
    guard binding.contextID == owner.wifiContextID, binding.state.radioID == owner.radioID else { return nil }
    return binding
  }

  func oweRead(_ owner: Context) {
    guard let binding = binding(for: owner) else { return }
    owedRead = ReadDebt(owner: owner, revision: binding.state.revision)
  }

  func finish(_ id: UUID) {
    if operation?.id == id { operation = nil }
  }

  func setSupport(_ support: ConnectionLinkRows.WiFiSettingsSupport) {
    wifiSettingsSupport = support
  }

  func setEditorPresented(_ presented: Bool) {
    editorOpen = presented
  }
}
