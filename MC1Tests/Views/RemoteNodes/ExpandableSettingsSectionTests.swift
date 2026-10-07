@testable import MC1
import SwiftUI
import Testing
import UIKit

/// Hosts `ExpandableSettingsSection` so leaving the window cancels the view task's `onLoad`.
@Suite("Expandable settings section load", .serialized)
@MainActor
struct ExpandableSettingsSectionTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
  private static let collapsedSettle = Duration.milliseconds(200)

  @Test
  func `expanded section load cancels when the host goes away`() async throws {
    let probe = SectionLoadProbe(isExpanded: true)
    let host = mount(probe)
    try await waitUntil("expanded section never started onLoad") { probe.started }
    host.tearDown()
    try await waitUntil("expanded onLoad did not resume cancelled") { probe.cancelled }
    #expect(probe.finishedWithoutCancellation == false)
  }

  @Test
  func `collapsed section does not load`() async throws {
    let probe = SectionLoadProbe(isExpanded: false)
    let host = mount(probe)
    defer { host.tearDown() }
    try await Task.sleep(for: Self.collapsedSettle)
    #expect(probe.started == false)
  }

  @Test
  func `expanding starts the load`() async throws {
    let probe = SectionLoadProbe(isExpanded: false)
    let host = mount(probe)
    try await Task.sleep(for: Self.collapsedSettle)
    #expect(probe.started == false)

    probe.isExpanded = true
    try await waitUntil("expanding the section never started onLoad") { probe.started }
    host.tearDown()
    try await waitUntil("expanded onLoad did not resume cancelled") { probe.cancelled }
  }

  private struct SectionHarness: View {
    @Bindable var probe: SectionLoadProbe

    var body: some View {
      Form {
        ExpandableSettingsSection(
          title: "Section",
          icon: "info.circle",
          isExpanded: $probe.isExpanded,
          isLoaded: { false },
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
  var hasError = false
  private(set) var started = false
  private(set) var cancelled = false
  private(set) var finishedWithoutCancellation = false

  init(isExpanded: Bool) {
    self.isExpanded = isExpanded
  }

  func load() async {
    started = true
    do {
      try await Task.sleep(for: .seconds(30))
      finishedWithoutCancellation = true
    } catch is CancellationError {
      cancelled = true
    } catch {
      cancelled = false
    }
  }
}
