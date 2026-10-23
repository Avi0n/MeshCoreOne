@testable import MC1
import SwiftUI
import Testing
import UIKit

/// Hosts production `GlassFilterBar` and walks the public accessibility tree.
@Suite("Glass filter bar accessibility", .serialized, .timeLimit(.minutes(1)))
@MainActor
struct GlassFilterBarTests {
  private enum Layout {
    static let viewport = CGRect(x: 0, y: 0, width: 390, height: 160)
    static let settle = Duration.milliseconds(500)
  }

  @Test
  func `exactly one option exposes the selected trait`() async throws {
    let probe = FilterProbe(selection: .alpha)
    let host = try mount(probe, size: .regular)
    defer { host.tearDown() }
    try await settle(host)
    let selected = try optionNodes(in: host.window).filter { $0.traits.contains(.selected) }
    #expect(selected.map(\.label) == [ProbeFilter.alpha.title])
  }

  @Test
  func `the bar group keeps each option as its own button`() async throws {
    let probe = FilterProbe()
    let host = try mount(probe, size: .regular)
    defer { host.tearDown() }
    try await settle(host)
    let nodes = accessNodes(in: host.window)
    let groups = nodes.filter { $0.label == L10n.Chats.Chats.Filter.title && $0.childCount > 0 }
    let options = try optionNodes(in: nodes)
    #expect(groups.count == 1)
    #expect(options.count == ProbeFilter.allCases.count)
    #expect(options.allSatisfy { $0.traits.contains(.button) })
    #expect(Set(options.map(\.label)) == Set(ProbeFilter.allCases.map(\.title)))
  }

  @Test
  func `activation moves the selection and the selected trait`() async throws {
    let probe = FilterProbe(selection: .alpha)
    let host = try mount(probe, size: .regular)
    defer { host.tearDown() }
    try await settle(host)
    let bravo = try #require(optionNodes(in: host.window).first { $0.label == ProbeFilter.bravo.title })
    #expect(bravo.object.accessibilityActivate())
    try await settle(host)
    #expect(probe.selection == .bravo)
    let selected = try optionNodes(in: host.window).filter { $0.traits.contains(.selected) }
    #expect(selected.map(\.label) == [ProbeFilter.bravo.title])
  }

  @Test
  func `disabled activation does not change the selection`() async throws {
    let probe = FilterProbe(selection: .alpha, isSearching: true)
    let host = try mount(probe, size: .regular)
    defer { host.tearDown() }
    try await settle(host)
    let options = try optionNodes(in: host.window)
    #expect(options.allSatisfy { $0.traits.contains(.notEnabled) })
    let bravo = try #require(options.first { $0.label == ProbeFilter.bravo.title })
    _ = bravo.object.accessibilityActivate()
    try await settle(host)
    #expect(probe.selection == .alpha)
  }

  @Test
  func `selection leaves pill frames unchanged and adds no accessible element`() async throws {
    for size in [GlassFilterBar<ProbeFilter>.Size.regular, .large] {
      let probe = FilterProbe(selection: .alpha)
      let host = try mount(probe, size: size)
      defer { host.tearDown() }
      try await settle(host)
      let before = accessNodes(in: host.window)
      let beforeOptions = try optionNodes(in: before)
      let beforeElements = before.filter(\.isElement)
      probe.selection = .bravo
      try await settle(host)
      let after = accessNodes(in: host.window)
      let afterOptions = try optionNodes(in: after)
      #expect(after.filter(\.isElement).count == beforeElements.count)
      #expect(afterOptions.count == beforeOptions.count)
      for filter in ProbeFilter.allCases {
        let earlier = try #require(beforeOptions.first { $0.label == filter.title })
        let later = try #require(afterOptions.first { $0.label == filter.title })
        #expect(earlier.frame.width > 1)
        #expect(earlier.frame.height > 1)
        #expect(framesMatch(earlier.frame, later.frame, scale: host.window.screen.scale))
      }
    }
  }

  private func mount(_ probe: FilterProbe, size: GlassFilterBar<ProbeFilter>.Size) throws -> FilterWindowHost {
    let controller = UIHostingController(rootView: FilterHarness(probe: probe, size: size))
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let window = UIWindow(windowScene: scene)
    window.frame = Layout.viewport
    window.rootViewController = controller
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return FilterWindowHost(window: window)
  }

