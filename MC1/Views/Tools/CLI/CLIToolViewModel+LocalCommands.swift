import Foundation
import MC1Services

// MARK: - Local Radio Command Execution

extension CLIToolViewModel {
  /// Executes a parsed local-session radio command against the connected radio,
  /// appending firmware-parity output. Every `set` routes through a `*Verified`
  /// setter so `SettingsEvent`s flow to the Settings UI and `Device` persistence.
  func executeLocal(_ command: CLILocalCommand) async {
    switch command {
    case .clock: await runClock(sync: false)
    case .clockSync: await runClock(sync: true)
    case .ver: await runVer()
    case .board: await runBoard()
    case let .advert(flood): await runAdvert(flood: flood)
    case .reboot: await runReboot()
    case let .getKey(key): await runGet(key)
    case let .setName(name): await runSetName(name)
    case let .setLatitude(latitude): await runSetLatitude(latitude)
    case let .setLongitude(longitude): await runSetLongitude(longitude)
    case let .setTxPower(power): await runSetTxPower(power)
    case let .setRadio(frequencyMHz, bandwidthKHz, spreadingFactor, codingRate):
      await runSetRadio(frequencyMHz: frequencyMHz, bandwidthKHz: bandwidthKHz, spreadingFactor: spreadingFactor, codingRate: codingRate)
    case let .setFrequency(frequencyMHz): await runSetFrequency(frequencyMHz)
    case let .setMultiAcks(value): await runSetMultiAcks(value)
    case let .setPathHashMode(mode): await runSetPathHashMode(mode)
    case .getCustomVars: await runGetCustomVars(key: nil)
    case let .getCustomVar(key): await runGetCustomVars(key: key)
    case let .setCustomVar(key, value): await runSetCustomVar(key: key, value: value)
    }
  }

  /// Renders a parser error as a usage or error output line.
  func renderParseError(_ error: CLILocalParseError) {
    switch error {
    case let .badArguments(usage):
      appendOutput(usage.usageLine, type: .error)
    case .valueOutOfRange:
      appendOutput(L10n.Tools.Tools.Cli.invalidValue, type: .error)
    case .invalidCustomVarToken:
      appendOutput(L10n.Tools.Tools.Cli.invalidCustomVarToken, type: .error)
    }
  }

  // MARK: - Reads

  private func runClock(sync: Bool) async {
    guard let settingsService else { return notConnected() }
    do {
      if sync {
        let now = Date()
        try await settingsService.setTime(now)
        output(LocalCommandOutput.clockSet(now))
      } else {
        let time = try await settingsService.getTime()
        output(LocalCommandOutput.clock(time))
      }
    } catch { renderError(error) }
  }

  private func runVer() async {
    guard let settingsService else { return notConnected() }
    do {
      let capabilities = try await settingsService.queryDevice()
      output("\(capabilities.version) (Build: \(capabilities.firmwareBuild))")
    } catch { renderError(error) }
  }

  private func runBoard() async {
    guard let settingsService else { return notConnected() }
    do {
      let capabilities = try await settingsService.queryDevice()
      output(capabilities.model)
    } catch { renderError(error) }
  }

  private func runGet(_ key: CLILocalKey) async {
    guard let settingsService else { return notConnected() }
    do {
      switch key {
      case .name:
        try await output(LocalCommandOutput.value(settingsService.getSelfInfo().name))
      case .lat:
        try await output(LocalCommandOutput.value(LocalCommandOutput.coordinate(settingsService.getSelfInfo().latitude)))
      case .lon:
        try await output(LocalCommandOutput.value(LocalCommandOutput.coordinate(settingsService.getSelfInfo().longitude)))
      case .tx:
        try await output(LocalCommandOutput.value("\(settingsService.getSelfInfo().txPower)"))
      case .radio:
        try await output(LocalCommandOutput.value(LocalCommandOutput.radio(settingsService.getSelfInfo())))
      case .freq:
        try await output(LocalCommandOutput.value(LocalCommandOutput.decimal(settingsService.getSelfInfo().radioFrequency)))
      case .publicKey:
        try await output(LocalCommandOutput.value(LocalCommandOutput.hex(settingsService.getSelfInfo().publicKey)))
      case .multiAcks:
        try await output(LocalCommandOutput.value("\(settingsService.getSelfInfo().multiAcks)"))
      case .pathHashMode:
        try await output(LocalCommandOutput.value("\(settingsService.queryDevice().pathHashMode)"))
      case .bat:
        try await output(LocalCommandOutput.value("\(settingsService.getBattery().level) mV"))
      }
    } catch { renderError(error) }
  }

  // MARK: - Writes

  private func runSetName(_ name: String) async {
    guard let settingsService else { return notConnected() }
    do {
      let selfInfo = try await settingsService.setNodeNameVerified(name)
      localDeviceName = selfInfo.name
      if activeSession?.isLocal == true {
        activeSession = .local(deviceName: selfInfo.name)
      }
      output(LocalCommandOutput.ok)
    } catch { renderError(error) }
  }

