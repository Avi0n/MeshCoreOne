import Foundation
import MapKit
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Nodes retained history state")
@MainActor
struct NodesHistoryStateTests {
  private static let viewportTolerance = 0.001
  private let publicKey = Data(repeating: 0xAB, count: 32)

  private func snapshot() -> NodeStatusSnapshotDTO {
    NodeStatusSnapshotDTO(
      nodePublicKey: publicKey, latitude: 37.7, longitude: -122.4
    )
  }

  @Test
  func `Map renderer replacement retains pin identity report and last retiring viewport`() throws {
    let report = snapshot()
    let state = NodeLocationMapState(snapshots: [report], initialSelectedReportID: report.id)
    let pointID = try #require(state.selectedPointID)
    let firstRenderer = UUID()
    let nextRenderer = UUID()
    let firstViewport = MapViewportSnapshot(
      latitude: 37.8, longitude: -122.3, zoom: 12, bearing: 40, pitch: 30
    )
    let finalViewport = MapViewportSnapshot(
      latitude: 37.9, longitude: -122.2, zoom: 13, bearing: 45, pitch: 35
    )

    state.prepareRenderer(firstRenderer)
    state.fitInitially()
    state.viewportReady(renderer: firstRenderer)
    state.captureViewport(firstViewport, renderer: firstRenderer)
    let firstFitVersion = state.cameraRegionVersion
    state.invalidateRenderer()
    state.captureViewport(finalViewport, renderer: firstRenderer)
    state.selectReport(pointID: nil, renderer: firstRenderer)

    #expect(state.viewport == finalViewport)
    #expect(state.selectedReportID == report.id)
    state.prepareRenderer(nextRenderer)
    state.viewportReady(renderer: nextRenderer)
    state.fitInitially()
    state.captureViewport(firstViewport, renderer: firstRenderer)
    state.selectReport(pointID: nil, renderer: firstRenderer)

    #expect(state.viewport == finalViewport)
    #expect(state.selectedPointID == pointID)
    #expect(state.points.first?.id == pointID)
    #expect(state.cameraRegionVersion == firstFitVersion)
  }

  @Test
  func `History and map restore a mounted renderer after a transient overlap ends`() throws {
    let current = UUID()
    let transient = UUID()
    let history = TelemetryHistoryOverviewState(history: .init(publicKey: publicKey, radioID: UUID()))
    history.prepareRenderer(revision: 1)
    history.prepareRenderer(current, revision: 1)
    history.prepareRenderer(transient, revision: 1)
    history.invalidateRenderer(transient, revision: 1)
    history.recordVisiblePosition(.sensors, renderer: current)
    #expect(history.visibleScrollID == .sensors)

    let report = snapshot()
    let map = NodeLocationMapState(snapshots: [report], initialSelectedReportID: nil)
    let point = try #require(map.points.first)
    map.prepareRenderer(revision: 1)
    map.prepareRenderer(current, revision: 1)
    map.prepareRenderer(transient, revision: 1)
    map.invalidateRenderer(transient, revision: 1)
    map.selectReport(pointID: point.id, renderer: current)
    #expect(map.selectedReportID == report.id)

    let replacement = UUID()
    history.prepareRenderer(revision: 2)
    history.prepareRenderer(replacement, revision: 2)
    history.prepareRenderer(current, revision: 1)
    history.invalidateRenderer(current, revision: 1)
    history.recordVisiblePosition(.neighbors, renderer: replacement)
    #expect(history.visibleScrollID == .neighbors)
    map.prepareRenderer(revision: 2)
    map.prepareRenderer(replacement, revision: 2)
    map.prepareRenderer(current, revision: 1)
    map.invalidateRenderer(current, revision: 1)
    #expect(map.rendererIsCurrent(replacement))
    history.end()
    map.end()
  }

  @Test
  func `Cleared report selection stays cleared after a renderer is replaced`() {
    let report = snapshot()
    let state = NodeLocationMapState(snapshots: [report], initialSelectedReportID: report.id)
    let renderer = UUID()
    state.prepareRenderer(renderer)
    state.selectReport(pointID: nil, renderer: renderer)
    state.invalidateRenderer()
    state.prepareRenderer(UUID())
    state.fitInitially()

    #expect(state.selectedReportID == nil)
    #expect(state.selectedPointID == nil)
  }

