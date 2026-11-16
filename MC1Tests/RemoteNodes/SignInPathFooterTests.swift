@testable import MC1
import Testing

@Suite("Sign-in path footer")
struct SignInPathFooterTests {
  @Test
  func `a cleared route stays cleared when the contact has no stored route`() {
    let choice = signInPathFooter(
      hasStoredRoute: false,
      floodRouting: true,
      routeCleared: true,
      fallbackEnabled: false,
      isAuthenticating: false
    )

    #expect(choice.message == L10n.RemoteNodes.RemoteNodes.Auth.floodFooterCleared)
    #expect(choice.showsSavedHops == false)
    #expect(choice.showsNoRouteLabel == false)
    #expect(choice.showsFloodRoutingOn)
    #expect(choice.canChangeFloodRouting == false)
  }

  @Test
  func `a cleared route stays cleared when flood routing is on`() {
    let choice = signInPathFooter(
      hasStoredRoute: true,
      floodRouting: true,
      routeCleared: true,
      fallbackEnabled: false,
      isAuthenticating: false
    )

    #expect(choice.message == L10n.RemoteNodes.RemoteNodes.Auth.floodFooterCleared)
    #expect(choice.showsSavedHops == false)
    #expect(choice.showsNoRouteLabel == false)
    #expect(choice.showsFloodRoutingOn)
    #expect(choice.canChangeFloodRouting == false)
  }

  @Test(arguments: [false, true])
  func `no stored route shows flood routing and locks it`(floodRouting: Bool) {
    let choice = signInPathFooter(
      hasStoredRoute: false,
      floodRouting: floodRouting,
      routeCleared: false,
      fallbackEnabled: !floodRouting,
      isAuthenticating: false
    )

    #expect(choice.message == L10n.RemoteNodes.RemoteNodes.Auth.noRouteFooter)
    #expect(choice.showsSavedHops == false)
    #expect(choice.showsNoRouteLabel)
    #expect(choice.showsFloodRoutingOn == floodRouting)
    #expect(choice.canChangeFloodRouting == false)
  }

  @Test
  func `flood routing on a stored route uses the flood footer`() {
    let choice = signInPathFooter(
      hasStoredRoute: true,
      floodRouting: true,
      routeCleared: false,
      fallbackEnabled: false,
      isAuthenticating: false
    )

    #expect(choice.message == L10n.RemoteNodes.RemoteNodes.Auth.floodFooter)
    #expect(choice.showsSavedHops == false)
    #expect(choice.showsNoRouteLabel == false)
    #expect(choice.showsFloodRoutingOn)
    #expect(choice.canChangeFloodRouting)
  }

  @Test(arguments: [false, true])
  func `a stored route with flood routing off follows path fallback`(fallbackEnabled: Bool) {
    let choice = signInPathFooter(
      hasStoredRoute: true,
      floodRouting: false,
      routeCleared: false,
      fallbackEnabled: fallbackEnabled,
      isAuthenticating: false
    )

    #expect(
      choice.message == (
        fallbackEnabled
          ? L10n.RemoteNodes.RemoteNodes.Auth.pathFooter
          : L10n.RemoteNodes.RemoteNodes.Auth.pathFooterKept
      )
    )
    #expect(choice.showsSavedHops)
    #expect(choice.showsNoRouteLabel == false)
    #expect(choice.showsFloodRoutingOn == false)
    #expect(choice.canChangeFloodRouting)
  }

  @Test(arguments: [false, true], [false, true])
  private func `an in-flight attempt on a stored route locks flood routing`(
    floodRouting: Bool,
    fallbackEnabled: Bool
  ) {
    let resting = signInPathFooter(
      hasStoredRoute: true,
      floodRouting: floodRouting,
      routeCleared: false,
      fallbackEnabled: fallbackEnabled,
      isAuthenticating: false
    )
    let waiting = signInPathFooter(
      hasStoredRoute: true,
      floodRouting: floodRouting,
      routeCleared: false,
      fallbackEnabled: fallbackEnabled,
      isAuthenticating: true
    )

    #expect(resting.canChangeFloodRouting)
    #expect(waiting.message == resting.message)
    #expect(waiting.showsSavedHops == resting.showsSavedHops)
    #expect(waiting.showsNoRouteLabel == resting.showsNoRouteLabel)
    #expect(waiting.showsFloodRoutingOn == resting.showsFloodRoutingOn)
    #expect(!waiting.canChangeFloodRouting)
  }
}
