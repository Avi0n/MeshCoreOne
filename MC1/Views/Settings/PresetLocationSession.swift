import SwiftUI

@Observable
@MainActor
final class PresetLocationSession {
  var errorMessage: String?
  var showOpenSettingsAlert = false
  var isResolving = false

  private static let locationSlotWait: TimeInterval = 10
  private static let locationSlotPoll: Duration = .milliseconds(50)

  private var task: Task<Void, Never>?
  private var generation = 0

  func resolveOnAppear(from appState: AppState) {
    guard PresetLocationPolicy.shouldResolveOnAppear(
      authorized: appState.locationService.isAuthorized,
      source: appState.regionSelection?.source
    ) else { return }
    enqueue(kind: .appear, appState: appState)
  }

  func useMyLocation(from appState: AppState) {
    enqueue(kind: .userInitiated, appState: appState)
  }

  private func enqueue(kind: PresetLocationPolicy.ResolveKind, appState: AppState) {
    if let existing = task {
      guard kind == .userInitiated else { return }
      start(kind: .userInitiated, appState: appState, waitingOn: existing)
      return
    }
    start(kind: kind, appState: appState, waitingOn: nil)
  }

  private func start(
    kind: PresetLocationPolicy.ResolveKind,
    appState: AppState,
    waitingOn: Task<Void, Never>?
  ) {
    generation += 1
    let current = generation
    task = Task { @MainActor in
      if let waitingOn {
        await waitingOn.value
      }
      await perform(kind: kind, appState: appState, generation: current)
      if generation == current {
        task = nil
      }
    }
  }

  private func perform(
    kind: PresetLocationPolicy.ResolveKind,
    appState: AppState,
    generation: Int
  ) async {
    isResolving = true
    defer {
      // A replaced enqueue must not clear isResolving or the button re-enables between GPS slots.
      if self.generation == generation {
        isResolving = false
      }
    }

    switch kind {
    case .appear:
      await runResolve(kind: .appear, appState: appState)
    case .userInitiated:
      switch PresetLocationPolicy.useMyLocationAction(
        status: appState.locationService.authorizationStatus
      ) {
      case .openSettings:
        showOpenSettingsAlert = true
      case .resolve:
        await runResolve(kind: .userInitiated, appState: appState)
      case .waitForAuthorization:
        do {
          _ = try await appState.locationService.requestCurrentLocation()
        } catch {
          // Status is the source of truth: timeout, deny, or a location miss after grant.
        }
        switch PresetLocationPolicy.actionAfterAuthorizationWait(
          status: appState.locationService.authorizationStatus
        ) {
        case .resolve:
          await runResolve(kind: .userInitiated, appState: appState)
        case .openSettings:
          showOpenSettingsAlert = true
        case .none:
          break
        }
      }
    }
  }

  private func runResolve(kind: PresetLocationPolicy.ResolveKind, appState: AppState) async {
    if appState.locationService.isRequestingLocation {
      await waitForLocationSlot(appState)
    }
    if appState.locationService.isRequestingLocation {
      return
    }

    let result = await appState.regionResolver.resolve()
    guard !Task.isCancelled else { return }

    let next = PresetLocationPolicy.committedSelection(
      current: appState.regionSelection,
      result: result,
      kind: kind
    )
    if next != appState.regionSelection {
      appState.regionSelection = next
    }

    if result == nil,
       PresetLocationPolicy.shouldPresentLookupMiss(
         kind: kind,
         requestInProgress: false
       ) {
      errorMessage = L10n.Settings.Radio.PresetLocation.UseMyLocation.failure
    }
  }

  private func waitForLocationSlot(_ appState: AppState) async {
    let deadline = Date().addingTimeInterval(Self.locationSlotWait)
    while appState.locationService.isRequestingLocation, Date() < deadline {
      try? await Task.sleep(for: Self.locationSlotPoll)
    }
  }
}

extension View {
  func presetLocationSessionAlerts(
    _ session: PresetLocationSession,
    openURL: OpenURLAction
  ) -> some View {
    errorAlert(Bindable(session).errorMessage)
      .alert(
        L10n.Onboarding.Permissions.LocationAlert.title,
        isPresented: Bindable(session).showOpenSettingsAlert
      ) {
        Button(L10n.Onboarding.Permissions.LocationAlert.openSettings) {
          if let url = URL(string: UIApplication.openSettingsURLString) {
            openURL(url)
          }
        }
        Button(L10n.Localizable.Common.cancel, role: .cancel) {}
      } message: {
        Text(L10n.Settings.Radio.PresetLocation.denied)
      }
  }
}
