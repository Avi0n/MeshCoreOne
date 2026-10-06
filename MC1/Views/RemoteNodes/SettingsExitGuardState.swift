import SwiftUI

/// Decision state for Done, discard, and the region save alert.
@Observable
@MainActor
final class SettingsExitGuardState {
  /// Phase of the region save alert.
  enum SettingsExitRegionAlertPhase: Equatable {
    case unsaved
    case saving
    case succeeded
    case failed
  }

  var showDiscardAlert = false
  var showRegionAlert = false
  var regionAlertPhase: SettingsExitRegionAlertPhase = .unsaved
  private(set) var didDismissSheet = false

  func tapDone(
    isApplying: Bool,
    hasUncommittedSettingsEdits: Bool,
    hasUnsavedRegionChanges: Bool
  ) {
    guard !isApplying else { return }
    if hasUncommittedSettingsEdits {
      showDiscardAlert = true
    } else if hasUnsavedRegionChanges {
      presentRegionAlert()
    } else {
      dismissSheet()
    }
  }

  func keepEditing() {
    showDiscardAlert = false
  }

  func discardChanges(hasUnsavedRegionChanges: Bool, revert: () -> Void) {
    showDiscardAlert = false
    revert()
    if hasUnsavedRegionChanges {
      presentRegionAlert()
    } else {
      dismissSheet()
    }
  }

  func presentRegionAlert() {
    regionAlertPhase = .unsaved
    showRegionAlert = true
  }

  @discardableResult
  func beginRegionSave() -> Bool {
    guard showRegionAlert else { return false }
    guard regionAlertPhase == .unsaved || regionAlertPhase == .failed else { return false }
    regionAlertPhase = .saving
    return true
  }

  func regionAlertMessage(errorMessage: String?) -> String {
    switch regionAlertPhase {
    case .unsaved:
      L10n.RemoteNodes.RemoteNodes.Settings.unsavedRegionsMessage
    case .saving, .succeeded:
      L10n.RemoteNodes.RemoteNodes.Settings.savingRegions
    case .failed:
      errorMessage ?? L10n.RemoteNodes.RemoteNodes.Settings.unsavedRegionsMessage
    }
  }

  func noteRegionsPersisted() {
    guard regionAlertPhase == .saving else { return }
    regionAlertPhase = .succeeded
  }

  /// True when the sheet should close because Save finished with the alert still up.
  @discardableResult
  func finishRegionSave(errorMessage: String?, hasUnsavedRegionChanges: Bool) -> Bool {
    if errorMessage != nil {
      regionAlertPhase = .failed
      return false
    }
    if hasUnsavedRegionChanges {
      if regionAlertPhase == .saving {
        regionAlertPhase = .unsaved
      }
      return false
    }
    if regionAlertPhase == .saving {
      regionAlertPhase = .succeeded
    }
    guard regionAlertPhase == .succeeded, showRegionAlert else { return false }
    showRegionAlert = false
    dismissSheet()
    return true
  }

  func tapDontSave(discardUnsavedRegions: () -> Void = {}) {
    switch regionAlertPhase {
    case .saving, .succeeded:
      return
    case .unsaved, .failed:
      discardUnsavedRegions()
      showRegionAlert = false
      dismissSheet()
    }
  }

  func tapCancelRegion() {
    switch regionAlertPhase {
    case .saving, .succeeded:
      return
    case .unsaved, .failed:
      showRegionAlert = false
    }
  }

  private func dismissSheet() {
    didDismissSheet = true
  }

  func consumeDismiss() -> Bool {
    guard didDismissSheet else { return false }
    didDismissSheet = false
    return true
  }
}
