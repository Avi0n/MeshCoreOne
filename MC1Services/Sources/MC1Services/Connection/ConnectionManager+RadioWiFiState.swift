import Foundation

extension ConnectionManager {
  public func radioWiFiState(for radioID: UUID) -> RadioWiFiState {
    if let existing = radioWiFiStates[radioID] { return existing }
    let state = RadioWiFiState(radioID: radioID)
    radioWiFiStates[radioID] = state
    return state
  }

  func transferRadioWiFiEvidence(from previousID: UUID, to canonicalID: UUID) async {
    let previous = radioWiFiState(for: previousID)
    let canonical = radioWiFiState(for: canonicalID)
    previous.closeAdmission()
    canonical.closeAdmission()
    await previous.waitForMutationSettlement()
    if canonical !== previous { await canonical.waitForMutationSettlement() }
    canonical.adoptCanonicalEvidence(rebootPending: previous.rebootPending)
    if canonical !== previous, radioWiFiStates[previousID] === previous { radioWiFiStates[previousID] = nil }
  }

  func cancelExpectedWiFiReboots() {
    for state in radioWiFiStates.values {
      state.cancelExpectedReboot()
    }
  }

  func revalidateExpectedWiFiReboot(_ link: ConnectionLink) async {
    let settings = link.services.settingsService
    let state = settings.radioWiFiState
    guard owns(link), connectionState == .ready,
          let candidate = state.expectedRebootID else { return }
    await settings.confirmExpectedRadioWiFiReboot(id: candidate, expectedRadioID: link.device.radioID) { [weak self] in
      guard let self else { return false }
      return self.owns(link) && self.connectionState == .ready
    }
  }
}
