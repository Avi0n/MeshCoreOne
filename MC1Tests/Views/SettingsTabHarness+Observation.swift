@testable import MC1
import UIKit

extension SettingsTabHarness {
  func showsSettingsList() -> Bool {
    navigation.selectedTab == AppTab.settings.rawValue
      && navigation.selectedSetting == nil
      && navigationTitleIsShown(L10n.Settings.title)
      && visibleCollectionViews().contains { list in
        list.visibleCells.contains { viewIsVisible($0) }
      }
  }

  func showsNavigationTitle(_ title: String) -> Bool {
    navigationTitleIsShown(title)
  }

  func showsPage(title: String, markers: [String]) -> Bool {
    guard navigationTitleIsShown(title) else { return false }
    return markers.contains { textIsDrawn($0) }
  }

  func tabControlsAreOnScreen() -> Bool {
    let titles = Self.tabTitles
    guard let bar = tabBars().first(where: { bar in
      viewIsVisible(bar) && bar.isUserInteractionEnabled && (bar.items?.count ?? 0) == titles.count
    }) else { return false }
    let labels = controlLabels(in: bar)
    return titles.allSatisfy { title in
      labels.contains { matchesLabel($0, target: title) }
    }
  }

  func showsSelectedTab(_ tab: AppTab) -> Bool {
    navigation.selectedTab == tab.rawValue && navigationAcceptsInput
      && tabBars().contains { bar in
        viewIsVisible(bar) && bar.selectedItem?.title == Self.tabTitles[tab.rawValue]
      }
  }

  func describe() -> String {
    let titles = navigationControllers().map { nav in
      let pages = nav.viewControllers.map { $0.navigationItem.title ?? String(describing: type(of: $0)) }
      return "visible=\(viewIsVisible(nav.view)) transition=\(nav.transitionCoordinator != nil) titles=\(pages)"
    }
    let bars = tabBars().map { bar in
      let labels = controlLabels(in: bar).joined(separator: ",")
      return "visible=\(viewIsVisible(bar)) items=\(bar.items?.count ?? 0) labels=[\(labels)]"
    }
    return """
    note=\(note) setting=\(String(describing: navigation.selectedSetting)) tab=\(navigation.selectedTab) \
    connection=\(appState.connectionState) device=\(appState.connectedDevice?.nodeName ?? "nil") \
    search=[\(searchStateDescription)] navs=\(titles) tabs=\(bars)
    """
  }

  private static var tabTitles: [String] {
    [
      L10n.Localizable.Tabs.chats,
      L10n.Localizable.Tabs.nodes,
      L10n.Localizable.Tabs.map,
      L10n.Localizable.Tabs.tools,
      L10n.Localizable.Tabs.settings,
    ]
  }

  /// Window OCR avoids merged list lines; accept matches only within the list and outside the tab bar.
  func indexPath(matching title: String, in list: UICollectionView) -> IndexPath? {
    if let indexPath = list.indexPathsForVisibleItems.sorted().first(where: { indexPath in
      guard let cell = list.cellForItem(at: indexPath), viewIsVisible(cell) else { return false }
      return labels(in: cell).contains { matchesLabel($0, target: title) }
    }) {
      return indexPath
    }
    if let rect = matchingRect(in: list, target: title) {
      let origin = list.convert(CGPoint(x: list.bounds.minX, y: list.bounds.minY), to: window)
      let center = CGPoint(x: origin.x + rect.midX, y: origin.y + rect.midY)
      if let indexPath = indexPath(at: center, in: list) { return indexPath }
    }
    let listFrame = list.convert(list.bounds, to: window)
    for rect in windowRects(matching: title) {
      let center = CGPoint(x: rect.midX, y: rect.midY)
      guard listFrame.contains(center), !tabBarContains(center) else { continue }
      if let indexPath = indexPath(at: center, in: list) { return indexPath }
    }
    return nil
  }

  private func indexPath(at center: CGPoint, in list: UICollectionView) -> IndexPath? {
    var containing: [(indexPath: IndexPath, area: CGFloat)] = []
    var nearest: (indexPath: IndexPath, distance: CGFloat)?
    for indexPath in list.indexPathsForVisibleItems {
      guard let cell = list.cellForItem(at: indexPath) else { continue }
      let frame = cell.convert(cell.bounds, to: window)
      if frame.contains(center) {
        containing.append((indexPath, rectArea(frame)))
      }
      let slop = frame.insetBy(dx: -Recognition.rowHitSlop, dy: -Recognition.rowHitSlop)
      guard slop.contains(center) else { continue }
      let distance = abs(frame.midY - center.y)
      if nearest == nil || distance < nearest?.distance ?? .greatestFiniteMagnitude {
        nearest = (indexPath, distance)
      }
    }
    if let match = containing.min(by: { $0.area < $1.area }) {
      return match.indexPath
    }
    return nearest?.indexPath
  }

