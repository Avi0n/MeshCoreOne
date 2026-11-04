import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("Radio WiFi view model")
@MainActor
struct RadioWiFiViewModelTests {
  @Test
  func `unknown command hides the section`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReply("Unknown command")

    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection == false)
    #expect(harness.viewModel.wifiSettingsSupport == .unavailable)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(await harness.mock.runCliCommandCalls == ["get wifi.enabled"])
  }

  @Test
  func `values and sentinels map onto the rows`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.wifiSettingsSupport == .supported)
    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.statusConnected)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.ipAddress == "10.0.0.8")

    await harness.mock.setCLIReplyQueue([
      "> 0",
      "> (not set)",
      "> (not connected)",
      "> (not connected)"
    ])
    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.wifiEnabled == false)
    #expect(harness.viewModel.statusConnected == false)
    #expect(harness.viewModel.networkName == nil)
    #expect(harness.viewModel.ipAddress == nil)
  }

  @Test
  func `save sends only the ssid when the password is unchanged`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    harness.viewModel.ssidField = "newssid"
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.requestSave()

    let calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == ["set wifi.ssid newssid"])
  }

  @Test
  func `save is disabled when nothing changed`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    let before = await harness.mock.runCliCommandCalls.count

    #expect(harness.viewModel.canSave == false)
    await harness.viewModel.requestSave()

    let calls = await harness.mock.runCliCommandCalls
    #expect(calls.count == before)
  }

  @Test
  func `a failed first password read keeps Save disabled`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> stored", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    harness.viewModel.openEditor()
    await harness.mock.setCLIReplyQueue(["> stored"])
    await harness.mock.failCLICommand(after: 1, MeshCoreError.timeout)

    await harness.viewModel.loadEditor()
    harness.viewModel.ssidField = "replacement"
    let before = await harness.mock.runCliCommandCalls.count

    #expect(harness.viewModel.errorMessage != nil)
    #expect(harness.viewModel.canSave == false)
    await harness.viewModel.requestSave()

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(harness.viewModel.editorOpen)
  }

  @Test(arguments: CredentialReadFailure.allCases)
  private func `unknown password knowledge requires a fresh editor Retry`(_ failure: CredentialReadFailure) async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> stored", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    harness.viewModel.openEditor()
    await failure.prime(harness.mock)

    await harness.viewModel.loadEditor()
    harness.viewModel.ssidField = "draft"
    let before = await harness.mock.runCliCommandCalls.count
    #expect(!harness.viewModel.canSave)
    #expect(harness.viewModel.needsEditorRead)
    await harness.viewModel.requestSave()
    #expect(await harness.mock.runCliCommandCalls.count == before)

    await harness.mock.setCLIReplyQueue(["> stored", "> recovered-key"])
    await harness.viewModel.loadEditor()

    #expect(harness.viewModel.canSave)
    #expect(harness.viewModel.ssidField == "draft")
    #expect(!harness.viewModel.needsEditorRead)
    #expect(harness.viewModel.errorMessage == nil)
    await harness.viewModel.closeEditor()
    #expect(harness.viewModel.passwordField.isEmpty)
    #expect(harness.viewModel.ssidField.isEmpty)
  }

  @Test
  func `Retry refreshes an untouched password after a foreign mutation`() async throws {
    let harness = await Harness.make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await harness.viewModel.noteSessionReadiness()
    harness.viewModel.openEditor()
    await harness.viewModel.loadEditor()
    harness.viewModel.ssidField = "draft"
    let settings = try #require(harness.settings)
    _ = try await settings.runCliCommand("set wifi.pwd terminal-key")
    #expect(!harness.viewModel.canSave)

    await harness.viewModel.loadEditor()
    let passwordRefreshed = harness.viewModel.passwordField == "terminal-key"
    #expect(passwordRefreshed)
    let before = await harness.mock.runCliCommandCalls.count
    await harness.viewModel.requestSave()

    #expect(await Array(harness.mock.runCliCommandCalls.dropFirst(before)) == ["set wifi.ssid draft"])
    await harness.mock.bootWiFiFirmware()
    #expect(await harness.mock.wiFiBootUses(ssid: "draft", password: "terminal-key"))
    #expect(harness.viewModel.passwordField.isEmpty)
  }

  @Test
  func `repeated raw mutations invalidate an open editor after each fresh Retry`() async throws {
    let harness = await Harness.make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await harness.viewModel.noteSessionReadiness()
    harness.viewModel.openEditor()
    await harness.viewModel.loadEditor()
    harness.viewModel.ssidField = "draft"
    let settings = try #require(harness.settings)

    for value in ["first-key", "second-key"] {
      _ = try await settings.runCliCommand("set wifi.pwd \(value)")
      await harness.viewModel.noteSessionReadiness()
      #expect(harness.viewModel.needsEditorRead)
      #expect(!harness.viewModel.canSave)
      await harness.viewModel.loadEditor()
      #expect(!harness.viewModel.needsEditorRead)
      #expect(harness.viewModel.canSave)
      #expect(harness.viewModel.ssidField == "draft")
      let passwordRefreshed = harness.viewModel.passwordField == value
      #expect(passwordRefreshed)
    }
    await harness.viewModel.closeEditor()
  }

  @Test
  func `same-service unreadiness freezes the draft and replacement erases it`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "stored", password: "stored-key")
    harness.viewModel.openEditor()
    harness.viewModel.ssidField = "draft"
    harness.connectionState = .syncing
    await harness.viewModel.noteSessionReadiness()

    #expect(!harness.viewModel.canSave)
    #expect(harness.viewModel.editorOpen)
    #expect(harness.viewModel.ssidField == "draft")
    #expect(!harness.viewModel.statusConnected)

    harness.connectionState = .ready
    await harness.viewModel.noteSessionReadiness()
    #expect(harness.viewModel.canSave)
    let replacement = MockConfigurationSession()
    await replacement.setCLIReply("Unknown command")
    harness.settings = SettingsService(session: replacement, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await harness.viewModel.noteSessionReadiness()

    #expect(!harness.viewModel.editorOpen)
    #expect(harness.viewModel.passwordField.isEmpty)
    #expect(harness.viewModel.ssidField.isEmpty)
    #expect(!harness.viewModel.canSave)
  }

  @Test
  func `a WiFi warning cannot submit credentials after a raw mutation`() async throws {
    let harness = await Harness.make()
    harness.transport.value = .wifi
    await harness.loadNetwork(ssid: "stored", password: "stored-key")
    harness.viewModel.openEditor()
    harness.viewModel.ssidField = "draft"
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.showingWiFiWarning)
    let settings = try #require(harness.settings)
    _ = try await settings.runCliCommand("set wifi.enabled 0")
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.confirmFromAlert(.wifiWarning).value

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(!harness.viewModel.showingWiFiWarning)
    #expect(!harness.viewModel.canSave)
    #expect(harness.viewModel.needsEditorRead)
    #expect(harness.viewModel.editorOpen)
  }

  @Test
  func `an uncertain enable write reads back before restoring commands`() async {
    let harness = await Harness.make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await harness.viewModel.noteSessionReadiness()
    await harness.mock.failWiFiCommand("set wifi.enabled 0", with: .timeoutAfterApply)
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.setWiFiEnabled(false)

    let readback = await harness.mock.runCliCommandCalls.dropFirst(before)
    #expect(Array(readback) == [
      "set wifi.enabled 0", "get wifi.enabled", "get wifi.ssid", "get wifi.status", "get wifi.ip"
    ])
    #expect(harness.viewModel.canRunCommands)
    #expect(!harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.rebootPending)
    await harness.viewModel.refresh()
    #expect(harness.viewModel.canRunCommands)
    #expect(!harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.statusConnected)
    #expect(harness.viewModel.rebootPending)
    #expect(harness.viewModel.errorMessage == nil)
  }

  @Test
  func `an empty password is sent with a trailing space`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    harness.viewModel.passwordField = ""
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.requestSave()

    let calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == ["set wifi.ssid myssid", "set wifi.pwd "])
  }

  @Test
  func `an overlong name or password is rejected with no command`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "")
    let before = await harness.mock.runCliCommandCalls.count

    harness.viewModel.ssidField = String(repeating: "a", count: 33)
    #expect(harness.viewModel.canSave == false)
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.errorMessage == nil)

    harness.viewModel.ssidField = "myssid"
    harness.viewModel.passwordField = String(repeating: "b", count: 64)
    #expect(harness.viewModel.canSave == false)
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.errorMessage == nil)

    let calls = await harness.mock.runCliCommandCalls
    #expect(calls.count == before)
  }

  @Test
  func `save stays disabled until the name and password are inside the byte limits`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")

    harness.viewModel.ssidField = ""
    #expect(harness.viewModel.canSave == false)

    harness.viewModel.ssidField = "a"
    #expect(harness.viewModel.canSave)

    harness.viewModel.ssidField = String(repeating: "a", count: 32)
    #expect(harness.viewModel.canSave)

    harness.viewModel.ssidField = "myssid"
    harness.viewModel.passwordField = String(repeating: "b", count: 63)
    #expect(harness.viewModel.canSave)

    harness.viewModel.passwordField = ""
    #expect(harness.viewModel.canSave)
  }

  @Test
  func `the editor footer names the first invalid field`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "")

    harness.viewModel.ssidField = ""
    harness.viewModel.passwordField = String(repeating: "b", count: 64)
    #expect(harness.viewModel.editorFooter == L10n.Settings.RadioWiFi.Editor.emptyName)

    harness.viewModel.ssidField = String(repeating: "a", count: 33)
    #expect(harness.viewModel.editorFooter == L10n.Settings.RadioWiFi.Editor.nameLength)

    harness.viewModel.ssidField = "myssid"
    #expect(harness.viewModel.editorFooter == L10n.Settings.RadioWiFi.Editor.passwordLength)

    harness.viewModel.passwordField = ""
    #expect(harness.viewModel.editorFooter == L10n.Settings.RadioWiFi.Editor.footer)
  }

  @Test
  func `clear is available only when a network name is stored`() async {
    let missing = await Harness.make()
    await missing.mock.setCLIReplyQueue([
      "> 1",
      "> (not set)",
      "> (not connected)",
      "> (not connected)"
    ])
    await missing.viewModel.noteSessionReadiness()
    #expect(missing.viewModel.networkName == nil)
    #expect(missing.viewModel.canClear == false)

    let sentinel = await Harness.make()
    await sentinel.mock.setCLIReplyQueue([
      "> 1",
      "> (not connected)",
      "> connected",
      "> (not connected)"
    ])
    await sentinel.viewModel.noteSessionReadiness()
    #expect(sentinel.viewModel.networkName == "(not connected)")
    #expect(sentinel.viewModel.canClear)
  }

  @Test
  func `an accepted write shows the reboot flag and a rejected write does not`() async {
    let accepted = await Harness.make()
    await accepted.loadNetwork(ssid: "myssid", password: "")
    accepted.viewModel.ssidField = "other"
    await accepted.viewModel.requestSave()
    #expect(accepted.viewModel.rebootPending)
    #expect(await accepted.settings?.radioWiFiRebootPending == true)

    let rejected = await Harness.make()
    await rejected.loadNetwork(ssid: "myssid", password: "")
    await rejected.mock.setCLIReply("Error: rejected")
    rejected.viewModel.ssidField = "other"
    await rejected.viewModel.requestSave()
    #expect(rejected.viewModel.rebootPending == false)
  }

  @Test
  func `save on wifi sends nothing until continue`() async {
    let harness = await Harness.make()
    harness.transport.value = .wifi
    await harness.loadNetwork(ssid: "myssid", password: "")
    harness.viewModel.ssidField = "other"
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.requestSave()

    var calls = await harness.mock.runCliCommandCalls
    #expect(calls.count == before)
    #expect(harness.viewModel.showingWiFiWarning)
    #expect(harness.viewModel.linkWarningTitle == L10n.Settings.RadioWiFi.Alert.Save.title)
    #expect(harness.viewModel.linkWarningConfirmTitle == L10n.Localizable.Common.save)

    await harness.viewModel.confirmFromAlert(.wifiWarning).value
    calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == ["set wifi.ssid other"])
  }

  @Test
  func `toggle off on wifi cancel leaves wifi enabled`() async {
    let harness = await Harness.make()
    harness.transport.value = .wifi
    await harness.loadNetwork(ssid: "myssid", password: "")
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.setWiFiEnabled(false)

    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.showingWiFiWarning)
    #expect(harness.viewModel.linkWarningTitle == L10n.Settings.RadioWiFi.Alert.TurnOff.title)
    #expect(harness.viewModel.linkWarningConfirmTitle == L10n.Settings.RadioWiFi.Alert.TurnOff.confirm)
    harness.viewModel.cancelWiFiWarning()
    #expect(harness.viewModel.showingWiFiWarning == false)
    #expect(harness.viewModel.wifiEnabled)
    let calls = await harness.mock.runCliCommandCalls
    #expect(calls.count == before)
  }

  @Test
  func `reboot to apply calls reboot only after confirm`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "")
    harness.viewModel.ssidField = "other"
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.rebootPending)

    harness.viewModel.requestReboot()
    harness.viewModel.cancelReboot()
    #expect(await harness.mock.rebootCallCount == 0)
    #expect(harness.viewModel.showingRebootConfirm == false)

    harness.viewModel.requestReboot()
    await harness.viewModel.confirmFromAlert(.reboot).value
    #expect(await harness.mock.rebootCallCount == 1)
  }

  @Test
  func `a load that finishes after services change applies nothing`() async {
    let mock = MockConfigurationSession()
    await mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    let device = Harness.radio()
    let first = SettingsService(session: mock, radioWiFiState: RadioWiFiState(radioID: device.radioID))
    let replacement = SettingsService(session: mock, radioWiFiState: RadioWiFiState(radioID: device.radioID))
    let viewModel = RadioWiFiViewModel()
    var current: SettingsService? = first
    viewModel.configure(
      settingsService: {
        let settings = current
        current = replacement
        return settings
      },
      connectionState: { .ready },
      connectedDevice: { device },
      currentTransportType: { .bluetooth }
    )

    await viewModel.load()

    #expect(viewModel.showsSection == false)
    #expect(viewModel.networkName == nil)
    #expect(await mock.runCliCommandCalls.isEmpty)
  }

  @Test
  func `a password-only edit sends a spaced ssid unchanged`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "Field Net ", password: "old")
    harness.viewModel.passwordField = "new"
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.requestSave()

    let calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == ["set wifi.ssid Field Net ", "set wifi.pwd new"])
  }

  @Test
  func `credentials keep spaces and another key's sentinel`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue([
      "> 1",
      "> (not connected)",
      "> connected",
      "> (not connected)"
    ])
    await harness.viewModel.noteSessionReadiness()
    await harness.mock.setCLIReplyQueue(["> (not connected)", ">  pw "])
    await harness.viewModel.loadEditor()

    #expect(harness.viewModel.networkName == "(not connected)")
    #expect(harness.viewModel.passwordField == " pw ")
    #expect(harness.viewModel.canSave == false)
  }

  @Test
  func `a save sends the submitted values when the fields change during the ssid reply`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "old", password: "old")
    await harness.mock.holdNextCLIReply()
    harness.viewModel.ssidField = "A"
    harness.viewModel.passwordField = "a"
    let before = await harness.mock.runCliCommandCalls.count
    let task = Task { await harness.viewModel.requestSave() }

    try await waitUntil("ssid command held") {
      await harness.mock.heldCLICommand == "set wifi.ssid A"
    }
    harness.viewModel.ssidField = "B"
    harness.viewModel.passwordField = "b"
    await harness.mock.releaseHeldCLIReply()
    await task.value

    let calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == ["set wifi.ssid A", "set wifi.pwd a"])
    #expect(harness.viewModel.networkName == "A")
    #expect(harness.viewModel.editorOpen == false)
  }

  @Test
  func `continue on wifi sends the values captured at save`() async {
    let harness = await Harness.make()
    harness.transport.value = .wifi
    await harness.loadNetwork(ssid: "old", password: "old")
    harness.viewModel.ssidField = "A"
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.requestSave()
    harness.viewModel.ssidField = "B"
    await harness.viewModel.confirmFromAlert(.wifiWarning).value

    let calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == ["set wifi.ssid A"])
  }

  @Test
  func `no other wifi load or write starts while a save is pending`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "old", password: "old")
    await harness.mock.holdNextCLIReply()
    harness.viewModel.ssidField = "A"
    harness.viewModel.passwordField = "a"
    let before = await harness.mock.runCliCommandCalls.count
    let task = Task { await harness.viewModel.requestSave() }
    try await waitUntil("save command held") {
      await harness.mock.heldCLICommand == "set wifi.ssid A"
    }

    await harness.viewModel.requestSave()
    await harness.viewModel.load()
    await harness.viewModel.setWiFiEnabled(false)
    await harness.viewModel.confirmFromAlert(.clear).value
    await harness.mock.releaseHeldCLIReply()
    await task.value

    let calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == ["set wifi.ssid A", "set wifi.pwd a"])
  }

  @Test
  func `the editor is busy until the password read returns`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "old", password: "old")
    await harness.mock.holdCLIReply(after: 1)
    await harness.mock.setCLIReplyQueue(["> old", "> pw"])
    let task = Task { await harness.viewModel.loadEditor() }
    try await waitUntil("password read held") {
      await harness.mock.heldCLICommand == "get wifi.pwd"
    }

    #expect(harness.viewModel.isBusy)
    #expect(harness.viewModel.canSave == false)
    await harness.mock.releaseHeldCLIReply()
    await task.value

    #expect(harness.viewModel.passwordField == "pw")
    #expect(harness.viewModel.isBusy == false)
  }

  @Test
  func `a partial save shows the accepted ssid`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "old", password: "")
    harness.viewModel.ssidField = "new"
    harness.viewModel.passwordField = "P"
    harness.viewModel.openEditor()
    await harness.mock.setCLIReplyQueue(["OK", "Error: bad"])
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.requestSave()

    #expect(harness.viewModel.networkName == "new")
    #expect(harness.viewModel.editorOpen)
    #expect(harness.viewModel.errorMessage != nil)

    await harness.mock.setCLIReply("OK")
    await harness.viewModel.requestSave()

    let calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == [
      "set wifi.ssid new",
      "set wifi.pwd P",
      "set wifi.ssid new",
      "set wifi.pwd P"
    ])
  }

  @Test
  func `clear on wifi asks once and names the current address`() async {
    let harness = await Harness.make()
    harness.transport.value = .wifi
    await harness.loadNetwork(ssid: "myssid", password: "secret")

    harness.viewModel.requestClear()

    #expect(harness.viewModel.showingClearConfirm)
    #expect(harness.viewModel.showingWiFiWarning == false)
    #expect(harness.viewModel.clearMessage == L10n.Settings.RadioWiFi.Alert.Clear.wifiMessage)
  }

  @Test
  func `clear on bluetooth uses the stored network message`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")

    harness.viewModel.requestClear()

    #expect(harness.viewModel.showingClearConfirm)
    #expect(harness.viewModel.clearMessage == L10n.Settings.RadioWiFi.Alert.Clear.message)
  }

  @Test
  func `reboot message names the stored network`() async {
    let named = await Harness.make()
    await named.loadNetwork(ssid: "Field Net", password: "")
    #expect(named.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.join("Field Net"))

    let unnamed = await Harness.make()
    await unnamed.mock.setCLIReplyQueue([
      "> 1",
      "> (not set)",
      "> (not connected)",
      "> (not connected)"
    ])
    await unnamed.viewModel.noteSessionReadiness()
    #expect(unnamed.viewModel.networkName == nil)
    #expect(unnamed.viewModel.wifiEnabled)
    #expect(unnamed.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.restart)

    let disabledNamed = await Harness.make()
    await disabledNamed.mock.setCLIReplyQueue([
      "> 0", "> Field Net", "> (not connected)", "> (not connected)"
    ])
    await disabledNamed.viewModel.noteSessionReadiness()
    #expect(disabledNamed.viewModel.networkName == "Field Net")
    #expect(disabledNamed.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.disabled)

    let disabledUnset = await Harness.make()
    await disabledUnset.mock.setCLIReplyQueue([
      "> 0", "> (not set)", "> (not connected)", "> (not connected)"
    ])
    await disabledUnset.viewModel.noteSessionReadiness()
    #expect(disabledUnset.viewModel.networkName == nil)
    #expect(disabledUnset.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.disabled)
  }

  @Test
  func `an accepted disable keeps the stored name and the off confirmation`() async {
    let harness = await Harness.make()
    harness.transport.value = .wifi
    await harness.loadNetwork(ssid: "Field Net", password: "")

    await harness.viewModel.setWiFiEnabled(false)
    #expect(harness.viewModel.showingWiFiWarning)
    await harness.viewModel.confirmFromAlert(.wifiWarning).value

    #expect(harness.viewModel.wifiEnabled == false)
    #expect(harness.viewModel.networkName == "Field Net")
    #expect(harness.viewModel.rebootPending)
    #expect(harness.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.disabled)
    let calls = await harness.mock.runCliCommandCalls
    #expect(calls.contains("set wifi.enabled 0"))
  }

  @Test
  func `a rejected disable keeps the enabled confirmation`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "Field Net", password: "")
    await harness.mock.setCLIReply("Error: rejected")

    await harness.viewModel.setWiFiEnabled(false)

    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.networkName == "Field Net")
    #expect(harness.viewModel.rebootPending == false)
    #expect(harness.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.join("Field Net"))
  }

  @Test
  func `a dropped session keeps the stored network and clears the live rows`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    await harness.mock.setCLIReplyQueue(["> myssid", "> original-key"])
    await harness.viewModel.loadEditor()
    harness.viewModel.ssidField = "myssid"
    harness.viewModel.passwordField = "pw"
    await harness.mock.setCLIReply("OK")
    await harness.viewModel.requestSave()
    let before = await harness.mock.runCliCommandCalls.count

    harness.connectionState = .syncing
    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.rebootPending)
    #expect(harness.viewModel.statusConnected == false)
    #expect(harness.viewModel.ipAddress == nil)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(await harness.mock.runCliCommandCalls.count == before)
  }

  @Test
  func `a missing service keeps the section and reports no error`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    let before = await harness.mock.runCliCommandCalls.count

    harness.settings = nil
    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.statusConnected == false)
    #expect(harness.viewModel.ipAddress == nil)
    #expect(await harness.mock.runCliCommandCalls.count == before)
  }

  @Test
  func `a ready service replaces the rows and the reboot flag`() async throws {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    #expect(harness.viewModel.rebootPending == false)

    let nextMock = MockConfigurationSession()
    let next = SettingsService(session: nextMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await nextMock.setCLIReply("OK")
    try await next.setRadioCLIValue("wifi.enabled", to: "0")
    await nextMock.setCLIReplyQueue(["> 0", "> cafe", "> (not connected)", "> 192.168.0.4"])
    harness.settings = next

    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.wifiEnabled == false)
    #expect(harness.viewModel.networkName == "cafe")
    #expect(harness.viewModel.statusConnected == false)
    #expect(harness.viewModel.ipAddress == "192.168.0.4")
    #expect(harness.viewModel.rebootPending)
    #expect(await nextMock.runCliCommandCalls == [
      "set wifi.enabled 0",
      "get wifi.enabled",
      "get wifi.ssid",
      "get wifi.status",
      "get wifi.ip"
    ])
  }

  @Test
  func `a cancelled read keeps the previous snapshot`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    await harness.mock.failNextCLICommand(CancellationError())

    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.statusConnected)
    #expect(harness.viewModel.networkValue == "myssid")
    #expect(harness.viewModel.statusValue == L10n.Settings.RadioWiFi.Status.connected)
    #expect(harness.viewModel.ipAddress == "10.0.0.8")
  }

  @Test
  func `a ready read waits until the editor closes`() async throws {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    harness.viewModel.openEditor()
    let before = await harness.mock.runCliCommandCalls.count
    await harness.mock.setCLIReplyQueue(["> 1", "> cafe", "> connected", "> 10.0.0.5"])

    await harness.viewModel.noteSessionReadiness()

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.ipAddress == "10.0.0.8")

    await harness.viewModel.closeEditor()
    try await waitUntil("editor close reloads wifi") {
      harness.viewModel.networkName == "cafe"
    }

    #expect(harness.viewModel.ipAddress == "10.0.0.5")
    #expect(harness.viewModel.errorMessage == nil)
    #expect(await Array(harness.mock.runCliCommandCalls.dropFirst(before)) == [
      "get wifi.enabled",
      "get wifi.ssid",
      "get wifi.status",
      "get wifi.ip"
    ])
  }

  @Test
  func `a ready read waits until the pending save finishes`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "old", password: "old")
    await harness.mock.setCLIReplyQueue(["OK", "OK", "> 1", "> cafe", "> connected", "> 10.9.9.9"])
    await harness.mock.holdNextCLIReply()
    harness.viewModel.ssidField = "A"
    harness.viewModel.passwordField = "a"
    let task = Task { await harness.viewModel.requestSave() }
    try await waitUntil("save command held") {
      await harness.mock.heldCLICommand == "set wifi.ssid A"
    }
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.noteSessionReadiness()

    #expect(await harness.mock.runCliCommandCalls.count == before)
    await harness.mock.releaseHeldCLIReply()
    await task.value

    #expect(harness.viewModel.networkName == "cafe")
    #expect(harness.viewModel.ipAddress == "10.9.9.9")
    #expect(harness.viewModel.rebootPending)
    #expect(await Array(harness.mock.runCliCommandCalls.dropFirst(before)) == [
      "set wifi.pwd a",
      "get wifi.enabled",
      "get wifi.ssid",
      "get wifi.status",
      "get wifi.ip"
    ])
  }

  @Test
  func `a read held across a service swap applies the later service only`() async throws {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> old", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()

    let laterMock = MockConfigurationSession()
    let later = SettingsService(session: laterMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await laterMock.setCLIReply("OK")
    try await later.setRadioCLIValue("wifi.ssid", to: "later")
    await laterMock.setCLIReplyQueue(["> 1", "> later", "> connected", "> 10.2.2.2"])

    await harness.mock.holdNextCLIReply()
    let task = Task { await harness.viewModel.noteSessionReadiness() }
    try await waitUntil("first read held") {
      await harness.mock.heldCLICommand == "get wifi.enabled"
    }
    harness.settings = later
    await harness.viewModel.noteSessionReadiness()
    #expect(await laterMock.runCliCommandCalls == ["set wifi.ssid later"])

    await harness.mock.releaseHeldCLIReply()
    await task.value

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.networkName == "later")
    #expect(harness.viewModel.ipAddress == "10.2.2.2")
    #expect(harness.viewModel.rebootPending)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(await harness.mock.runCliCommandCalls == [
      "get wifi.enabled",
      "get wifi.ssid",
      "get wifi.status",
      "get wifi.ip",
      "get wifi.enabled"
    ])
    #expect(await laterMock.runCliCommandCalls == [
      "set wifi.ssid later",
      "get wifi.enabled",
      "get wifi.ssid",
      "get wifi.status",
      "get wifi.ip"
    ])
  }

  @Test
  func `commands send nothing while the session is not ready`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    harness.viewModel.ssidField = "other"
    harness.viewModel.passwordField = "pw"
    harness.connectionState = .syncing
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.requestSave()
    await harness.viewModel.setWiFiEnabled(false)
    harness.viewModel.requestClear()
    await harness.viewModel.confirmFromAlert(.clear).value
    harness.viewModel.requestReboot()
    await harness.viewModel.confirmFromAlert(.reboot).value

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.showingClearConfirm == false)
    #expect(harness.viewModel.showingRebootConfirm == false)
    #expect(await harness.mock.rebootCallCount == 0)
  }

  @Test
  func `a reboot that drops the link does not alert`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    let settings = try #require(harness.settings)
    try await settings.setRadioCLIValue("wifi.ssid", to: "myssid")
    let errors: [any Error] = [
      BLEError.notConnected,
      BLEError.operationTimeout,
      WiFiTransportError.notConnected,
      WiFiTransportError.sendTimeout
    ]

    for error in errors {
      await harness.mock.failReboot(error)
      harness.viewModel.requestReboot()
      await harness.viewModel.confirmFromAlert(.reboot).value
      #expect(harness.viewModel.errorMessage == nil)
    }

    #expect(await harness.mock.rebootCallCount == errors.count)
    #expect(harness.viewModel.networkName == "myssid")
  }

  @Test
  func `a reboot that fails before the radio resets still alerts`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    let settings = try #require(harness.settings)
    try await settings.setRadioCLIValue("wifi.ssid", to: "myssid")
    await harness.mock.failReboot(SettingsServiceError.notConnected)

    harness.viewModel.requestReboot()
    await harness.viewModel.confirmFromAlert(.reboot).value

    #expect(await harness.mock.rebootCallCount == 1)
    #expect(harness.viewModel.errorMessage == SettingsServiceError.notConnected.userFacingMessage)
    #expect(harness.viewModel.networkName == "myssid")
  }

  @Test(arguments: ProbeFailure.allCases)
  func `a failed wifi probe stays retryable`(_ failure: ProbeFailure) async {
    let harness = await Harness.make()
    await failure.prime(harness.mock)

    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection == false)
    #expect(harness.viewModel.wifiSettingsSupport == .unknown)
    #expect(harness.viewModel.errorMessage != nil)
    #expect(harness.viewModel.canRunCommands == false)
    #expect(await harness.mock.runCliCommandCalls == ["get wifi.enabled"])
  }

  @Test(arguments: [1, 2, 3])
  func `a later read failure retains completed nonsecret fields`(_ skip: Int) async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.mock.failCLICommand(after: skip, MeshCoreError.timeout)

    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.wifiSettingsSupport == .supported)
    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.statusConnected == (skip == 3))
    #expect(harness.viewModel.ipAddress == nil)
    #expect(harness.viewModel.errorMessage != nil)
    #expect(harness.viewModel.canClear == (skip > 1))
    let calls = await harness.mock.runCliCommandCalls
    #expect(calls.count == skip + 1)
    #expect(calls.first == "get wifi.enabled")
  }

  @Test
  func `support is visible while a later read is held`() async throws {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> cafe", "> connected", "> 10.0.0.5"])
    await harness.mock.holdCLIReply(after: 1)
    let task = Task { await harness.viewModel.noteSessionReadiness() }
    try await waitUntil("ssid read held") {
      await harness.mock.heldCLICommand == "get wifi.ssid"
    }

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.wifiSettingsSupport == .supported)
    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.statusConnected == false)
    #expect(harness.viewModel.ipAddress == nil)
    #expect(harness.viewModel.canRunCommands == false)

    await harness.mock.releaseHeldCLIReply()
    await task.value

    #expect(harness.viewModel.networkName == "cafe")
    #expect(harness.viewModel.statusConnected)
    #expect(harness.viewModel.ipAddress == "10.0.0.5")
    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.canClear)
  }

  @Test
  func `refresh succeeds after a failed probe`() async {
    let harness = await Harness.make()
    await harness.mock.failNextCLICommand(MeshCoreError.timeout)
    await harness.viewModel.noteSessionReadiness()
    #expect(harness.viewModel.recoverySurface(wifiPageVisible: false) == .connection)
    #expect(harness.viewModel.showsSection == false)

    await harness.mock.setCLIReplyQueue(["> 0", "> (not set)", "> (not connected)", "> (not connected)"])
    await harness.viewModel.refresh()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.wifiEnabled == false)
    #expect(harness.viewModel.networkName == nil)
    #expect(harness.viewModel.ipAddress == nil)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.recoverySurface(wifiPageVisible: false) == nil)
  }

  @Test
  func `a failed refresh clears association and retry restores it`() async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    let eligible = Self.wifiSwitchEnabled(on: harness.viewModel, dialHost: "10.0.0.8")
    #expect(eligible)

    await harness.mock.failNextCLICommand(MeshCoreError.timeout)
    await harness.viewModel.refresh()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.statusConnected == false)
    #expect(harness.viewModel.ipAddress == nil)
    #expect(harness.viewModel.canClear)
    #expect(Self.wifiSwitchEnabled(on: harness.viewModel, dialHost: nil) == false)
    #expect(harness.viewModel.recoverySurface(wifiPageVisible: true) == .wifiPage)

    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.9.9.9"])
    await harness.viewModel.refresh()

    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.statusConnected)
    #expect(harness.viewModel.ipAddress == "10.9.9.9")
    #expect(Self.wifiSwitchEnabled(on: harness.viewModel, dialHost: "10.9.9.9"))
    #expect(harness.viewModel.recoverySurface(wifiPageVisible: true) == nil)
  }

  @Test
  func `refresh waits until the editor closes`() async throws {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> original", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    harness.viewModel.openEditor()
    await harness.mock.setCLIReplyQueue(["> original"])
    await harness.mock.failCLICommand(after: 1, MeshCoreError.timeout)
    await harness.viewModel.loadEditor()
    #expect(harness.viewModel.errorMessage != nil)
    await harness.mock.setCLIReplyQueue(["> 1", "> cafe", "> connected", "> 10.2.2.2"])
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.refresh()

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(harness.viewModel.showsSection)
    #expect(
      RadioWiFiViewModel.recoverySurface(
        errorMessage: harness.viewModel.errorMessage,
        editorOpen: true,
        wifiPageVisible: true
      ) == .editor
    )

    await harness.viewModel.closeEditor()
    try await waitUntil("editor close recovers wifi") {
      harness.viewModel.ipAddress == "10.2.2.2"
    }

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.networkName == "cafe")
  }

  @Test
  func `firmware without radio cli skips the wifi probe`() async {
    let harness = await Harness.make()
    harness.device.firmwareVersion = 13

    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection == false)
    #expect(harness.viewModel.wifiSettingsSupport == .unknown)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(await harness.mock.runCliCommandCalls.isEmpty)
  }

  @Test
  func `replacing the radio drops its snapshot when the new probe fails`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    harness.viewModel.ssidField = "other"
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.rebootPending)
    #expect(harness.viewModel.canClear)

    let nextMock = MockConfigurationSession()
    await nextMock.failNextCLICommand(MeshCoreError.timeout)
    harness.device = Harness.radio()
    harness.settings = SettingsService(session: nextMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection == false)
    #expect(harness.viewModel.wifiSettingsSupport == .unknown)
    #expect(harness.viewModel.networkName == nil)
    #expect(harness.viewModel.rebootPending == false)
    #expect(harness.viewModel.canClear == false)
    #expect(harness.viewModel.canSave == false)
    #expect(harness.viewModel.errorMessage != nil)
  }

  @Test
  func `an unknown command on the replacement radio clears the previous snapshot`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "")
    harness.viewModel.ssidField = "other"
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.rebootPending)

    let nextMock = MockConfigurationSession()
    await nextMock.setCLIReply("Unknown command")
    harness.device = Harness.radio()
    harness.settings = SettingsService(session: nextMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.wifiSettingsSupport == .unavailable)
    #expect(harness.viewModel.showsSection == false)
    #expect(harness.viewModel.networkName == nil)
    #expect(harness.viewModel.rebootPending == false)
    #expect(harness.viewModel.canClear == false)
    #expect(harness.viewModel.errorMessage == nil)
  }

  @Test
  func `a same-radio service replacement cannot act until it probes`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    harness.viewModel.ssidField = "other"
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.rebootPending)

    let nextMock = MockConfigurationSession()
    let next = SettingsService(session: nextMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await nextMock.setCLIReplyQueue(["> 1", "> cafe", "> connected", "> 10.4.4.4"])
    await nextMock.holdNextCLIReply()
    harness.settings = next
    let task = Task { await harness.viewModel.noteSessionReadiness() }
    try await waitUntil("replacement probe held") {
      await nextMock.heldCLICommand == "get wifi.enabled"
    }

    #expect(harness.viewModel.showsSection == false)
    #expect(harness.viewModel.rebootPending == false)
    #expect(harness.viewModel.canClear == false)
    #expect(harness.viewModel.canSave == false)
    await harness.viewModel.requestSave()
    await harness.viewModel.setWiFiEnabled(false)
    harness.viewModel.requestClear()
    harness.viewModel.requestReboot()
    #expect(await nextMock.runCliCommandCalls == ["get wifi.enabled"])

    await nextMock.releaseHeldCLIReply()
    await task.value

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.networkName == "cafe")
    #expect(harness.viewModel.ipAddress == "10.4.4.4")
    #expect(harness.viewModel.canClear)
    #expect(harness.viewModel.showingClearConfirm == false)
    #expect(harness.viewModel.showingRebootConfirm == false)
  }

  @Test
  func `a same-radio service whose probe fails does not keep the old actions`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "")
    harness.viewModel.ssidField = "other"
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.rebootPending)

    let nextMock = MockConfigurationSession()
    await nextMock.failNextCLICommand(MeshCoreError.timeout)
    harness.settings = SettingsService(session: nextMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection == false)
    #expect(harness.viewModel.wifiSettingsSupport == .unknown)
    #expect(harness.viewModel.networkName == nil)
    #expect(harness.viewModel.rebootPending == false)
    #expect(harness.viewModel.canClear == false)
    #expect(harness.viewModel.canSave == false)
    #expect(harness.viewModel.errorMessage != nil)
  }

  @Test
  func `a load that finishes after a radio change applies the new radio only`() async throws {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> old", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    await harness.mock.setCLIReplyQueue(["> old", "> original-key"])
    await harness.viewModel.loadEditor()
    harness.viewModel.ssidField = "older"
    await harness.mock.setCLIReply("OK")
    await harness.viewModel.requestSave()
    #expect(harness.viewModel.rebootPending)

    await harness.mock.setCLIReplyQueue(["> 1", "> stale", "> connected", "> 9.9.9.9"])
    await harness.mock.holdNextCLIReply()
    let task = Task { await harness.viewModel.noteSessionReadiness() }
    try await waitUntil("old radio read held") {
      await harness.mock.heldCLICommand == "get wifi.enabled"
    }

    let nextMock = MockConfigurationSession()
    await nextMock.setCLIReplyQueue(["> 0", "> fresh", "> (not connected)", "> (not connected)"])
    harness.device = Harness.radio()
    harness.settings = SettingsService(session: nextMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await harness.viewModel.noteSessionReadiness()
    await harness.mock.releaseHeldCLIReply()
    await task.value

    #expect(harness.viewModel.networkName == "fresh")
    #expect(harness.viewModel.wifiEnabled == false)
    #expect(harness.viewModel.rebootPending == false)
    #expect(harness.viewModel.ipAddress == nil)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(await nextMock.runCliCommandCalls == [
      "get wifi.enabled",
      "get wifi.ssid",
      "get wifi.status",
      "get wifi.ip"
    ])
  }

  @Test
  func `a save that finishes after a radio change does not enable the new radio`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    await harness.mock.holdNextCLIReply()
    harness.viewModel.ssidField = "later"
    harness.viewModel.passwordField = "next"
    let task = Task { await harness.viewModel.requestSave() }
    try await waitUntil("save held across radio change") {
      await harness.mock.heldCLICommand == "set wifi.ssid later"
    }

    let nextMock = MockConfigurationSession()
    await nextMock.failNextCLICommand(MeshCoreError.timeout)
    harness.device = Harness.radio()
    harness.settings = SettingsService(session: nextMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await harness.viewModel.noteSessionReadiness()
    await harness.mock.setCLIReply("OK")
    await harness.mock.releaseHeldCLIReply()
    await task.value

    #expect(harness.viewModel.networkName == nil)
    #expect(harness.viewModel.rebootPending == false)
    #expect(harness.viewModel.canClear == false)
    #expect(harness.viewModel.canSave == false)
    #expect(harness.viewModel.wifiSettingsSupport == .unknown)
    let calls = await harness.mock.runCliCommandCalls
    #expect(calls.contains("set wifi.ssid later"))
    #expect(calls.contains("set wifi.pwd next") == false)
  }

  @Test
  func `an error that finishes after a radio change stays off the new radio`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "")
    await harness.mock.holdNextCLIReply()
    harness.viewModel.ssidField = "later"
    let task = Task { await harness.viewModel.requestSave() }
    try await waitUntil("failing save held") {
      await harness.mock.heldCLICommand == "set wifi.ssid later"
    }

    let nextMock = MockConfigurationSession()
    await nextMock.setCLIReply("Unknown command")
    harness.device = Harness.radio()
    harness.settings = SettingsService(session: nextMock, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID))
    await harness.viewModel.noteSessionReadiness()
    #expect(harness.viewModel.errorMessage == nil)

    await harness.mock.setCLIReply("Error: rejected")
    await harness.mock.releaseHeldCLIReply()
    await task.value

    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.wifiSettingsSupport == .unavailable)
    #expect(harness.viewModel.rebootPending == false)
    #expect(harness.viewModel.canClear == false)
  }

  @Test(arguments: AlertConfirmationCase.allCases)
  private func `alert button consumption survives framework binding dismissal`(
    _ action: AlertConfirmationCase
  ) async throws {
    let harness = await Harness.make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await harness.viewModel.noteSessionReadiness()
    try await action.prepare(harness)
    let before = await harness.mock.runCliCommandCalls.count
    let binding = action.presentationBinding(harness.viewModel)
    #expect(binding.wrappedValue)

    let accepted = harness.viewModel.confirmFromAlert(action.entry)
    binding.wrappedValue = false
    await accepted.value

    let writes = await harness.mock.runCliCommandCalls.dropFirst(before).filter { $0.hasPrefix("set ") }
    #expect(writes == action.expectedWrites)
    #expect(await harness.mock.rebootCallCount == (action == .reboot ? 1 : 0))
    #expect(harness.viewModel.confirmation == nil)
    #expect(!harness.viewModel.isBusy)
    await harness.viewModel.closeEditor()
    await harness.settings?.retireRadioWiFiOperations()
  }

  @Test(arguments: AlertConfirmationCase.allCases)
  private func `duplicate alert confirm submits once`(_ action: AlertConfirmationCase) async throws {
    let harness = await Harness.make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await harness.viewModel.noteSessionReadiness()
    try await action.prepare(harness)
    let before = await harness.mock.runCliCommandCalls.count
    let accepted = harness.viewModel.confirmFromAlert(action.entry)
    let duplicate = harness.viewModel.confirmFromAlert(action.entry)
    action.presentationBinding(harness.viewModel).wrappedValue = false
    await accepted.value
    await duplicate.value

    let writes = await harness.mock.runCliCommandCalls.dropFirst(before).filter { $0.hasPrefix("set ") }
    #expect(writes == action.expectedWrites)
    #expect(await harness.mock.rebootCallCount == (action == .reboot ? 1 : 0))
    await harness.viewModel.closeEditor()
    await harness.settings?.retireRadioWiFiOperations()
  }

  @Test(arguments: AlertConfirmationCase.allCases, [false, true])
  private func `cancelled and obsolete alert contexts submit nothing`(
    _ action: AlertConfirmationCase, obsolete: Bool
  ) async throws {
    let harness = await Harness.make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await harness.viewModel.noteSessionReadiness()
    try await action.prepare(harness)
    let before = await harness.mock.runCliCommandCalls.count
    if obsolete {
      let accepted = harness.viewModel.confirmFromAlert(action.entry)
      action.presentationBinding(harness.viewModel).wrappedValue = false
      harness.connectionState = .disconnected
      await accepted.value
    } else {
      action.presentationBinding(harness.viewModel).wrappedValue = false
      await harness.viewModel.confirmFromAlert(action.entry).value
    }

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(await harness.mock.rebootCallCount == 0)
    #expect(harness.viewModel.confirmation == nil)
    await harness.viewModel.closeEditor()
    await harness.settings?.retireRadioWiFiOperations()
  }

  @Test(arguments: AlertConfirmationCase.allCases)
  private func `accepted alert cannot write a newer WiFi revision`(_ action: AlertConfirmationCase) async throws {
    let harness = await Harness.make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await harness.viewModel.noteSessionReadiness()
    try await action.prepare(harness)
    let settings = try #require(harness.settings)
    let before = await harness.mock.runCliCommandCalls.count
    let accepted = harness.viewModel.confirmFromAlert(action.entry)
    action.presentationBinding(harness.viewModel).wrappedValue = false
    let token = try #require(settings.radioWiFiState.beginMutation(expectedRevision: nil))
    settings.radioWiFiState.settle(token, outcome: .accepted)
    await accepted.value

    let writes = await harness.mock.runCliCommandCalls.dropFirst(before).filter { $0.hasPrefix("set ") }
    #expect(writes.isEmpty)
    #expect(await harness.mock.rebootCallCount == 0)
    await harness.viewModel.closeEditor()
    await settings.retireRadioWiFiOperations()
  }

  @Test
  func `accepted Save cannot write after its editor closes`() async throws {
    let harness = await Harness.make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await harness.viewModel.noteSessionReadiness()
    try await AlertConfirmationCase.save.prepare(harness)
    let before = await harness.mock.runCliCommandCalls.count
    let accepted = harness.viewModel.confirmFromAlert(.wifiWarning)
    harness.viewModel.showingWiFiWarning = false
    harness.viewModel.editor = nil
    await accepted.value

    #expect(await harness.mock.runCliCommandCalls.count == before)
    await harness.viewModel.closeEditor()
    await harness.settings?.retireRadioWiFiOperations()
  }

  @Test
  func `reboot confirmation cannot open during a pending Tools WiFi mutation`() async throws {
    let harness = try await Harness.makeRebootBoundary()
    let tools = harness.makeTools()
    await harness.mock.holdNextCLIReply()
    tools.executeCommand("01|set wifi.ssid newer")
    do {
      try await waitUntil("Tools WiFi setter must remain pending") {
        await harness.mock.heldCLICommand == "01|set wifi.ssid newer"
      }
      #expect(harness.settings?.radioWiFiState.stableRevision() == nil)
      harness.viewModel.requestReboot()
      #expect(!harness.viewModel.showingRebootConfirm)
      await harness.mock.releaseHeldCLIReply()
      try await waitUntil("Tools setter must settle") { !tools.isWaitingForResponse }
      let accepted = harness.viewModel.confirmFromAlert(.reboot)
      AlertConfirmationCase.reboot.presentationBinding(harness.viewModel).wrappedValue = false
      await accepted.value
      #expect(await harness.mock.rebootCallCount == 0)
      await harness.confirmCurrentRebootOnce()
    } catch {
      await harness.mock.releaseHeldCLIReply()
      tools.cleanup()
      await harness.settings?.retireRadioWiFiOperations()
      throw error
    }
    tools.cleanup()
    await harness.settings?.retireRadioWiFiOperations()
  }

  @Test
  func `settled Tools WiFi mutation invalidates an already open reboot confirmation`() async throws {
    let harness = try await Harness.makeRebootBoundary()
    let tools = harness.makeTools()
    harness.viewModel.requestReboot()
    #expect(harness.viewModel.showingRebootConfirm)
    tools.executeCommand("01|set wifi.ssid newer")
    try await waitUntil("Tools setter must settle") { !tools.isWaitingForResponse }
    let accepted = harness.viewModel.confirmFromAlert(.reboot)
    AlertConfirmationCase.reboot.presentationBinding(harness.viewModel).wrappedValue = false
    await accepted.value

    #expect(await harness.mock.rebootCallCount == 0)
    await harness.confirmCurrentRebootOnce()
    tools.cleanup()
    await harness.settings?.retireRadioWiFiOperations()
  }

  @Test(arguments: [false, true])
  func `reboot service admission rejects pending and settled Tools interference`(pending: Bool) async throws {
    let harness = try await Harness.makeRebootBoundary()
    let settings = try #require(harness.settings)
    let revision = try #require(settings.radioWiFiState.stableRevision())
    let tools = harness.makeTools()
    await harness.mock.holdNextCLIReply()
    tools.executeCommand("01|set wifi.ssid newer")
    do {
      try await waitUntil("Tools setter must reach firmware") {
        await harness.mock.heldCLICommand == "01|set wifi.ssid newer"
      }
      if !pending {
        await harness.mock.releaseHeldCLIReply()
        try await waitUntil("Tools setter must settle before reboot admission") { !tools.isWaitingForResponse }
      }
      await #expect(throws: CancellationError.self) {
        try await settings.reboot(expectedContextID: settings.radioWiFiContextID, expectedRevision: revision)
      }
      #expect(await harness.mock.rebootCallCount == 0)
      if pending { await harness.mock.releaseHeldCLIReply() }
      try await waitUntil("Tools setter must complete") { !tools.isWaitingForResponse }
      #expect(harness.viewModel.errorMessage == nil)
      await harness.confirmCurrentRebootOnce()
    } catch {
      await harness.mock.releaseHeldCLIReply()
      tools.cleanup()
      await settings.retireRadioWiFiOperations()
      throw error
    }
    tools.cleanup()
    await settings.retireRadioWiFiOperations()
  }

  private static func wifiSwitchEnabled(on viewModel: RadioWiFiViewModel, dialHost: String?) -> Bool {
    let dial = dialHost.map { ConnectionLinkWiFiDial(host: $0, port: ConnectionMethod.defaultWiFiPort) }
    let snapshot = ConnectionLinkRows.make(
      liveTransport: .bluetooth,
      savedPeripheralID: UUID(),
      wifiSupport: viewModel.wifiSettingsSupport,
      wifiAssociated: viewModel.statusConnected,
      wifiDial: dial,
      activeSwitch: nil
    )
    return snapshot.rows.first { $0.link == .wifi }?.canSwitch == true
  }
}

