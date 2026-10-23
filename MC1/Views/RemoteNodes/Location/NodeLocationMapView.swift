import MC1Services
import SwiftUI

/// Renders retained map state so viewport and report selection survive remounting.
struct NodeLocationMapView: View {
  @Environment(\.appState) private var appState
  @State private var state: NodeLocationMapState
  @State private var rendererID = UUID()
  private let rendererRevision: Int

  init(
    points: [MapPoint], lines: [MapLine],
    reports: [UUID: LocationPathMapBuilder.LocationReport], title: String,
    initialSelectionID: UUID? = nil
  ) {
    _state = State(initialValue: NodeLocationMapState(
      points: points, lines: lines, reports: reports, title: title,
      initialSelectedReportID: initialSelectionID
    ))
    rendererRevision = 0
  }

  init(snapshots: [NodeStatusSnapshotDTO], initialSelectionID: UUID?) {
    _state = State(initialValue: NodeLocationMapState(
      snapshots: snapshots, initialSelectedReportID: initialSelectionID
    ))
    rendererRevision = 0
  }

  init(state: NodeLocationMapState, rendererRevision: Int = 0) {
    _state = State(initialValue: state)
    self.rendererRevision = rendererRevision
  }

  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapClusteringEnabled.rawValue)
  private var clusteringEnabled = AppStorageKey.defaultMapClusteringEnabled
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked

  @State private var isStyleLoaded = false
  @State private var selectedReport: SelectedReport?
  @State private var selectedPointScreenPosition: CGPoint?
  @State private var selectionRequestVersion = 0

  var body: some View {
    @Bindable var state = state
    ZStack(alignment: .bottom) {
      MC1MapView(
        points: state.points,
        lines: state.lines,
        mapStyle: mapStyleSelection,
        isOffline: !appState.offlineMapService.isNetworkAvailable,
        showLabels: showLabels,
        clusteringEnabled: clusteringEnabled,
        showsUserLocation: true,
        isInteractive: true,
        showsScale: true,
        isNorthLocked: isNorthLocked,
        cameraRegion: $state.cameraRegion,
        cameraRegionVersion: state.cameraRegionVersion,
        cameraBottomSheetFraction: 0,
        selectionRequestID: state.selectedPointID,
        selectionRequestVersion: selectionRequestVersion,
        viewportSnapshotProvider: { state.viewport },
        onViewportReady: {
          guard isCurrentRenderer else { return }
          state.viewportReady(renderer: rendererID)
        },
        onViewportCapture: { state.captureViewport($0, renderer: rendererID) },
        onPointTap: { point, screenPosition in
          guard isCurrentRenderer, let report = state.reports[point.id] else { return }
          state.selectReport(pointID: point.id, renderer: rendererID)
          selectedReport = SelectedReport(id: point.id, report: report)
          selectedPointScreenPosition = screenPosition
        },
        onSelectionProjection: { point, screenPosition in
          guard isCurrentRenderer, state.selectedPointID == point.id,
                let report = state.reports[point.id] else { return }
          selectedReport = SelectedReport(id: point.id, report: report)
          selectedPointScreenPosition = screenPosition
        },
        onMapTap: { _ in clearCallout() },
        onCameraRegionChange: { region in
          guard isCurrentRenderer else { return }
          state.cameraRegion = region
          if state.selectedReportID != nil { clearCallout() }
        },
        isStyleLoaded: $isStyleLoaded,
        isCenteredOnUser: centeredOnUserBinding
      )
      // Popover attached before ignoresSafeArea, matching the main map (MapCanvasView
      // applies ignoresSafeArea outside MapContentView's popover). When ignoresSafeArea
      // sits between the map and the popover, the anchor rect resolves in the safe-area
      // inset space while the coordinator reports full-screen coordinates, and the top
      // inset shoves the anchor down and the callout flips below the pin.
      .popover(
        item: calloutBinding,
        attachmentAnchor: .rect(.rect(CGRect(
          origin: selectedPointScreenPosition ?? .zero,
          size: CGSize(width: 1, height: 1)
        ))),
        arrowEdge: .bottom
      ) { selection in
        LocationReportCallout(report: selection.report)
          .presentationCompactAdaptation(.popover)
      }
      .ignoresSafeArea()

      toolbarOverlay
    }
    .navigationTitle(state.title)
    .navigationBarTitleDisplayMode(.inline)
    .onAppear {
      state.prepareRenderer(rendererID, revision: rendererRevision)
      guard isCurrentRenderer else { return }
      if isStyleLoaded {
        state.fitInitially()
        selectionRequestVersion += 1
      }
    }
    .onDisappear { state.invalidateRenderer(rendererID, revision: rendererRevision) }
    .onChange(of: isStyleLoaded) { _, loaded in
      guard loaded, isCurrentRenderer else { return }
      state.fitInitially()
      selectionRequestVersion += 1
    }
    .onGeometryChange(for: CGSize.self) { $0.size } action: { _ in
      guard isCurrentRenderer else { return }
      selectionRequestVersion += 1
    }
  }

  private var isCurrentRenderer: Bool {
    state.rendererRevision == rendererRevision && state.rendererIsCurrent(rendererID)
  }

  private var centeredOnUserBinding: Binding<Bool> {
    Binding(
      get: { state.isCenteredOnUser },
      set: { value in
        guard isCurrentRenderer else { return }
        state.isCenteredOnUser = value
      }
    )
  }

  private var calloutBinding: Binding<SelectedReport?> {
    Binding(
      get: { selectedReport },
      set: { value in
        selectedReport = value
        if value == nil, isCurrentRenderer {
          state.selectReport(pointID: nil, renderer: rendererID)
          selectedPointScreenPosition = nil
        }
      }
    )
  }

  /// Fixed bottom-right map controls.
  private var toolbarOverlay: some View {
    HStack {
      Spacer()
      MapControlsToolbar(
        onLocationTap: centerOnMyLocation,
        isCenteredOnUser: state.isCenteredOnUser,
        isNorthLocked: $isNorthLocked,
        showLabels: $showLabels,
        clusteringEnabled: $clusteringEnabled,
        mapStyleSelection: $mapStyleSelection,
        viewportBounds: state.cameraRegion?.toMLNCoordinateBounds()
      ) {
        centerAllButton
      }
    }
  }

  /// Re-fits the camera to the displayed pin(s). Disabled when nothing is plottable.
  private var centerAllButton: some View {
    Button(L10n.Map.Map.Controls.centerAll, systemImage: "arrow.up.left.and.arrow.down.right") {
      guard isCurrentRenderer else { return }
      state.isCenteredOnUser = false
      state.fitCameraToContent()
    }
    .mapControlButton(tint: state.points.isEmpty ? .secondary : .primary)
    .disabled(state.points.isEmpty)
  }

  private func centerOnMyLocation() {
    guard isCurrentRenderer else { return }
    state.isCenteredOnUser = appState.centerOnUserLocation { region in
      guard isCurrentRenderer else { return }
      state.cameraRegion = region
      state.cameraRegionVersion += 1
    }
  }

  private func clearCallout() {
    guard isCurrentRenderer else { return }
    state.selectReport(pointID: nil, renderer: rendererID)
    selectedReport = nil
    selectedPointScreenPosition = nil
  }

  /// A tapped report pin, wrapped for `.popover(item:)`. `id` is the pin's id, so
  /// tapping a different pin re-presents the callout.
  private struct SelectedReport: Identifiable {
    let id: UUID
    let report: LocationPathMapBuilder.LocationReport
  }
}