  func titleIsVisible(_ title: String, in list: UICollectionView) -> Bool {
    matchingRect(in: list, target: title) != nil || windowRect(matching: title, inside: list) != nil
  }

  private func windowRect(matching title: String, inside list: UICollectionView) -> CGRect? {
    let listFrame = list.convert(list.bounds, to: window)
    return windowRects(matching: title)
      .filter { rect in
        let center = CGPoint(x: rect.midX, y: rect.midY)
        return listFrame.contains(center) && !tabBarContains(center)
      }
      .min { rectArea($0) < rectArea($1) }
  }

  private func tabBarContains(_ point: CGPoint) -> Bool {
    tabBars().contains { bar in
      viewIsVisible(bar) && bar.convert(bar.bounds, to: window).contains(point)
    }
  }

  private func navigationTitleIsShown(_ title: String) -> Bool {
    navigationBars().contains { bar in
      guard viewIsVisible(bar) else { return false }
      let drawn = labels(in: bar).contains { matchesLabel($0, target: title) }
      return drawn || bar.topItem?.title == title
    }
  }

  private func textIsDrawn(_ text: String) -> Bool {
    if labels(in: window).contains(where: { matchesLabel($0, target: text) }) { return true }
    if visibleCollectionViews().contains(where: { matchingRect(in: $0, target: text) != nil }) { return true }
    if matchingRect(in: window, target: text) != nil { return true }
    return visibleCollectionViews().contains { listShowsWhenScrolled(text, in: $0) }
  }

  func buttons(in view: UIView?) -> [UIButton] {
    guard let view else { return [] }
    return ((view as? UIButton).map { [$0] } ?? []) + view.subviews.flatMap { buttons(in: $0) }
  }

  func visibleCollectionViews() -> [UICollectionView] {
    descendants(of: window, UICollectionView.self).filter { list in
      viewIsVisible(list) && !isInsideTabBar(list)
    }
  }

  private func isInsideTabBar(_ view: UIView) -> Bool {
    var ancestor = view.superview
    while let current = ancestor {
      if current is UITabBar { return true }
      ancestor = current.superview
    }
    return false
  }

  func navigationBars() -> [UINavigationBar] {
    descendants(of: window, UINavigationBar.self)
  }

  func navigationControllers() -> [UINavigationController] {
    viewControllers(UINavigationController.self)
  }

  func splitControllers() -> [UISplitViewController] {
    viewControllers(UISplitViewController.self)
  }

  private func tabBars() -> [UITabBar] {
    descendants(of: window, UITabBar.self)
  }

  func searchBars() -> [UISearchBar] {
    descendants(of: window, UISearchBar.self)
  }

  private func viewControllers<T: UIViewController>(_ type: T.Type) -> [T] {
    guard let root = window.rootViewController else { return [] }
    var result: [T] = []
    var seen = Set<ObjectIdentifier>()
    func walk(_ viewController: UIViewController) {
      let id = ObjectIdentifier(viewController)
      guard seen.insert(id).inserted else { return }
      if let typed = viewController as? T {
        result.append(typed)
      }
      if let nav = viewController as? UINavigationController {
        nav.viewControllers.forEach(walk)
      }
      viewController.children.forEach(walk)
    }
    walk(root)
    return result
  }

  private func descendants<T: UIView>(of view: UIView, _ type: T.Type) -> [T] {
    ((view as? T).map { [$0] } ?? []) + view.subviews.flatMap { descendants(of: $0, type) }
  }

  func viewIsVisible(_ view: UIView) -> Bool {
    guard view.window === window, !view.isHidden, view.alpha > 0, !view.bounds.isEmpty else { return false }
    guard view.convert(view.bounds, to: window).intersects(window.bounds) else { return false }
    var ancestor = view.superview
    while let current = ancestor {
      if current.isHidden || current.alpha == 0 { return false }
      ancestor = current.superview
    }
    return true
  }
}
