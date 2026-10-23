@testable import MC1
@testable import MC1Services
import UIKit

extension SettingsTabHarness {
  @discardableResult
  func showSettingsList() async throws -> Bool {
    navigation.selectedTab = AppTab.settings.rawValue
    note = "show settings list"
    return try await waitUntil { showsSettingsList() }
  }

  @discardableResult
  func open(title: String, markers: [String], detail: SettingsDetail) async throws -> Bool {
    guard try await showSettingsList() else { return false }
    note = "open \(title)"
    navigation.navigateToSetting(detail)
    return try await waitUntil {
      navigation.selectedSetting == detail && showsPage(title: title, markers: markers)
    }
  }

  @discardableResult
  func openChild(row: String, title: String, markers: [String], detail: SettingsDetail) async throws -> Bool {
    note = "open child \(row)"
    guard try await activateRow(row) else { return false }
    return try await waitUntil {
      navigation.selectedSetting == detail && showsPage(title: title, markers: markers)
    }
  }

  /// Changes navigation state without simulating a native Back gesture.
  @discardableResult
  func pop() async throws -> Bool {
    guard try await waitUntil({ navigationAcceptsInput }) else { return false }
    let navs = navigationControllers().filter { viewIsVisible($0.view) }
    if let nav = navs.filter({ $0.viewControllers.count > 1 })
      .max(by: { $0.viewControllers.count < $1.viewControllers.count }),
      nav.popViewController(animated: false) != nil {
      window.layoutIfNeeded()
      return true
    }
    if let nav = navs.first(where: { $0.navigationBar.backItem != nil }),
       nav.popViewController(animated: false) != nil {
      window.layoutIfNeeded()
      return true
    }
    for bar in navigationBars() where viewIsVisible(bar) {
      guard let button = buttons(in: bar).first(where: {
        $0.accessibilityIdentifier == "BackButton" || buttonMatches($0, title: L10n.Settings.title)
      }) else { continue }
      if button.allControlEvents.contains(.primaryActionTriggered) {
        button.performPrimaryAction()
      } else {
        button.sendActions(for: .touchUpInside)
      }
      window.layoutIfNeeded()
      return true
    }
    if let split = splitControllers().last(where: { viewIsVisible($0.view) }) {
      split.show(.primary)
      window.layoutIfNeeded()
      return true
    }
    return false
  }

  func resize(regularWidth: Bool, compactHeight: Bool = false) async throws -> Bool {
    guard try await waitUntil({ navigationAcceptsInput }) else { return false }
    let size = Self.windowSize(regularWidth: regularWidth, compactHeight: compactHeight)
    window.frame = CGRect(origin: .zero, size: size)
    Self.applySize(controller, regularWidth: regularWidth, compactHeight: compactHeight)
    controller.view.frame = window.bounds
    window.layoutIfNeeded()
    return try await waitUntil { navigationAcceptsInput }
  }

  func selectTab(_ tab: AppTab) {
    navigation.selectedTab = tab.rawValue
    window.layoutIfNeeded()
  }

  func disconnect() {
    appState.connectionManager.setTestState(
      connectionState: .disconnected,
      services: .some(nil),
      session: .some(nil),
      connectedDevice: .some(nil)
    )
    window.layoutIfNeeded()
  }

  func replaceConnectedDevice() {
    appState.connectionManager.setTestState(
      connectedDevice: .some(Self.makeDevice(name: "Field"))
    )
    window.layoutIfNeeded()
  }

  func activateSearch() -> Bool {
    guard let searchController = currentSearchController else { return false }
    searchController.isActive = true
    window.layoutIfNeeded()
    return true
  }

  var searchPresentationIsSettled: Bool {
    guard let searchController = currentSearchController else { return false }
    return searchController.isActive && !searchController.isBeingPresented
      && !searchController.isBeingDismissed && searchController.transitionCoordinator == nil
      && searchController.searchBar.window === window
  }

  var searchStateDescription: String {
    guard let search = currentSearchController else { return "none" }
    return "active=\(search.isActive) presenting=\(search.isBeingPresented) dismissing=\(search.isBeingDismissed) transition=\(search.transitionCoordinator != nil) query=\(searchQuery() ?? "nil")"
  }

  @discardableResult
  func setSearchQuery(_ query: String) -> Bool {
    let searchController = currentSearchController
    window.layoutIfNeeded()
    guard let bar = searchBars().first(where: { viewIsVisible($0) }) ?? searchController?.searchBar else { return false }
    let field = bar.searchTextField
    guard field.window === window, field.becomeFirstResponder() else { return false }
    if field.text != query {
      guard let range = field.textRange(from: field.beginningOfDocument, to: field.endOfDocument) else { return false }
      field.selectedTextRange = range
      field.insertText(query)
    }
    window.layoutIfNeeded()
    return searchQuery() == query
  }

  func searchQuery() -> String? {
    searchBars().first { viewIsVisible($0) }?.text ?? currentSearchController?.searchBar.text
  }

  private var currentSearchController: UISearchController? {
    navigationBars().filter { viewIsVisible($0) }.compactMap { $0.topItem?.searchController }.first
  }

  var navigationAcceptsInput: Bool {
    let visible = navigationControllers().filter { viewIsVisible($0.view) }
    return !visible.isEmpty && visible.allSatisfy { $0.transitionCoordinator == nil }
  }

