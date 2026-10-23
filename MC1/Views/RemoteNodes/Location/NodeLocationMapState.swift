import CoreLocation
import MapKit
import MC1Services
import SwiftUI

@Observable
@MainActor
final class NodeLocationMapState {
  private static let singleFixSpanDelta: CLLocationDegrees = 0.05
  private static let pathBoundingPaddingMultiplier: Double = 1.3

  let presentationID = UUID()
  let points: [MapPoint]
  let lines: [MapLine]
  let reports: [UUID: LocationPathMapBuilder.LocationReport]
  let title: String
  var cameraRegion: MKCoordinateRegion?
  var cameraRegionVersion = 0
  var isCenteredOnUser = false
  private(set) var hasInitiallyFit = false
  private(set) var selectedReportID: UUID?

  @ObservationIgnored private(set) var viewport: MapViewportSnapshot?
  @ObservationIgnored private var rendererID: UUID?
  @ObservationIgnored private(set) var rendererRevision = 0
  @ObservationIgnored private var mountedRenderers: [UUID] = []
  @ObservationIgnored private var viewportRendererID: UUID?
  @ObservationIgnored private var hasEnded = false

  init(snapshots: [NodeStatusSnapshotDTO], initialSelectedReportID: UUID?) {
    let plotted = LocationPathMapBuilder.build(from: snapshots, decimatePins: false)
    points = plotted.points
    lines = plotted.lines
    reports = plotted.reports
    title = L10n.RemoteNodes.RemoteNodes.Status.locationMapTitle
    selectedReportID = initialSelectedReportID
  }

  init(
    points: [MapPoint], lines: [MapLine],
    reports: [UUID: LocationPathMapBuilder.LocationReport], title: String,
    initialSelectedReportID: UUID?
  ) {
    self.points = points
    self.lines = lines
    self.reports = reports
    self.title = title
    selectedReportID = initialSelectedReportID
  }

  var selectedPointID: UUID? {
    guard let selectedReportID else { return nil }
    return reports.first { $0.value.id == selectedReportID }?.key
  }

  func prepareRenderer(revision: Int) {
    guard !hasEnded, rendererRevision != revision else { return }
    rendererRevision = revision
    invalidateRenderer()
  }

  func prepareRenderer(_ id: UUID, revision: Int = 0) {
    guard !hasEnded, rendererRevision == revision else { return }
    mountedRenderers.removeAll { $0 == id }
    mountedRenderers.append(id)
    rendererID = id
  }

  func invalidateRenderer() {
    mountedRenderers = []
    rendererID = nil
  }

  func invalidateRenderer(_ id: UUID, revision: Int = 0) {
    guard rendererRevision == revision else { return }
    mountedRenderers.removeAll { $0 == id }
    if rendererID == id { rendererID = mountedRenderers.last }
  }

  func rendererIsCurrent(_ id: UUID) -> Bool {
    !hasEnded && rendererID == id
  }

  func viewportReady(renderer id: UUID) {
    guard rendererIsCurrent(id) else { return }
    viewportRendererID = id
  }

  func captureViewport(_ snapshot: MapViewportSnapshot, renderer id: UUID) {
    guard !hasEnded, viewportRendererID == id else { return }
    viewport = snapshot
  }

  func selectReport(pointID: UUID?, renderer id: UUID) {
    guard rendererIsCurrent(id) else { return }
    selectedReportID = pointID.flatMap { reports[$0]?.id }
  }

  func fitInitially() {
    guard !hasInitiallyFit, !hasEnded else { return }
    hasInitiallyFit = true
    fitCameraToContent()
  }

  func fitCameraToContent() {
    guard !hasEnded else { return }
    let coordinates = points.map(\.coordinate)
    if coordinates.count == 1 {
      cameraRegion = MKCoordinateRegion(
        center: coordinates[0],
        span: MKCoordinateSpan(
          latitudeDelta: Self.singleFixSpanDelta,
          longitudeDelta: Self.singleFixSpanDelta
        )
      )
    } else if let region = coordinates.boundingRegion(paddingMultiplier: Self.pathBoundingPaddingMultiplier) {
      cameraRegion = region
    } else {
      return
    }
    cameraRegionVersion += 1
  }

  func end() {
    hasEnded = true
    invalidateRenderer()
    viewportRendererID = nil
  }
}
