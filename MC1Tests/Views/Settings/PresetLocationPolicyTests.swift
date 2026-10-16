import CoreLocation
import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("PresetLocationPolicy")
struct PresetLocationPolicyTests {
  private func usCA() -> RegionSelection {
    RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA", source: .location)
  }

  private func makePortugal(source: RegionSelection.Source) -> RegionSelection {
    RegionSelection(countryCode: "PT", source: source)
  }

  @Test
  func `expand only when not authorized and incomplete`() {
    #expect(
      PresetLocationPolicy.shouldExpandOnRadio(authorized: false, selection: nil)
    )
    #expect(
      !PresetLocationPolicy.shouldExpandOnRadio(authorized: true, selection: nil)
    )
    let usNoAdmin = RegionSelection(countryCode: "US", source: .manual)
    #expect(
      PresetLocationPolicy.shouldExpandOnRadio(authorized: false, selection: usNoAdmin)
    )
    #expect(
      !PresetLocationPolicy.shouldExpandOnRadio(authorized: true, selection: usNoAdmin)
    )
    #expect(
      !PresetLocationPolicy.shouldExpandOnRadio(authorized: false, selection: usCA())
    )
  }

  @Test
  func `incomplete is nil or a subdivided country with no administrativeAreaCode`() {
    #expect(PresetLocationPolicy.isIncomplete(nil))
    #expect(
      PresetLocationPolicy.isIncomplete(
        RegionSelection(countryCode: "US", source: .manual)
      )
    )
    #expect(!PresetLocationPolicy.isIncomplete(usCA()))
    #expect(
      !PresetLocationPolicy.isIncomplete(
        RegionSelection(countryCode: "US", administrativeAreaCode: "US-TX", source: .manual)
      )
    )
    #expect(
      PresetLocationPolicy.isIncomplete(
        RegionSelection(countryCode: "AU", source: .manual)
      )
    )
    #expect(!PresetLocationPolicy.isIncomplete(makePortugal(source: .manual)))
  }

  @Test
  func `shouldResolveOnAppear is true only when authorized and source is not manual`() {
    #expect(
      PresetLocationPolicy.shouldResolveOnAppear(authorized: true, source: nil)
    )
    #expect(
      PresetLocationPolicy.shouldResolveOnAppear(authorized: true, source: .location)
    )
    #expect(
      !PresetLocationPolicy.shouldResolveOnAppear(authorized: true, source: .manual)
    )
    #expect(
      !PresetLocationPolicy.shouldResolveOnAppear(authorized: false, source: .location)
    )
    #expect(
      !PresetLocationPolicy.shouldResolveOnAppear(authorized: false, source: nil)
    )
  }

  @Test
  func `useMyLocationAction maps authorization status`() {
    #expect(
      PresetLocationPolicy.useMyLocationAction(status: .authorizedWhenInUse) == .resolve
    )
    #expect(
      PresetLocationPolicy.useMyLocationAction(status: .authorizedAlways) == .resolve
    )
    #expect(
      PresetLocationPolicy.useMyLocationAction(status: .notDetermined) == .waitForAuthorization
    )
    #expect(
      PresetLocationPolicy.useMyLocationAction(status: .denied) == .openSettings
    )
    #expect(
      PresetLocationPolicy.useMyLocationAction(status: .restricted) == .openSettings
    )
  }

  @Test
  func `appear commit keeps manual Portugal over California GPS`() {
    let portugal = makePortugal(source: .manual)
    let california = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      source: .location
    )
    #expect(
      PresetLocationPolicy.committedSelection(
        current: portugal,
        result: california,
        kind: .appear
      ) == portugal
    )
  }

  @Test
  func `user-initiated commit replaces manual Portugal with California GPS`() {
    let portugal = makePortugal(source: .manual)
    let california = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      source: .location
    )
    #expect(
      PresetLocationPolicy.committedSelection(
        current: portugal,
        result: california,
        kind: .userInitiated
      ) == california
    )
  }

  @Test
  func `actionAfterAuthorizationWait`() {
    #expect(
      PresetLocationPolicy.actionAfterAuthorizationWait(status: .authorizedWhenInUse) == .resolve
    )
    #expect(
      PresetLocationPolicy.actionAfterAuthorizationWait(status: .denied) == .openSettings
    )
    #expect(
      PresetLocationPolicy.actionAfterAuthorizationWait(status: .restricted) == .openSettings
    )
    #expect(
      PresetLocationPolicy.actionAfterAuthorizationWait(status: .notDetermined) == .none
    )
  }

  @Test
  func `lookup miss is silent on appear and when request is in flight`() {
    #expect(
      !PresetLocationPolicy.shouldPresentLookupMiss(kind: .appear, requestInProgress: false)
    )
    #expect(
      !PresetLocationPolicy.shouldPresentLookupMiss(kind: .userInitiated, requestInProgress: true)
    )
    #expect(
      PresetLocationPolicy.shouldPresentLookupMiss(kind: .userInitiated, requestInProgress: false)
    )
  }

  @Test
  func `committedSelection with nil GPS keeps current`() {
    let portugal = makePortugal(source: .manual)
    #expect(
      PresetLocationPolicy.committedSelection(
        current: portugal,
        result: nil,
        kind: .appear
      ) == portugal
    )
    #expect(
      PresetLocationPolicy.committedSelection(
        current: portugal,
        result: nil,
        kind: .userInitiated
      ) == portugal
    )
    #expect(
      PresetLocationPolicy.committedSelection(
        current: nil,
        result: nil,
        kind: .appear
      ) == nil
    )
  }

  @Test
  func `shouldCommitAppearResult is false only for manual`() {
    #expect(!PresetLocationPolicy.shouldCommitAppearResult(currentSource: .manual))
    #expect(PresetLocationPolicy.shouldCommitAppearResult(currentSource: .location))
    #expect(PresetLocationPolicy.shouldCommitAppearResult(currentSource: nil))
  }

  @Test
  func `appear commit of nil current takes GPS result`() {
    let california = usCA()
    #expect(
      PresetLocationPolicy.committedSelection(
        current: nil,
        result: california,
        kind: .appear
      ) == california
    )
  }

  @Test
  func `appear commit of location current takes GPS result`() {
    let portugal = makePortugal(source: .location)
    let california = usCA()
    #expect(
      PresetLocationPolicy.committedSelection(
        current: portugal,
        result: california,
        kind: .appear
      ) == california
    )
  }

  @Test
  func `useMyLocation failure copy does not say region, below, or this screen`() {
    let text = L10n.Settings.Radio.PresetLocation.UseMyLocation.failure
    #expect(!text.localizedCaseInsensitiveContains("region"))
    #expect(!text.localizedCaseInsensitiveContains("below"))
    #expect(!text.localizedCaseInsensitiveContains("this screen"))
  }

  @Test
  func `useMyLocation locating copy does not say region`() {
    let text = L10n.Settings.Radio.PresetLocation.UseMyLocation.locating
    #expect(!text.localizedCaseInsensitiveContains("region"))
    #expect(!text.isEmpty)
  }

  @Test
  func `denied copy does not say region or mesh contacts`() {
    let text = L10n.Settings.Radio.PresetLocation.denied
    #expect(!text.localizedCaseInsensitiveContains("region"))
    #expect(!text.localizedCaseInsensitiveContains("contact"))
    #expect(!text.localizedCaseInsensitiveContains("mesh"))
  }

  @Test
  func `pt lookup-miss and denied use voce`() throws {
    let bundle = try #require(Self.localeBundle("pt"))
    let failure = bundle.localizedString(
      forKey: "radio.presetLocation.useMyLocation.failure",
      value: Self.sentinel,
      table: "Settings"
    )
    let denied = bundle.localizedString(
      forKey: "radio.presetLocation.denied",
      value: Self.sentinel,
      table: "Settings"
    )
    #expect(failure.contains("sua localização"))
    #expect(!failure.contains("tua"))
    #expect(denied.contains("Ative-a"))
    #expect(!denied.contains("Ativa-a"))
  }

  private static let sentinel = "\u{0}__settings_key_missing__\u{0}"

  private static func localeBundle(_ locale: String) -> Bundle? {
    guard let url = Bundle.main.url(forResource: locale, withExtension: "lproj") else {
      return nil
    }
    return Bundle(url: url)
  }
}
