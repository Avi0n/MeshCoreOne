import MC1Services
import SwiftUI

@Observable
@MainActor
final class NodesNavigationState {
  enum Presentation {
    case compact
    case regular
  }

  struct RootStamp: Equatable {
    let root: NodesRoute?
    let revision: Int
    let request: Int
  }

  private(set) var presentation: Presentation?
  private(set) var layoutRevision = 0
  private(set) var sidebarRevision = 0
  private(set) var sidebarPath: [NodesRoute] = []
  private var descendants: [NodesRoute] = []
  private var descendantStamp: RootStamp?

  init(presentation: Presentation? = nil) {
    self.presentation = presentation
  }

  static func root(in navigation: NavigationCoordinator) -> NodesRoute? {
    switch navigation.nodesSelection {
    case .discovery:
      .discovery(presentationRevision: navigation.nodesPresentationRevision)
    case let .contact(contact):
      .contact(id: contact.id, presentationRevision: navigation.nodesPresentationRevision)
    case let .watchlist(radioID, _):
      .watchlist(radioID: radioID, presentationRevision: navigation.nodesPresentationRevision)
    case nil:
      nil
    }
  }

  static func stamp(in navigation: NavigationCoordinator) -> RootStamp {
    RootStamp(
      root: root(in: navigation),
      revision: navigation.nodesPresentationRevision,
      request: navigation.nodesRootNavigationGeneration
    )
  }

  func changePresentation(to newValue: Presentation, prepare: (Int) -> Void = { _ in }) {
    guard presentation != newValue else { return }
    layoutRevision += 1
    prepare(layoutRevision)
    presentation = newValue
  }

  func synchronize(with navigation: NavigationCoordinator) {
    let stamp = Self.stamp(in: navigation)
    if descendantStamp != stamp || !isDetailPath(descendants, in: navigation) {
      descendants = []
      descendantStamp = stamp
    }
  }

  func detailPath(in navigation: NavigationCoordinator) -> [NodesRoute] {
    descendantStamp == Self.stamp(in: navigation) && isDetailPath(descendants, in: navigation) ? descendants : []
  }

  func compactPath(in navigation: NavigationCoordinator) -> [NodesRoute] {
    sidebarPath + (Self.root(in: navigation).map { [$0] } ?? []) + detailPath(in: navigation)
  }

  func sidebarBinding() -> Binding<[NodesRoute]> {
    let layout = layoutRevision
    let sidebar = sidebarRevision
    return Binding(
      get: { self.sidebarPath },
      set: { path, transaction in
        guard self.presentation == .regular, self.layoutRevision == layout,
              self.sidebarRevision == sidebar, self.isSidebarPath(path) else { return }
        withTransaction(transaction) { self.setSidebarPath(path) }
      }
    )
  }

  func detailBinding(in navigation: NavigationCoordinator) -> Binding<[NodesRoute]> {
    let layout = layoutRevision
    let stamp = Self.stamp(in: navigation)
    return Binding(
      get: { self.detailPath(in: navigation) },
      set: { path, transaction in
        guard self.presentation == .regular, self.layoutRevision == layout,
              Self.stamp(in: navigation) == stamp,
              self.isDetailPath(path, in: navigation) else { return }
        withTransaction(transaction) { self.setDetailPath(path, stamp: stamp, in: navigation) }
      }
    )
  }

  func compactBinding(in navigation: NavigationCoordinator) -> Binding<[NodesRoute]> {
    let layout = layoutRevision
    let sidebar = sidebarRevision
    let stamp = Self.stamp(in: navigation)
    return Binding(
      get: { self.compactPath(in: navigation) },
      set: { path, transaction in
        guard self.presentation == .compact, self.layoutRevision == layout,
              self.sidebarRevision == sidebar, Self.stamp(in: navigation) == stamp else { return }
        withTransaction(transaction) { self.setCompactPath(path, in: navigation, stamp: stamp) }
      }
    )
  }

  func openMap(presentationID: UUID, in navigation: NavigationCoordinator) {
    let path = detailPath(in: navigation)
    guard case .savedHistory = path.last else { return }
    setDetailPath(
      path + [.locationMap(presentationID: presentationID)],
      stamp: Self.stamp(in: navigation),
      in: navigation
    )
  }

  func openContact(_ contact: ContactDTO, in navigation: NavigationCoordinator) {
    guard case let .watchlist(radioID, nil) = navigation.nodesSelection, radioID == contact.radioID else { return }
    navigation.nodesSelection = .watchlist(radioID: radioID, contact: contact)
    setDetailPath(
      [.contact(id: contact.id, presentationRevision: navigation.nodesPresentationRevision)],
      stamp: Self.stamp(in: navigation),
      in: navigation
    )
  }

  func closeContact(matching id: UUID, in navigation: NavigationCoordinator) {
    navigation.clearSelectedContact(matching: id)
    synchronize(with: navigation)
  }

  private func setCompactPath(
    _ path: [NodesRoute], in navigation: NavigationCoordinator, stamp: RootStamp
  ) {
    let prefix: [NodesRoute] = path.first == .blockedContacts ? [.blockedContacts] : []
    let remaining = Array(path.dropFirst(prefix.count))
    guard let root = stamp.root else {
      guard remaining.isEmpty else { return }
      setSidebarPath(prefix)
      return
    }
    if remaining.first == root {
      let suffix = Array(remaining.dropFirst())
      guard prefix == sidebarPath, isDetailPath(suffix, in: navigation) else { return }
      setDetailPath(suffix, stamp: stamp, in: navigation)
    } else if remaining.isEmpty, prefix.isEmpty || prefix == sidebarPath {
      setSidebarPath(prefix)
      navigation.clearNodesRoot(matchingPresentation: stamp.revision)
      synchronize(with: navigation)
    }
  }

  private func setSidebarPath(_ path: [NodesRoute]) {
    guard sidebarPath != path else { return }
    sidebarRevision += 1
    sidebarPath = path
  }

  private func setDetailPath(_ path: [NodesRoute], stamp: RootStamp, in navigation: NavigationCoordinator) {
    descendantStamp = stamp
    descendants = path
    guard case let .watchlist(radioID, contact?) = navigation.nodesSelection else { return }
    let contactRoute = NodesRoute.contact(id: contact.id, presentationRevision: stamp.revision)
    if path.first != contactRoute {
      navigation.nodesSelection = .watchlist(radioID: radioID)
    }
  }

  private func isSidebarPath(_ path: [NodesRoute]) -> Bool {
    path.isEmpty || path == [.blockedContacts]
  }

  private func isDetailPath(_ path: [NodesRoute], in navigation: NavigationCoordinator) -> Bool {
    if path.isEmpty { return true }
    guard let contact = navigation.nodesSelection?.contact else { return false }
    var suffix = path[...]
    if case .watchlist = navigation.nodesSelection {
      guard suffix.first == .contact(id: contact.id, presentationRevision: navigation.nodesPresentationRevision) else {
        return false
      }
      suffix = suffix.dropFirst()
    }
    if suffix.isEmpty { return true }
    guard case let .savedHistory(history) = suffix.first,
          history.publicKey == contact.publicKey, history.radioID == contact.radioID else { return false }
    suffix = suffix.dropFirst()
    if suffix.isEmpty { return true }
    guard suffix.count == 1, case .locationMap = suffix.first else { return false }
    return true
  }
}