  @Test
  func `Ended map ignores all camera and selection callbacks`() throws {
    let report = snapshot()
    let state = NodeLocationMapState(snapshots: [report], initialSelectedReportID: nil)
    let renderer = UUID()
    let pointID = try #require(state.points.first?.id)
    state.prepareRenderer(renderer)
    state.viewportReady(renderer: renderer)
    state.end()
    state.prepareRenderer(renderer)
    state.viewportReady(renderer: renderer)
    state.selectReport(pointID: pointID, renderer: renderer)
    state.captureViewport(
      MapViewportSnapshot(latitude: 1, longitude: 2, zoom: 3, bearing: 4, pitch: 5),
      renderer: renderer
    )
    state.fitInitially()

    #expect(state.selectedReportID == nil)
    #expect(state.viewport == nil)
    #expect(state.cameraRegionVersion == 0)
  }

  @Test
  func `History rejects old renderer scroll updates and falls back from a removed report`() {
    let history = ContactRoute.TelemetryHistory(publicKey: publicKey, radioID: UUID())
    let state = TelemetryHistoryOverviewState(history: history)
    let oldRenderer = UUID()
    let newRenderer = UUID()
    let report = snapshot()
    state.prepareRenderer(oldRenderer)
    state.recordVisiblePosition(.report(report.id), renderer: oldRenderer)
    state.invalidateRenderer()
    state.prepareRenderer(newRenderer)
    state.recordVisiblePosition(.timeRange, renderer: oldRenderer)

    #expect(state.visibleScrollID == .report(report.id))
    #expect(state.restoredPosition(in: [report]) == .report(report.id))
    #expect(state.restoredPosition(in: []) == .location)
    state.end()
    state.recordVisiblePosition(.timeRange, renderer: newRenderer)
    #expect(state.visibleScrollID == .report(report.id))
  }

  @Test
  func `first user camera movement is captured without a region command`() {
    let coordinator = MC1MapView.Coordinator()
    var captured: MapViewportSnapshot?
    coordinator.viewportSnapshotProvider = { nil }
    coordinator.onViewportCapture = { captured = $0 }
    coordinator.mapView(coordinator.mapView, regionDidChangeWith: [], animated: false)
    #expect(captured == nil)
    coordinator.mapView(coordinator.mapView, regionDidChangeWith: .gesturePan, animated: false)
    #expect(captured == nil)

    coordinator.isStyleLoaded = true
    coordinator.mapView(coordinator.mapView, regionDidChangeWith: .gesturePan, animated: false)
    #expect(captured != nil)
    #expect(captured?.appliedCameraCommandVersion == 0)
    coordinator.pendingRegionTask?.cancel()
  }

  @Test
  func `Retained viewport restores only after style readiness and consumes prior region command`() {
    let coordinator = MC1MapView.Coordinator()
    let saved = MapViewportSnapshot(
      latitude: 37.8, longitude: -122.3, zoom: 12, bearing: 40, pitch: 30,
      appliedCameraCommandVersion: 7
    )
    var readyCount = 0
    var captured: MapViewportSnapshot?
    coordinator.viewportSnapshotProvider = { saved }
    coordinator.onViewportReady = { readyCount += 1 }
    coordinator.onViewportCapture = { captured = $0 }
    coordinator.restoreViewportIfNeeded()

    #expect(readyCount == 0)
    #expect(captured == nil)
    #expect(coordinator.lastAppliedRegionVersion == 0)
    coordinator.isStyleLoaded = true
    coordinator.restoreViewportIfNeeded()
    let restored = MapViewportSnapshot(mapView: coordinator.mapView)

    #expect(readyCount == 1)
    #expect(coordinator.lastAppliedRegionVersion == 7)
    #expect(captured?.appliedCameraCommandVersion == 7)
    #expect(abs(restored.latitude - saved.latitude) < Self.viewportTolerance)
    #expect(abs(restored.longitude - saved.longitude) < Self.viewportTolerance)
    #expect(abs(restored.zoom - saved.zoom) < Self.viewportTolerance)
    #expect(abs(restored.bearing - saved.bearing) < Self.viewportTolerance)
    #expect(abs(restored.pitch - saved.pitch) < Self.viewportTolerance)

    let newSaved = MapViewportSnapshot(latitude: 1, longitude: 2, zoom: 3, bearing: 4, pitch: 5)
    coordinator.viewportSnapshotProvider = { newSaved }
    coordinator.restoreViewportIfNeeded()
    #expect(MapViewportSnapshot(mapView: coordinator.mapView) == restored)
    coordinator.isDismantled = true
    coordinator.captureViewport()
    #expect(readyCount == 1)
  }

