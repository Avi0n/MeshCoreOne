import CoreLocation
import MC1Services

enum PresetLocationPolicy {
  enum ResolveKind: Equatable {
    case appear
    case userInitiated
  }

  enum UseMyLocationAction: Equatable {
    case resolve
    case waitForAuthorization
    case openSettings
  }

  enum AfterAuthorizationWait: Equatable {
    case resolve
    case openSettings
    case none
  }

  static func isIncomplete(_ selection: RegionSelection?) -> Bool {
    guard let selection else { return true }
    return RegionalAreas.showsSubdivisionPicker(for: selection.countryCode)
      && selection.administrativeAreaCode == nil
  }

  static func shouldExpandOnRadio(authorized: Bool, selection: RegionSelection?) -> Bool {
    !authorized && isIncomplete(selection)
  }

  static func shouldResolveOnAppear(
    authorized: Bool,
    source: RegionSelection.Source?
  ) -> Bool {
    authorized && source != .manual
  }

  static func shouldCommitAppearResult(currentSource: RegionSelection.Source?) -> Bool {
    currentSource != .manual
  }

  static func committedSelection(
    current: RegionSelection?,
    result: RegionSelection?,
    kind: ResolveKind
  ) -> RegionSelection? {
    guard let result else { return current }
    switch kind {
    case .appear:
      return shouldCommitAppearResult(currentSource: current?.source) ? result : current
    case .userInitiated:
      return result
    }
  }

  static func useMyLocationAction(status: CLAuthorizationStatus) -> UseMyLocationAction {
    switch status {
    case .authorizedWhenInUse, .authorizedAlways:
      return .resolve
    case .notDetermined:
      return .waitForAuthorization
    case .denied, .restricted:
      return .openSettings
    @unknown default:
      return .openSettings
    }
  }

  static func actionAfterAuthorizationWait(
    status: CLAuthorizationStatus
  ) -> AfterAuthorizationWait {
    switch status {
    case .authorizedWhenInUse, .authorizedAlways:
      return .resolve
    case .denied, .restricted:
      return .openSettings
    case .notDetermined:
      return .none
    @unknown default:
      return .openSettings
    }
  }

  static func shouldPresentLookupMiss(
    kind: ResolveKind,
    requestInProgress: Bool
  ) -> Bool {
    kind == .userInitiated && !requestInProgress
  }
}