private enum AlertConfirmationCase: CaseIterable, Sendable {
  case clear, reboot, save, disable

  var entry: RadioWiFiViewModel.AlertAction {
    switch self {
    case .clear: .clear
    case .reboot: .reboot
    case .save, .disable: .wifiWarning
    }
  }

  var expectedWrites: [String] {
    switch self {
    case .clear: ["set wifi.clear"]
    case .reboot: []
    case .save: ["set wifi.ssid replacement"]
    case .disable: ["set wifi.enabled 0"]
    }
  }

  @MainActor
  func prepare(_ harness: Harness) async throws {
    switch self {
    case .clear:
      harness.viewModel.requestClear()
    case .reboot:
      let settings = try #require(harness.settings)
      try await settings.setRadioCLIValue("wifi.ssid", to: "built-in")
      harness.viewModel.requestReboot()
    case .save:
      harness.transport.value = .wifi
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "replacement"
      await harness.viewModel.requestSave()
    case .disable:
      harness.transport.value = .wifi
      await harness.viewModel.setWiFiEnabled(false)
    }
  }

  @MainActor
  func presentationBinding(_ viewModel: RadioWiFiViewModel) -> Binding<Bool> {
    @Bindable var viewModel = viewModel
    switch self {
    case .clear: return $viewModel.showingClearConfirm
    case .reboot: return $viewModel.showingRebootConfirm
    case .save, .disable: return $viewModel.showingWiFiWarning
    }
  }
}