  @Test
  func `A pending camera command still applies after restoring an older captured viewport`() {
    let saved = MapViewportSnapshot(
      latitude: 37.8, longitude: -122.3, zoom: 12, bearing: 40, pitch: 30,
      appliedCameraCommandVersion: 7
    )
    let command = MKCoordinateRegion(
      center: CLLocationCoordinate2D(latitude: 38.5, longitude: -121.5),
      span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    )
    let coordinator = MC1MapView.Coordinator()
    var captured: MapViewportSnapshot?
    coordinator.mapView.frame = CGRect(x: 0, y: 0, width: 400, height: 800)
    coordinator.viewportSnapshotProvider = { saved }
    coordinator.onViewportCapture = { captured = $0 }
    coordinator.isStyleLoaded = true
    coordinator.restoreViewportIfNeeded()

    let map = MC1MapView(
      points: [], lines: [], mapStyle: .standard,
      showLabels: false, showsUserLocation: false, isInteractive: true, showsScale: false,
      cameraRegion: .constant(command), cameraRegionVersion: 8,
      viewportSnapshotProvider: { saved },
      onPointTap: nil, onMapTap: nil, onCameraRegionChange: nil
    )
    map.updateCameraRegion(in: coordinator.mapView, coordinator: coordinator)

    #expect(coordinator.lastAppliedRegionVersion == 8)
    #expect(captured?.appliedCameraCommandVersion == 8)
    let afterCommand = MapViewportSnapshot(mapView: coordinator.mapView)
    #expect(abs(afterCommand.latitude - command.center.latitude) < Self.viewportTolerance)
    #expect(abs(afterCommand.longitude - command.center.longitude) < Self.viewportTolerance)
    #expect(afterCommand.latitude != saved.latitude)

    let oldCommand = MC1MapView(
      points: [], lines: [], mapStyle: .standard,
      showLabels: false, showsUserLocation: false, isInteractive: true, showsScale: false,
      cameraRegion: .constant(command), cameraRegionVersion: 7,
      viewportSnapshotProvider: { saved },
      onPointTap: nil, onMapTap: nil, onCameraRegionChange: nil
    )
    let freshCoordinator = MC1MapView.Coordinator()
    freshCoordinator.viewportSnapshotProvider = { saved }
    freshCoordinator.isStyleLoaded = true
    freshCoordinator.restoreViewportIfNeeded()
    oldCommand.updateCameraRegion(in: freshCoordinator.mapView, coordinator: freshCoordinator)
    let retained = MapViewportSnapshot(mapView: freshCoordinator.mapView)
    #expect(abs(retained.latitude - saved.latitude) < Self.viewportTolerance)
    #expect(abs(retained.longitude - saved.longitude) < Self.viewportTolerance)
    #expect(abs(retained.zoom - saved.zoom) < Self.viewportTolerance)
  }

