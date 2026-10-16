import Foundation

/// Route-matched consumption of a pending reaction-scroll request.
enum ChatScrollRequestDelivery {
  /// Pages and bakes the target before consuming its request. A replaced or
  /// cancelled request cannot scroll a conversation after an await.
  @MainActor
  static func takeIfHonorable(
    from navigation: NavigationCoordinator,
    kind: ChatRoute.Kind,
    conversationID: UUID,
    canHonor: Bool,
    timeline: ChatTimeline,
    loadOlder: @MainActor () async -> Void
  ) async -> UUID? {
    guard canHonor,
          let request = navigation.pendingScrollTarget,
          request.matches(kind: kind, conversationID: conversationID),
          let coordinator = timeline.coordinator,
          let writer = timeline.writer else { return nil }

    func isCurrent() -> Bool {
      !Task.isCancelled
        && navigation.pendingScrollTarget?.requestID == request.requestID
        && timeline.coordinator === coordinator
        && timeline.writer === writer
        && writer.isCurrent
    }

    while isCurrent() {
      if timeline.itemIndexByID[request.messageID] != nil {
        return navigation.takePendingScrollTarget(
          matchingKind: kind,
          conversationID: conversationID
        )
      }

      if timeline.messagesByID[request.messageID] != nil {
        let generation = coordinator.renderStateID
        await coordinator.buildItemsTask?.value
        guard isCurrent() else { return nil }
        if timeline.itemIndexByID[request.messageID] != nil { continue }
        // A concurrent row update can replace the bake we just awaited.
        guard coordinator.renderStateID != generation else { return nil }
        continue
      }

      guard timeline.renderState.hasMoreMessages else {
        _ = navigation.takePendingScrollTarget(
          matchingKind: kind,
          conversationID: conversationID
        )
        return nil
      }

      // Hidden reaction rows still advance the persisted paging offset.
      let fetchedCount = timeline.renderState.totalFetchedCount
      await loadOlder()
      guard isCurrent() else { return nil }
      guard timeline.renderState.totalFetchedCount > fetchedCount || !timeline.renderState.hasMoreMessages else { return nil }
    }
    return nil
  }
}
