@testable import MC1
import SwiftUI
import Testing
import UIKit

/// Hosts `ExpandableSettingsSection`. The load is not a child of the view task.
@Suite("Expandable settings section load", .serialized)
@MainActor
struct ExpandableSettingsSectionTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
  private static let collapsedSettle = Duration.milliseconds(200)

  @Test
  func `host removal does not cancel a model-owned load`() async throws {
    let probe = SectionLoadProbe(isExpanded: true)
    let host = mount(probe)
    try await waitUntil("expanded section never started onLoad") { probe.started == 1 }
    host.tearDown()
    probe.finish(ticket: 1, value: "kept")
    try await waitUntil("reply should apply after the host is gone") { probe.applied == "kept" }
  }

  @Test
  func `collapse does not cancel a model-owned load`() async throws {
    let probe = SectionLoadProbe(isExpanded: true)
    let host = mount(probe)
    try await waitUntil("expanded section never started onLoad") { probe.started == 1 }
    probe.isExpanded = false
    try await Task.sleep(for: Self.collapsedSettle)
    probe.finish(ticket: 1, value: "kept")
    try await waitUntil("reply should apply after collapse") { probe.applied == "kept" }
    host.tearDown()
  }

  @Test
  func `a second expand while loading or loaded does not send again`() async throws {
    let probe = SectionLoadProbe(isExpanded: true)
    let host = mount(probe)
    try await waitUntil("first expand should load") { probe.started == 1 }

    probe.isExpanded = false
    try await Task.sleep(for: Self.collapsedSettle)
    probe.isExpanded = true
    try await Task.sleep(for: Self.collapsedSettle)
    #expect(probe.started == 1)

    probe.finish(ticket: 1, value: "kept")
    probe.loaded = true
    try await waitUntil("first reply should apply") { probe.applied == "kept" }
    probe.isExpanded = false
    try await Task.sleep(for: Self.collapsedSettle)
    probe.isExpanded = true
    try await Task.sleep(for: Self.collapsedSettle)
    #expect(probe.started == 1)
    host.tearDown()
  }

  @Test
  func `try again bumps and the old reply does not apply`() async throws {
    let probe = SectionLoadProbe(isExpanded: true, hasError: true)
    let host = mount(probe)
    try await waitUntil("first load should start") { probe.started == 1 }
    let retry = Task { await probe.load() }
    try await waitUntil("try again should bump") { probe.started == 2 }
    probe.finish(ticket: 1, value: "stale")
    try await Task.sleep(for: .milliseconds(40))
    #expect(probe.applied == nil)
    probe.finish(ticket: 2, value: "fresh")
    await retry.value
    #expect(probe.applied == "fresh")
    host.tearDown()
  }

  @Test
  func `collapsed section does not load`() async throws {
    let probe = SectionLoadProbe(isExpanded: false)
    let host = mount(probe)
    defer { host.tearDown() }
    try await Task.sleep(for: Self.collapsedSettle)
    #expect(probe.started == 0)
  }

  private struct SectionHarness: View {
    @Bindable var probe: SectionLoadProbe

    var body: some View {
      Form {
        ExpandableSettingsSection(
          title: "Section",
          icon: "info.circle",
          isExpanded: $probe.isExpanded,
          isLoaded: { probe.loaded },
          isLoading: $probe.isLoading,
          hasError: $probe.hasError,
          onLoad: { await probe.load() }
        ) {
          Text(verbatim: "body")
        }
      }
    }
  }

  @MainActor
  private final class SectionWindowHost {
    let window: UIWindow
    var controller: UIHostingController<SectionHarness>?

    init(window: UIWindow, controller: UIHostingController<SectionHarness>) {
      self.window = window
      self.controller = controller
    }

    func tearDown() {
      window.rootViewController = nil
      window.isHidden = true
      controller = nil
    }
  }

  private func mount(_ probe: SectionLoadProbe) -> SectionWindowHost {
    let controller = UIHostingController(rootView: SectionHarness(probe: probe))
    let window = UIWindow(frame: Self.viewport)
    window.rootViewController = controller
    window.isHidden = false
    window.layoutIfNeeded()
    return SectionWindowHost(window: window, controller: controller)
  }
}

@Observable
@MainActor
private final class SectionLoadProbe {
  var isExpanded: Bool
  var isLoading = false
  var hasError: Bool
  var loaded = false
  private(set) var started = 0
  private(set) var applied: String?
  private var generation = 0
  private var waiters: [Int: CheckedContinuation<String, Never>] = [:]

  init(isExpanded: Bool, hasError: Bool = false) {
    self.isExpanded = isExpanded
    self.hasError = hasError
  }

  func load() async {
    generation += 1
    let ticket = generation
    started += 1
    isLoading = true
    let value = await withCheckedContinuation { (continuation: CheckedContinuation<String, Never>) in
      waiters[ticket] = continuation
    }
    isLoading = false
    guard ticket == generation else { return }
    applied = value
  }

  func finish(ticket: Int, value: String) {
    waiters[ticket]?.resume(returning: value)
    waiters[ticket] = nil
  }
}