private enum CredentialReadFailure: CaseIterable, Sendable {
  case unsupported
  case malformed
  case rejected
  case cancelled

  func prime(_ mock: MockConfigurationSession) async {
    let reply = switch self {
    case .unsupported: "Unknown command"
    case .malformed: "OK"
    case .rejected: "Error: rejected"
    case .cancelled: "> ignored"
    }
    await mock.setCLIReplyQueue(["> stored", reply])
    if case .cancelled = self {
      await mock.failCLICommand(after: 1, CancellationError())
    }
  }
}

enum ProbeFailure: CaseIterable, Sendable, CustomTestStringConvertible {
  case empty
  case blankValue
  case malformed
  case rejected
  case timedOut

  static var allCases: [ProbeFailure] {
    [.empty, .blankValue, .malformed, .rejected, .timedOut]
  }

  var testDescription: String {
    switch self {
    case .empty: "empty"
    case .blankValue: "blank value"
    case .malformed: "malformed"
    case .rejected: "rejected"
    case .timedOut: "timed out"
    }
  }

  func prime(_ mock: MockConfigurationSession) async {
    switch self {
    case .empty:
      await mock.setCLIReply("")
    case .blankValue:
      await mock.setCLIReply("> ")
    case .malformed:
      await mock.setCLIReply("> yes")
    case .rejected:
      await mock.setCLIReply("Error: rejected")
    case .timedOut:
      await mock.failNextCLICommand(MeshCoreError.timeout)
    }
  }
}

