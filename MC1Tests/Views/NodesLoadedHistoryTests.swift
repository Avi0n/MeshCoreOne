@testable import MC1
import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Nodes loaded history continuity", .serialized)
@MainActor
struct NodesLoadedHistoryTests {
  private enum Fixture {
    static let compactSize = CGSize(width: 390, height: 844)
    static let regularSize = CGSize(width: 1024, height: 768)
    static let reportCount = 36
    static let selectedReportIndex = 12
    static let reportsSection = 4
    static let pollInterval = Duration.milliseconds(30)
    static let settleInterval = Duration.milliseconds(350)
    static let timeout = Duration.seconds(6)
    static let secondsPerDay: TimeInterval = 86400
    static let publicKey = Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize)
    static let neighborPrefix = Data(repeating: 0xBC, count: 6)
  }

  @Test(arguments: [false, true])
  func `Loaded native history retains report rows disclosures range and visible report across resizing`(
    expandsSensors: Bool
  ) async throws {
    let snapshots = makeSnapshots()
    let report = snapshots[Fixture.selectedReportIndex]
    let history = TelemetryHistoryOverviewState(history: .init(publicKey: Fixture.publicKey, radioID: UUID()))
    defer { history.end() }
    history.viewModel.timeRange = .all
    history.radioExpanded = !expandsSensors
    history.sensorsExpanded = expandsSensors
    history.neighborsExpanded = !expandsSensors
    history.visibleScrollID = .report(report.id)
    history.startLoading {
      await history.viewModel.loadData(dependencies: .init(
        snapshots: { snapshots }, contact: { nil }, contacts: { [] }, discoveredNodes: { [] }
      ))
    }
    try #require(try await waitUntil { history.hasLoaded })
    #expect(history.viewModel.snapshots == snapshots)

    let appState = AppState()
    let sidebar = ContactsSidebarState()
    defer { sidebar.end() }
    let host = try mount(HistoryHost(appState: appState, history: history, sidebar: sidebar))
    defer { dismount(host) }
    let expectedCounts = [1, expandsSensors ? 1 : 2, expandsSensors ? 2 : 1, 1, Fixture.reportCount,
                          expandsSensors ? 1 : 2, 0]
    let reportPath = IndexPath(
      item: Fixture.reportCount - Fixture.selectedReportIndex - 1, section: Fixture.reportsSection
    )

    for size in [Fixture.compactSize, Fixture.regularSize, Fixture.compactSize] {
      resize(host, to: size)
      try #require(try await waitUntil {
        host.window.layoutIfNeeded()
        guard let list = historyList(in: host) else { return false }
        return sectionCounts(in: list) == expectedCounts && reportIsVisible(at: reportPath, in: list)
      }, "\(describe(host, history: history))")
      try await Task.sleep(for: Fixture.settleInterval)
      host.window.layoutIfNeeded()
      let list = try #require(historyList(in: host))
      #expect(sectionCounts(in: list) == expectedCounts)
      #expect(reportIsVisible(at: reportPath, in: list), "\(describe(host, history: history))")
      #expect(list.cellForItem(at: reportPath) != nil)
      #expect(history.visibleScrollID == .report(report.id), "\(describe(host, history: history))")
      #expect(history.viewModel.timeRange == .all)
      #expect(history.radioExpanded == !expandsSensors)
      #expect(history.sensorsExpanded == expandsSensors)
      #expect(history.neighborsExpanded == !expandsSensors)
      #expect(history.viewModel.snapshots == snapshots)
    }
  }

  private func makeSnapshots() -> [NodeStatusSnapshotDTO] {
    let latest = Date.now
    return (0..<Fixture.reportCount).map { index in
      NodeStatusSnapshotDTO(
        timestamp: latest.addingTimeInterval(-Double(Fixture.reportCount - index - 1) * Fixture.secondsPerDay),
        nodePublicKey: Fixture.publicKey,
        batteryMillivolts: 3800,
        neighborSnapshots: [.init(publicKeyPrefix: Fixture.neighborPrefix, snr: Double(index), secondsAgo: 1)],
        telemetryEntries: [.init(channel: 1, type: "Temperature", value: Double(index))],
        latitude: 37.7 + Double(index) * 0.005,
        longitude: -122.4 + Double(index) * 0.005
      )
    }
  }

  private struct HistoryHost: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var routes = NodesNavigationState()
    let appState: AppState
    let history: TelemetryHistoryOverviewState
    let sidebar: ContactsSidebarState

    var body: some View {
      navigation
        .onChange(of: sizeClass, initial: true) { _, value in
          routes.changePresentation(to: value == .regular ? .regular : .compact) { revision in
            history.prepareRenderer(revision: revision)
            sidebar.prepareRenderer(revision: revision)
          }
        }
        .modifier(ContactsSidebarPresenter(state: sidebar))
        .environment(\.appState, appState)
    }

    @ViewBuilder
    private var navigation: some View {
      switch routes.presentation {
      case .regular:
        NavigationSplitView {
          NavigationStack {
            ContactsContentColumn(
              state: sidebar, blockedContactsRoute: .blockedContacts, rendererRevision: routes.layoutRevision
            )
          }
        } detail: {
          NavigationStack { historyContent }
        }
        .navigationSplitViewStyle(.balanced)
      case .compact:
        NavigationStack { historyContent }
      case nil:
        ProgressView()
      }
    }

    private var historyContent: some View {
      TelemetryHistoryOverviewView(state: history, rendererRevision: routes.layoutRevision)
        .id(routes.layoutRevision)
    }
  }

  private struct Host {
    let window: UIWindow
    let controller: UIViewController
  }

  private func mount(_ content: some View) throws -> Host {
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let controller = UIHostingController(rootView: content)
    let window = UIWindow(windowScene: scene)
    window.rootViewController = controller
    let host = Host(window: window, controller: controller)
    resize(host, to: Fixture.compactSize)
    window.makeKeyAndVisible()
    return host
  }

  private func resize(_ host: Host, to size: CGSize) {
    host.controller.traitOverrides.horizontalSizeClass = size == Fixture.regularSize ? .regular : .compact
    host.controller.traitOverrides.verticalSizeClass = .regular
    host.window.frame = CGRect(origin: .zero, size: size)
    host.controller.view.frame = host.window.bounds
    host.window.layoutIfNeeded()
  }

  private func dismount(_ host: Host) {
    host.window.rootViewController = nil
    host.window.isHidden = true
    host.window.windowScene = nil
  }

  private func waitUntil(_ condition: () -> Bool) async throws -> Bool {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: Fixture.timeout)
    while clock.now < deadline {
      if condition() { return true }
      try await Task.sleep(for: Fixture.pollInterval)
    }
    return condition()
  }

  private func historyList(in host: Host) -> UICollectionView? {
    let navigation = controllers(in: host.controller).compactMap { $0 as? UINavigationController }.first {
      $0.topViewController?.navigationItem.title == L10n.RemoteNodes.RemoteNodes.History.overviewTitle
        && isVisible($0.view, in: host.window)
    }
    guard let root = navigation?.topViewController?.view else { return nil }
    return descendants(in: root).compactMap { $0 as? UICollectionView }.first
  }

  private func controllers(in controller: UIViewController) -> [UIViewController] {
    [controller] + controller.children.flatMap { controllers(in: $0) }
  }

  private func descendants(in view: UIView) -> [UIView] {
    [view] + view.subviews.flatMap { descendants(in: $0) }
  }

  private func sectionCounts(in list: UICollectionView) -> [Int] {
    (0..<list.numberOfSections).map { list.numberOfItems(inSection: $0) }
  }

  private func reportIsVisible(at path: IndexPath, in list: UICollectionView) -> Bool {
    guard let cell = list.cellForItem(at: path) else { return false }
    return cell.convert(cell.bounds, to: list).intersects(list.bounds)
  }

  private func isVisible(_ view: UIView, in window: UIWindow) -> Bool {
    view.window === window && !view.isHidden && view.alpha > 0
      && view.convert(view.bounds, to: window).intersects(window.bounds)
  }

  private func describe(_ host: Host, history: TelemetryHistoryOverviewState) -> String {
    let lists = descendants(in: host.window).compactMap { $0 as? UICollectionView }.map { sectionCounts(in: $0) }
    let visible = historyList(in: host)?.indexPathsForVisibleItems.sorted().map { "\($0.section):\($0.item)" } ?? []
    return "sections=\(lists), visible=\(visible), saved=\(history.visibleScrollID)"
  }
}