  private func settle(_ host: FilterWindowHost) async throws {
    host.window.layoutIfNeeded()
    try await Task.sleep(for: Layout.settle)
    host.window.layoutIfNeeded()
  }

  private func optionNodes(in window: UIWindow) throws -> [AccessNode] {
    let nodes = accessNodes(in: window)
    let options = try optionNodes(in: nodes)
    if options.count != ProbeFilter.allCases.count {
      Issue.record("Expected \(ProbeFilter.allCases.count) options, found \(describe(nodes))")
    }
    return options
  }

  private func optionNodes(in nodes: [AccessNode]) throws -> [AccessNode] {
    let titles = Set(ProbeFilter.allCases.map(\.title))
    return nodes.filter { titles.contains($0.label) && $0.isElement }
  }

  private func framesMatch(_ lhs: CGRect, _ rhs: CGRect, scale: CGFloat) -> Bool {
    let tolerance = 1 / max(scale, 1)
    return abs(lhs.minX - rhs.minX) <= tolerance
      && abs(lhs.minY - rhs.minY) <= tolerance
      && abs(lhs.width - rhs.width) <= tolerance
      && abs(lhs.height - rhs.height) <= tolerance
  }

  private func describe(_ nodes: [AccessNode]) -> String {
    nodes.map { node in
      "[\(node.label) traits=\(node.traits.rawValue) element=\(node.isElement) children=\(node.childCount)]"
    }.joined(separator: " ")
  }
}

@Observable
@MainActor
private final class FilterProbe {
  var selection: ProbeFilter
  var isSearching: Bool

  init(selection: ProbeFilter = .alpha, isSearching: Bool = false) {
    self.selection = selection
    self.isSearching = isSearching
  }
}

private enum ProbeFilter: String, CaseIterable, Hashable, Sendable {
  case alpha
  case bravo
  case charlie

  var title: String {
    switch self {
    case .alpha: "Alpha Filter"
    case .bravo: "Bravo Filter"
    case .charlie: "Charlie Filter"
    }
  }
}

private struct FilterHarness: View {
  @Bindable var probe: FilterProbe
  let size: GlassFilterBar<ProbeFilter>.Size

  var body: some View {
    GlassFilterBar(
      selection: $probe.selection,
      isSearching: probe.isSearching,
      pickerLabel: L10n.Chats.Chats.Filter.title,
      title: { $0.title },
      size: size
    )
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
  }
}

@MainActor
private final class FilterWindowHost {
  let window: UIWindow

  init(window: UIWindow) {
    self.window = window
  }

  func tearDown() {
    window.rootViewController = nil
    window.isHidden = true
    window.windowScene = nil
  }
}

private struct AccessNode {
  let object: NSObject
  let label: String
  let traits: UIAccessibilityTraits
  let frame: CGRect
  let isElement: Bool
  let childCount: Int
}

@MainActor
private func accessNodes(in root: NSObject) -> [AccessNode] {
  var nodes: [AccessNode] = []
  var seen = Set<ObjectIdentifier>()

  func visit(_ object: NSObject) {
    guard seen.insert(ObjectIdentifier(object)).inserted else { return }
    if let view = object as? UIView, view.accessibilityElementsHidden || view.isHidden { return }

    let rawCount = object.accessibilityElementCount()
    let childCount = rawCount == NSNotFound ? 0 : max(rawCount, 0)
    let label = object.accessibilityLabel ?? ""
    if object.isAccessibilityElement || (!label.isEmpty && childCount > 0) {
      nodes.append(AccessNode(
        object: object,
        label: label,
        traits: object.accessibilityTraits,
        frame: object.accessibilityFrame,
        isElement: object.isAccessibilityElement,
        childCount: childCount
      ))
    }

    if childCount > 0 {
      for index in 0..<childCount {
        guard let child = object.accessibilityElement(at: index) as? NSObject, child !== object else { continue }
        visit(child)
      }
      return
    }

    if let elements = object.accessibilityElements, !elements.isEmpty {
      for case let child as NSObject in elements where child !== object {
        visit(child)
      }
      return
    }

    guard let view = object as? UIView else { return }
    for subview in view.subviews where subview.alpha > 0 {
      visit(subview)
    }
  }

  visit(root)
  return nodes
}
