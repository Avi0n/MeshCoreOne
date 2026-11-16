struct SignInPathFooter: Equatable {
  let message: String
  let showsSavedHops: Bool
  let showsNoRouteLabel: Bool
  let showsFloodRoutingOn: Bool
  let canChangeFloodRouting: Bool
}

func signInPathFooter(
  hasStoredRoute: Bool,
  floodRouting: Bool,
  routeCleared: Bool,
  fallbackEnabled: Bool,
  isAuthenticating: Bool
) -> SignInPathFooter {
  if routeCleared {
    return SignInPathFooter(
      message: L10n.RemoteNodes.RemoteNodes.Auth.floodFooterCleared,
      showsSavedHops: false,
      showsNoRouteLabel: false,
      showsFloodRoutingOn: true,
      canChangeFloodRouting: false
    )
  }
  if !hasStoredRoute {
    return SignInPathFooter(
      message: L10n.RemoteNodes.RemoteNodes.Auth.noRouteFooter,
      showsSavedHops: false,
      showsNoRouteLabel: true,
      showsFloodRoutingOn: floodRouting,
      canChangeFloodRouting: false
    )
  }
  let canChangeFloodRouting = !isAuthenticating
  if floodRouting {
    return SignInPathFooter(
      message: L10n.RemoteNodes.RemoteNodes.Auth.floodFooter,
      showsSavedHops: false,
      showsNoRouteLabel: false,
      showsFloodRoutingOn: true,
      canChangeFloodRouting: canChangeFloodRouting
    )
  }
  return SignInPathFooter(
    message: fallbackEnabled
      ? L10n.RemoteNodes.RemoteNodes.Auth.pathFooter
      : L10n.RemoteNodes.RemoteNodes.Auth.pathFooterKept,
    showsSavedHops: true,
    showsNoRouteLabel: false,
    showsFloodRoutingOn: false,
    canChangeFloodRouting: canChangeFloodRouting
  )
}
