import Foundation

extension AppState {
  func invalidateMissingNodesContact(
    from serviceID: ObjectIdentifier,
    contactExists: @MainActor (UUID) async throws -> Bool
  ) async {
    guard !navigation.nodesShowingDiscovery, let selected = navigation.selectedContact,
          let radioID = currentRadioID, selected.radioID == radioID,
          services.map({ ObjectIdentifier($0) }) == serviceID else { return }
    let revision = navigation.nodesPresentationRevision
    let version = servicesVersion
    do {
      let exists = try await contactExists(selected.id)
      // A successful store read may close only the presentation and connection that requested it.
      guard !Task.isCancelled, !exists, navigation.selectedContact?.id == selected.id,
            navigation.nodesPresentationRevision == revision, currentRadioID == radioID,
            servicesVersion == version, services.map({ ObjectIdentifier($0) }) == serviceID else { return }
      navigation.clearSelectedContact(matching: selected.id)
    } catch {
      return
    }
  }
}