  private func runSetLatitude(_ latitude: Double) async {
    guard let settingsService else { return notConnected() }
    do {
      let current = try await settingsService.getSelfInfo()
      _ = try await settingsService.setManualLocationVerified(latitude: latitude, longitude: current.longitude)
      output(LocalCommandOutput.ok)
    } catch { renderError(error) }
  }

  private func runSetLongitude(_ longitude: Double) async {
    guard let settingsService else { return notConnected() }
    do {
      let current = try await settingsService.getSelfInfo()
      _ = try await settingsService.setManualLocationVerified(latitude: current.latitude, longitude: longitude)
      output(LocalCommandOutput.ok)
    } catch { renderError(error) }
  }

  private func runSetTxPower(_ power: Int8) async {
    guard let settingsService else { return notConnected() }
    do {
      _ = try await settingsService.setTxPowerVerified(power)
      output(LocalCommandOutput.ok)
    } catch { renderError(error) }
  }

  private func runSetRadio(frequencyMHz: Double, bandwidthKHz: Double, spreadingFactor: UInt8, codingRate: UInt8) async {
    guard let settingsService else { return notConnected() }
    do {
      _ = try await settingsService.setRadioParamsVerified(
        frequencyKHz: LocalCommandOutput.freqKHz(frequencyMHz),
        bandwidthKHz: LocalCommandOutput.bandwidthHz(bandwidthKHz),
        spreadingFactor: spreadingFactor,
        codingRate: codingRate
      )
      output(LocalCommandOutput.ok)
    } catch { renderError(error) }
  }

  private func runSetFrequency(_ frequencyMHz: Double) async {
    guard let settingsService else { return notConnected() }
    do {
      let current = try await settingsService.getSelfInfo()
      _ = try await settingsService.setRadioParamsVerified(
        frequencyKHz: LocalCommandOutput.freqKHz(frequencyMHz),
        bandwidthKHz: LocalCommandOutput.bandwidthHz(current.radioBandwidth),
        spreadingFactor: current.radioSpreadingFactor,
        codingRate: current.radioCodingRate
      )
      output(LocalCommandOutput.ok)
    } catch { renderError(error) }
  }

  private func runSetMultiAcks(_ value: UInt8) async {
    guard let settingsService else { return notConnected() }
    guard let connectedDevice else { return notConnected() }
    do {
      _ = try await settingsService.setOtherParamsVerified(from: connectedDevice, multiAcks: value)
      output(LocalCommandOutput.ok)
    } catch { renderError(error) }
  }

  private func runSetPathHashMode(_ mode: UInt8) async {
    guard let settingsService else { return notConnected() }
    do {
      _ = try await settingsService.setPathHashModeVerified(mode)
      output(LocalCommandOutput.ok)
    } catch { renderError(error) }
  }

  // MARK: - Custom Variables

  /// Fetches all custom vars in one round trip: `nil` dumps them, a key looks up
  /// client-side so an unknown key never reaches the wire (`Unknown var <key>`).
  private func runGetCustomVars(key: String?) async {
    guard let settingsService else { return notConnected() }
    do {
      let vars = try await settingsService.getCustomVars()
      completionEngine.updateCustomVarKeys(Array(vars.keys))
      guard let key else { return output(LocalCommandOutput.customVarList(vars)) }
      output(vars[key].map(LocalCommandOutput.value) ?? LocalCommandOutput.unknownVar(key))
    } catch { renderError(error) }
  }

  private func runSetCustomVar(key: String, value: String) async {
    guard let settingsService else { return notConnected() }
    do {
      try await settingsService.setCustomVar(key: key, value: value)
      output(LocalCommandOutput.customVarSet(key: key, value: value))
    } catch let SettingsServiceError.sessionError(meshError)
      where meshError.deviceErrorCode == .illegalArgument {
      output(LocalCommandOutput.unknownCustomVar)
    } catch { renderError(error) }
  }

  // MARK: - Actions

  private func runAdvert(flood: Bool) async {
    do {
      try await sendSelfAdvert(flood)
      output(flood ? LocalCommandOutput.floodAdvert : LocalCommandOutput.zeroHopAdvert)
    } catch { renderError(error) }
  }

  private func runReboot() async {
    guard let settingsService else { return notConnected() }
    do {
      try await settingsService.reboot()
      output(LocalCommandOutput.okRebooting)
    } catch { renderError(error) }
  }

  // MARK: - Output helpers

  private func notConnected() {
    appendOutput(L10n.Tools.Tools.Cli.notConnected, type: .error)
  }

  private func output(_ text: String) {
    guard !Task.isCancelled else { return }
    appendOutput(text, type: .response)
  }

  private func renderError(_ error: Error) {
    guard !Task.isCancelled else { return }
    appendOutput(error.localizedDescription, type: .error)
  }
}

private extension CLILocalUsage {
  var usageLine: String {
    switch self {
    case .get: L10n.Tools.Tools.Cli.usageGet
    case .set: L10n.Tools.Tools.Cli.usageSet
    case .setRadio: L10n.Tools.Tools.Cli.usageSetRadio
    }
  }
}
