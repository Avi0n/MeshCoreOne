import SwiftUI

/// Region-alert phase for settings sheet exit. Tests set this directly.
enum SettingsExitRegionAlertPhase: Equatable {
  case unsaved
  case saving
  case succeeded
  case failed
}

/// Decision state for Done, discard, and the region save alert.
@Observable
@MainActor
final class SettingsExitGuardState {
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

  func beginRegionSave() {
    guard showRegionAlert else { return }
    guard regionAlertPhase == .unsaved || regionAlertPhase == .failed else { return }
    regionAlertPhase = .saving
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
    case .saving:
      return
    case .unsaved, .failed, .succeeded:
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

struct SettingsExitGuard: ViewModifier {
  @Environment(\.dismiss) private var dismiss
  @State private var exitState = SettingsExitGuardState()

  var hasUncommittedSettingsEdits: Bool
  var hasUnsavedRegionChanges: Bool
  var isApplying: Bool
  var errorMessage: String?
  var revertUncommittedSettingsEdits: () -> Void
  var saveRegions: (() async -> Void)?
  /// Live post-save reads; stored `errorMessage` / `hasUnsavedRegionChanges` can be stale.
  var regionSaveErrorMessage: () -> String?
  var regionSaveHasUnsavedChanges: () -> Bool
  var discardUnsavedRegionChanges: () -> Void

  private var regionActionsDisabled: Bool {
    exitState.regionAlertPhase == .saving
  }

  func body(content: Content) -> some View {
    content
      .interactiveDismissDisabled(
        hasUncommittedSettingsEdits || hasUnsavedRegionChanges || isApplying
      )
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.RemoteNodes.RemoteNodes.Settings.done) {
            exitState.tapDone(
              isApplying: isApplying,
              hasUncommittedSettingsEdits: hasUncommittedSettingsEdits,
              hasUnsavedRegionChanges: hasUnsavedRegionChanges
            )
            consumeDismissIfNeeded()
          }
          .disabled(isApplying)
          .accessibilityValue(isApplying ? L10n.RemoteNodes.RemoteNodes.Settings.applyInProgress : "")
        }
      }
      .alert(
        L10n.RemoteNodes.RemoteNodes.Settings.discardTitle,
        isPresented: discardAlertBinding
      ) {
        Button(L10n.RemoteNodes.RemoteNodes.Settings.discardChanges, role: .destructive) {
          exitState.discardChanges(
            hasUnsavedRegionChanges: hasUnsavedRegionChanges,
            revert: revertUncommittedSettingsEdits
          )
          consumeDismissIfNeeded()
        }
        Button(L10n.RemoteNodes.RemoteNodes.Settings.keepEditing, role: .cancel) {
          exitState.keepEditing()
        }
      }
      .alert(
        L10n.RemoteNodes.RemoteNodes.Settings.unsavedRegionsTitle,
        isPresented: regionAlertBinding
      ) {
        Button(L10n.Localizable.Common.save) {
          // `.saving` must land before the alert binding clears on an `.unsaved` phase.
          exitState.beginRegionSave()
          Task { await runRegionSave() }
        }
        .disabled(regionActionsDisabled)
        Button(L10n.RemoteNodes.RemoteNodes.Settings.dontSave) {
          exitState.tapDontSave(discardUnsavedRegions: discardUnsavedRegionChanges)
          consumeDismissIfNeeded()
        }
        .disabled(regionActionsDisabled || exitState.regionAlertPhase == .succeeded)
        Button(L10n.Localizable.Common.cancel, role: .cancel) {
          exitState.tapCancelRegion()
        }
        .disabled(regionActionsDisabled)
      } message: {
        Text(regionAlertMessage)
      }
      .onChange(of: hasUnsavedRegionChanges) { _, unsaved in
        if !unsaved {
          exitState.noteRegionsPersisted()
        }
      }
  }

  private var discardAlertBinding: Binding<Bool> {
    Binding(
      get: { exitState.showDiscardAlert },
      set: { newValue in
        if !newValue {
          exitState.keepEditing()
        } else {
          exitState.showDiscardAlert = true
        }
      }
    )
  }

  private var regionAlertBinding: Binding<Bool> {
    Binding(
      get: { exitState.showRegionAlert },
      set: { newValue in
        if !newValue {
          exitState.tapCancelRegion()
        } else {
          exitState.showRegionAlert = true
        }
      }
    )
  }

  private var regionAlertMessage: String {
    switch exitState.regionAlertPhase {
    case .unsaved:
      L10n.RemoteNodes.RemoteNodes.Settings.unsavedRegionsMessage
    case .saving:
      L10n.RemoteNodes.RemoteNodes.Settings.savingRegions
    case .succeeded:
      L10n.RemoteNodes.RemoteNodes.Settings.unsavedRegionsMessage
    case .failed:
      errorMessage ?? L10n.RemoteNodes.RemoteNodes.Settings.unsavedRegionsMessage
    }
  }

  private func runRegionSave() async {
    guard let saveRegions else { return }
    await saveRegions()
    exitState.finishRegionSave(
      errorMessage: regionSaveErrorMessage(),
      hasUnsavedRegionChanges: regionSaveHasUnsavedChanges()
    )
    consumeDismissIfNeeded()
  }

  private func consumeDismissIfNeeded() {
    if exitState.consumeDismiss() {
      dismiss()
    }
  }
}

extension View {
  func settingsExitGuard(
    hasUncommittedSettingsEdits: Bool,
    hasUnsavedRegionChanges: Bool,
    isApplying: Bool,
    errorMessage: String?,
    revertUncommittedSettingsEdits: @escaping () -> Void,
    saveRegions: (() async -> Void)?,
    regionSaveErrorMessage: @escaping () -> String?,
    regionSaveHasUnsavedChanges: @escaping () -> Bool,
    discardUnsavedRegionChanges: @escaping () -> Void
  ) -> some View {
    modifier(
      SettingsExitGuard(
        hasUncommittedSettingsEdits: hasUncommittedSettingsEdits,
        hasUnsavedRegionChanges: hasUnsavedRegionChanges,
        isApplying: isApplying,
        errorMessage: errorMessage,
        revertUncommittedSettingsEdits: revertUncommittedSettingsEdits,
        saveRegions: saveRegions,
        regionSaveErrorMessage: regionSaveErrorMessage,
        regionSaveHasUnsavedChanges: regionSaveHasUnsavedChanges,
        discardUnsavedRegionChanges: discardUnsavedRegionChanges
      )
    )
  }
}