  @Test(arguments: [false, true])
  func `suspended history loading follows the entry lifetime`(standaloneExit: Bool) async {
    let state = TelemetryHistoryOverviewState(history: .init(publicKey: publicKey, radioID: UUID()))
    defer { state.end() }
    let report = snapshot()
    let started = AsyncStream<Void>.makeStream()
    let completed = AsyncStream<Void>.makeStream()
    var pendingSnapshots: CheckedContinuation<[NodeStatusSnapshotDTO], Never>?
    var remainingFetchCount = 0
    let dependencies = TelemetryHistoryOverviewViewModel.LoadDependencies(
      snapshots: {
        await withCheckedContinuation { continuation in
          pendingSnapshots = continuation
          started.continuation.yield(())
        }
      },
      contact: { remainingFetchCount += 1; return nil },
      contacts: { remainingFetchCount += 1; return [] },
      discoveredNodes: { remainingFetchCount += 1; return [] }
    )
    state.startLoading {
      await state.viewModel.loadData(dependencies: dependencies)
      completed.continuation.yield(())
    }
    var startIterator = started.stream.makeAsyncIterator()
    await startIterator.next()
    if standaloneExit { state.cancelLoading() } else { state.invalidateRenderer() }
    pendingSnapshots?.resume(returning: [report])
    var completionIterator = completed.stream.makeAsyncIterator()
    await completionIterator.next()

    #expect(remainingFetchCount == (standaloneExit ? 0 : 3))
    #expect(state.hasLoaded == !standaloneExit)
    #expect(state.viewModel.snapshots.map(\.id) == (standaloneExit ? [] : [report.id]))
    guard standaloneExit else { return }

    let returned = AsyncStream<Void>.makeStream()
    var returnLoadCount = 0
    let resumed = TelemetryHistoryOverviewViewModel.LoadDependencies(
      snapshots: { returnLoadCount += 1; return [report] },
      contact: { nil }, contacts: { [] }, discoveredNodes: { [] }
    )
    state.startLoading {
      await state.viewModel.loadData(dependencies: resumed)
      returned.continuation.yield(())
    }
    var returnIterator = returned.stream.makeAsyncIterator()
    await returnIterator.next()
    state.startLoading { returnLoadCount += 1 }

    #expect(state.hasLoaded)
    #expect(state.viewModel.snapshots.map(\.id) == [report.id])
    #expect(returnLoadCount == 1)
  }

  @Test
  func `An unstarted cancelled load cannot invalidate its replacement`() async {
    let state = TelemetryHistoryOverviewState(history: .init(publicKey: publicKey, radioID: UUID()))
    let report = snapshot()
    let completed = AsyncStream<Void>.makeStream()
    var obsoleteLoadCount = 0
    state.startLoading { obsoleteLoadCount += 1 }
    state.cancelLoading()
    let replacement = TelemetryHistoryOverviewViewModel.LoadDependencies(
      snapshots: { [report] }, contact: { nil }, contacts: { [] }, discoveredNodes: { [] }
    )
    state.startLoading {
      await state.viewModel.loadData(dependencies: replacement)
      completed.continuation.yield(())
    }
    var iterator = completed.stream.makeAsyncIterator()
    await iterator.next()

    #expect(obsoleteLoadCount == 0)
    #expect(state.hasLoaded)
    #expect(state.viewModel.snapshots.map(\.id) == [report.id])
  }

  @Test
  func `Cancelled history reload leaves the previously loaded data intact`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    _ = try await store.saveNodeStatusSnapshot(
      nodePublicKey: publicKey, batteryMillivolts: 3800,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil, uptimeSeconds: nil,
      rxAirtimeSeconds: nil, packetsSent: nil, packetsReceived: nil, receiveErrors: nil
    )
    let model = TelemetryHistoryOverviewViewModel()
    let radioID = UUID()
    await model.loadData(dataStore: store, publicKey: publicKey, radioID: radioID)
    let original = model.snapshots.map(\.id)
    _ = try await store.saveNodeStatusSnapshot(
      nodePublicKey: publicKey, batteryMillivolts: 3700,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil, uptimeSeconds: nil,
      rxAirtimeSeconds: nil, packetsSent: nil, packetsReceived: nil, receiveErrors: nil
    )
    let reload = Task {
      await model.loadData(dataStore: store, publicKey: publicKey, radioID: radioID)
    }
    reload.cancel()
    await reload.value

    #expect(model.snapshots.map(\.id) == original)
  }
}