@MainActor
private final class Harness {
  let viewModel = RadioWiFiViewModel()
  let mock: MockConfigurationSession
  var settings: SettingsService?
  var device: DeviceDTO
  var connectionState: DeviceConnectionState = .ready
  let transport = TransportBox()

  static func radio() -> DeviceDTO {
    DeviceDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "Radio",
      firmwareVersion: 14,
      firmwareVersionString: "1.18.0",
      manufacturerName: "Test",
      buildDate: "",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 869_525,
      bandwidth: 250_000,
      spreadingFactor: 11,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 22,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 0,
      telemetryModeBase: 0,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  static func make() async -> Harness {
    let mock = MockConfigurationSession()
    let device = radio()
    let settings = SettingsService(session: mock, radioWiFiState: RadioWiFiState(radioID: device.radioID))
    let harness = Harness(mock: mock, settings: settings, device: device)
    harness.viewModel.configure(
      settingsService: { harness.settings },
      connectionState: { harness.connectionState },
      connectedDevice: { harness.device },
      currentTransportType: { harness.transport.value }
    )
    return harness
  }

  init(mock: MockConfigurationSession, settings: SettingsService, device: DeviceDTO) {
    self.mock = mock
    self.settings = settings
    self.device = device
  }

  static func makeRebootBoundary() async throws -> Harness {
    let harness = await make()
    await harness.mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let settings = try #require(harness.settings)
    try await settings.setRadioCLIValue("wifi.ssid", to: "initial")
    await harness.viewModel.noteSessionReadiness()
    return harness
  }

  func makeTools() -> CLIToolViewModel {
    let tools = CLIToolViewModel()
    tools.configure(
      dependencies: CLIToolViewModel.Dependencies(
        repeaterAdminService: { nil }, remoteNodeService: { nil }, settingsService: { self.settings },
        dataStore: { nil }, radioID: { self.device.radioID }, connectedDevice: { self.device },
        powerOffRadio: {}, powerOffCancelAvailable: { true }
      ),
      localDeviceName: "Radio", sendSelfAdvert: { _ in }
    )
    tools.activeSession = .local(deviceName: "Radio")
    return tools
  }

  func confirmCurrentRebootOnce() async {
    viewModel.requestReboot()
    #expect(viewModel.showingRebootConfirm)
    let accepted = viewModel.confirmFromAlert(.reboot)
    let duplicate = viewModel.confirmFromAlert(.reboot)
    AlertConfirmationCase.reboot.presentationBinding(viewModel).wrappedValue = false
    await accepted.value
    await duplicate.value
    #expect(await mock.rebootCallCount == 1)
    #expect(settings?.radioWiFiState.expectedRebootID != nil)
  }

  func loadNetwork(ssid: String, password: String) async {
    await mock.setCLIReplyQueue(["> 1", "> \(ssid)", "> connected", "> (not connected)"])
    await viewModel.noteSessionReadiness()
    await mock.setCLIReplyQueue(["> \(ssid)", password.isEmpty ? "> " : "> \(password)"])
    await viewModel.loadEditor()
    await mock.setCLIReply("OK")
  }
}

@MainActor
private final class TransportBox {
  var value: TransportType? = .bluetooth
}
