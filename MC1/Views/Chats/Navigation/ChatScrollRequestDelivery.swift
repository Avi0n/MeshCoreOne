import Foundation

/// Route-matched consumption of a pending reaction-scroll request.
enum ChatScrollRequestDelivery {
  /// Leaves unmatched or not-yet-honourable targets in place so a later open
  /// or a still-loading timeline can consume them.
  @MainActor
  static func takeIfHonorable(
    from navigation: NavigationCoordinator,
    kind: ChatRoute.Kind,
    conversationID: UUID,
    canHonor: Bool
  ) -> UUID? {
    guard canHonor else { return nil }
    return navigation.takePendingScrollTarget(
      matchingKind: kind,
      conversationID: conversationID
    )
  }
}
