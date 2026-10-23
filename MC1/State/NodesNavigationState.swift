import SwiftUI

@Observable
@MainActor
final class NodesNavigationState {
  private enum Depth {
    static let history = 1
    static let map = 2
  }

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
    if navigation.nodesShowingDiscovery {
      return .discovery(presentationRevision: navigation.nodesPresentationRevision)
    }
    return navigation.selectedContact.map {
      .contact(id: $0.id, presentationRevision: navigation.nodesPresentationRevision)
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
    if descendantStamp != stamp {
      descendants = []
      descendantStamp = stamp
    }
  }

  func detailPath(in navigation: NavigationCoordinator) -> [NodesRoute] {
    descendantStamp == Self.stamp(in: navigation) ? descendants : []
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
        withTransaction(transaction) { self.setDetailPath(path, stamp: stamp) }
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
    guard path.count == Depth.history, case .savedHistory = path.first else { return }
    setDetailPath(path + [.locationMap(presentationID: presentationID)], stamp: Self.stamp(in: navigation))
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
      setDetailPath(suffix, stamp: stamp)
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

  private func setDetailPath(_ path: [NodesRoute], stamp: RootStamp) {
    descendantStamp = stamp
    descendants = path
  }

  private func isSidebarPath(_ path: [NodesRoute]) -> Bool {
    path.isEmpty || path == [.blockedContacts]
  }

  private func isDetailPath(_ path: [NodesRoute], in navigation: NavigationCoordinator) -> Bool {
    if path.isEmpty { return true }
    guard !navigation.nodesShowingDiscovery, let contact = navigation.selectedContact,
          case let .savedHistory(history) = path.first,
          history.publicKey == contact.publicKey, history.radioID == contact.radioID else { return false }
    if path.count == Depth.history { return true }
    return path.count == Depth.map && {
      if case .locationMap = path.last { return true }
      return false
    }()
  }
}