  @discardableResult
  private func activateRow(_ title: String) async throws -> Bool {
    var match: (list: UICollectionView, indexPath: IndexPath)?
    guard try await waitUntil({
      guard navigationAcceptsInput else { return false }
      let lists = visibleCollectionViews().filter { list in
        list.visibleCells.contains { acceptsInput($0, in: list) }
      }
      guard let candidate = cell(matching: title, in: lists) else { return false }
      candidate.list.scrollToItem(at: candidate.indexPath, at: .centeredVertically, animated: false)
      window.layoutIfNeeded()
      guard let cell = candidate.list.cellForItem(at: candidate.indexPath),
            acceptsInput(cell, in: candidate.list) else { return false }
      match = candidate
      return true
    }), let match else {
      note = "\(note) no-input-ready-cell"
      return false
    }
    let list = match.list
    let indexPath = match.indexPath
    note = "\(note) cell=\(indexPath.section).\(indexPath.item)"
    if let cell = list.cellForItem(at: indexPath), activateAccessibility(in: cell, title: title) {
      note = "\(note) accessibility"
      window.layoutIfNeeded()
      return true
    }
    guard let delegate = list.delegate else { return false }
    guard delegate.collectionView?(list, shouldSelectItemAt: indexPath) ?? true else { return false }
    list.selectItem(at: indexPath, animated: false, scrollPosition: [])
    delegate.collectionView?(list, didSelectItemAt: indexPath)
    let canPerform = delegate.collectionView?(list, canPerformPrimaryActionForItemAt: indexPath) ?? !list.isEditing
    if canPerform,
       delegate.responds(to: #selector(UICollectionViewDelegate.collectionView(_:performPrimaryActionForItemAt:))) {
      note += " action=primary"
      delegate.collectionView?(list, performPrimaryActionForItemAt: indexPath)
    } else if let button = buttons(in: list.cellForItem(at: indexPath)).first(where: { buttonMatches($0, title: title) }) {
      note += " action=button"
      button.performPrimaryAction()
    } else {
      note += " action=selection"
    }
    window.layoutIfNeeded()
    return true
  }

  func cell(matching title: String, in lists: [UICollectionView]) -> (list: UICollectionView, indexPath: IndexPath)? {
    for list in lists {
      var match: IndexPath?
      let found = searchListPages(list) {
        match = indexPath(matching: title, in: list)
        return match != nil
      }
      if found, let match {
        return (list, match)
      }
    }
    return nil
  }

  private func acceptsInput(_ cell: UICollectionViewCell, in list: UICollectionView) -> Bool {
    guard viewIsVisible(cell) else { return false }
    let center = cell.convert(CGPoint(x: cell.bounds.midX, y: cell.bounds.midY), to: window)
    guard window.bounds.contains(center), let hit = window.hitTest(center, with: nil),
          hit === list || hit.isDescendant(of: cell) else { return false }
    var ancestor: UIView? = hit
    while let current = ancestor {
      guard current.isUserInteractionEnabled, (current as? UIControl)?.isEnabled != false else { return false }
      ancestor = current.superview
    }
    return true
  }

  /// Leaves a matched row visible; restores the offset when no row matches.
  private func searchListPages(_ list: UICollectionView, _ found: () -> Bool) -> Bool {
    if found() { return true }
    let start = list.contentOffset
    let maxOffset = max(0, list.contentSize.height - list.bounds.height)
    let step = list.bounds.height * Recognition.scrollOverlap
    var down = start.y
    for _ in 0..<Recognition.maxScrollPages {
      if down >= maxOffset - 1 { break }
      down = min(maxOffset, down + step)
      list.setContentOffset(CGPoint(x: start.x, y: down), animated: false)
      window.layoutIfNeeded()
      if found() { return true }
    }
    var up = start.y
    for _ in 0..<Recognition.maxScrollPages {
      if up <= 1 { break }
      up = max(0, up - step)
      list.setContentOffset(CGPoint(x: start.x, y: up), animated: false)
      window.layoutIfNeeded()
      if found() { return true }
    }
    list.setContentOffset(start, animated: false)
    window.layoutIfNeeded()
    return false
  }

  private func activateAccessibility(in root: UIView, title: String) -> Bool {
    var matched: [NSObject] = []
    func walk(_ object: NSObject) {
      if matchesLabel(object.accessibilityLabel, target: title) {
        matched.append(object)
      }
      guard let view = object as? UIView else { return }
      let count = view.accessibilityElementCount()
      if count != NSNotFound, count > 0 {
        for index in 0..<count {
          guard let element = view.accessibilityElement(at: index) as? NSObject, element !== view else { continue }
          walk(element)
        }
      }
      for subview in view.subviews {
        walk(subview)
      }
    }
    walk(root)
    return matched.contains { $0.accessibilityActivate() }
  }

  private func buttonMatches(_ button: UIButton, title: String) -> Bool {
    matchesLabel(button.currentTitle, target: title)
      || matchesLabel(button.accessibilityLabel, target: title)
      || matchesLabel(button.title(for: .normal), target: title)
  }

  /// Uses rendered text when SwiftUI rows expose no UILabel.
  func listShowsWhenScrolled(_ text: String, in list: UICollectionView) -> Bool {
    searchListPages(list) { titleIsVisible(text, in: list) }
  }
}
