import SwiftUI

struct SettingsExitGuard: ViewModifier {
  @Environment(\.dismiss) private var dismiss
  @State private var exitState = SettingsExitGuardState()

  var hasUncommittedSettingsEdits: Bool
  var isApplying: Bool
  var errorMessage: String?
  var revertUncommittedSettingsEdits: () -> Void
  var regions: RegionExitActions?

  private var hasUnsavedRegionChanges: Bool {
    regions?.hasUnsavedChanges ?? false
  }

  private var regionActionsDisabled: Bool {
    exitState.regionAlertPhase == .saving || exitState.regionAlertPhase == .succeeded
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
              regions: regions
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
            regions: regions,
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
          guard exitState.beginRegionSave() else { return }
          Task { await runRegionSave() }
        }
        .disabled(regionActionsDisabled)
        Button(L10n.RemoteNodes.RemoteNodes.Settings.dontSave) {
          exitState.tapDontSave(discardUnsavedRegions: { regions?.discard() })
          consumeDismissIfNeeded()
        }
        .disabled(regionActionsDisabled)
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
    exitState.regionAlertMessage(errorMessage: errorMessage)
  }

  private func runRegionSave() async {
    guard let regions else { return }
    await regions.save()
    exitState.finishRegionSave(regions)
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
    isApplying: Bool,
    errorMessage: String?,
    revertUncommittedSettingsEdits: @escaping () -> Void,
    regions: RegionExitActions? = nil
  ) -> some View {
    modifier(
      SettingsExitGuard(
        hasUncommittedSettingsEdits: hasUncommittedSettingsEdits,
        isApplying: isApplying,
        errorMessage: errorMessage,
        revertUncommittedSettingsEdits: revertUncommittedSettingsEdits,
        regions: regions
      )
    )
  }
}
