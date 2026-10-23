import Foundation
@testable import MC1
@testable import MC1Services
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

    await harness.viewModel.continuePastWiFiWarning()
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
    #expect(harness.rebootCount == 0)
    #expect(harness.viewModel.showingRebootConfirm == false)

    harness.viewModel.requestReboot()
    await harness.viewModel.confirmReboot()
    #expect(harness.rebootCount == 1)
  }

  @Test
  func `a load that finishes after services change applies nothing`() async {
    let mock = MockConfigurationSession()
    await mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    let first = SettingsService(session: mock)
    let replacement = SettingsService(session: mock)
    let viewModel = RadioWiFiViewModel()
    let device = Harness.radio()
    var current: SettingsService? = first
    viewModel.configure(
      settingsService: {
        let settings = current
        current = replacement
        return settings
      },
      connectionState: { .ready },
      connectedDevice: { device },
      currentTransportType: { .bluetooth },
      reboot: {}
    )

    await viewModel.load()

    #expect(viewModel.showsSection == false)
    #expect(viewModel.networkName == nil)
    #expect(await mock.runCliCommandCalls == ["get wifi.enabled"])
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
    await harness.mock.setCLIReply(">  pw ")
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
    await harness.viewModel.continuePastWiFiWarning()

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
    await harness.viewModel.confirmClear()
    await harness.mock.releaseHeldCLIReply()
    await task.value

    let calls = await harness.mock.runCliCommandCalls
    #expect(Array(calls.dropFirst(before)) == ["set wifi.ssid A", "set wifi.pwd a"])
  }

  @Test
  func `the editor is busy until the password read returns`() async throws {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "old", password: "old")
    await harness.mock.holdNextCLIReply()
    await harness.mock.setCLIReply("> pw")
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
    harness.viewModel.editorOpen = true
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
    await harness.viewModel.continuePastWiFiWarning()

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
    let next = SettingsService(session: nextMock)
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
    #expect(harness.viewModel.ipAddress == "10.0.0.8")
  }

  @Test
  func `a ready read waits until the editor closes`() async throws {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.viewModel.noteSessionReadiness()
    harness.viewModel.editorOpen = true
    let before = await harness.mock.runCliCommandCalls.count
    await harness.mock.setCLIReplyQueue(["> 1", "> cafe", "> connected", "> 10.0.0.5"])

    await harness.viewModel.noteSessionReadiness()

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.ipAddress == "10.0.0.8")

    harness.viewModel.editorOpen = false
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
    let later = SettingsService(session: laterMock)
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
    await harness.viewModel.confirmClear()
    harness.viewModel.requestReboot()
    await harness.viewModel.confirmReboot()

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(harness.viewModel.errorMessage == nil)
    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.networkName == "myssid")
    #expect(harness.viewModel.showingClearConfirm == false)
    #expect(harness.viewModel.showingRebootConfirm == false)
    #expect(harness.rebootCount == 0)
  }

  @Test
  func `a reboot that drops the link does not alert`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    let errors: [any Error] = [
      BLEError.notConnected,
      BLEError.operationTimeout,
      WiFiTransportError.notConnected,
      WiFiTransportError.sendTimeout
    ]

    for error in errors {
      harness.rebootError = error
      harness.viewModel.requestReboot()
      await harness.viewModel.confirmReboot()
      #expect(harness.viewModel.errorMessage == nil)
    }

    #expect(harness.rebootCount == errors.count)
    #expect(harness.viewModel.networkName == "myssid")
  }

  @Test
  func `a reboot that fails before the radio resets still alerts`() async {
    let harness = await Harness.make()
    await harness.loadNetwork(ssid: "myssid", password: "secret")
    harness.rebootError = SettingsServiceError.notConnected

    harness.viewModel.requestReboot()
    await harness.viewModel.confirmReboot()

    #expect(harness.rebootCount == 1)
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
  func `a later read failure keeps support and clears association`(_ skip: Int) async {
    let harness = await Harness.make()
    await harness.mock.setCLIReplyQueue(["> 1", "> myssid", "> connected", "> 10.0.0.8"])
    await harness.mock.failCLICommand(after: skip, MeshCoreError.timeout)

    await harness.viewModel.noteSessionReadiness()

    #expect(harness.viewModel.showsSection)
    #expect(harness.viewModel.wifiSettingsSupport == .supported)
    #expect(harness.viewModel.wifiEnabled)
    #expect(harness.viewModel.statusConnected == false)
    #expect(harness.viewModel.ipAddress == nil)
    #expect(harness.viewModel.errorMessage != nil)
    #expect(harness.viewModel.canClear == false)
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
    await harness.mock.failNextCLICommand(MeshCoreError.timeout)
    await harness.viewModel.noteSessionReadiness()
    #expect(harness.viewModel.errorMessage != nil)
    harness.viewModel.editorOpen = true
    await harness.mock.setCLIReplyQueue(["> 1", "> cafe", "> connected", "> 10.2.2.2"])
    let before = await harness.mock.runCliCommandCalls.count

    await harness.viewModel.refresh()

    #expect(await harness.mock.runCliCommandCalls.count == before)
    #expect(harness.viewModel.showsSection == false)
    #expect(
      RadioWiFiViewModel.recoverySurface(
        errorMessage: harness.viewModel.errorMessage,
        editorOpen: true,
        wifiPageVisible: true
      ) == .editor
    )

    harness.viewModel.editorOpen = false
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
    harness.settings = SettingsService(session: nextMock)
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
    harness.settings = SettingsService(session: nextMock)
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
    let next = SettingsService(session: nextMock)
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
    harness.settings = SettingsService(session: nextMock)
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
    harness.settings = SettingsService(session: nextMock)
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
    harness.settings = SettingsService(session: nextMock)
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
    harness.settings = SettingsService(session: nextMock)
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
  var rebootCount = 0
  var rebootError: (any Error)?

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
    let harness = Harness(mock: mock, settings: SettingsService(session: mock), device: radio())
    harness.viewModel.configure(
      settingsService: { harness.settings },
      connectionState: { harness.connectionState },
      connectedDevice: { harness.device },
      currentTransportType: { harness.transport.value },
      reboot: {
        harness.rebootCount += 1
        if let rebootError = harness.rebootError {
          throw rebootError
        }
      }
    )
    return harness
  }

  init(mock: MockConfigurationSession, settings: SettingsService, device: DeviceDTO) {
    self.mock = mock
    self.settings = settings
    self.device = device
  }

  func loadNetwork(ssid: String, password: String) async {
    await mock.setCLIReplyQueue(["> 1", "> \(ssid)", "> connected", "> (not connected)"])
    await viewModel.noteSessionReadiness()
    await mock.setCLIReply(password.isEmpty ? "> " : "> \(password)")
    await viewModel.loadEditor()
    await mock.setCLIReply("OK")
  }
}

@MainActor
private final class TransportBox {
  var value: TransportType? = .bluetooth
}
