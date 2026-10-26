import MapKit
import MC1Services
import os.log
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "TracePathMapView")

/// Map-based view for building and visualizing trace paths
struct TracePathMapView: View {
  @Environment(\.appState) private var appState
  @Bindable var traceViewModel: TracePathViewModel
  @Binding var presentedResult: TraceResult?
  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapClusteringEnabled.rawValue)
  private var clusteringEnabled = AppStorageKey.defaultMapClusteringEnabled
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @AppStorage(AppStorageKey.mapFilterTracePath.rawValue)
  private var mapFilterRaw: String = ""
  @State private var mapViewModel = TracePathMapViewModel()

  private var mapFilter: MapFilterState {
    MapFilterPreferences.state(fromRaw: mapFilterRaw, host: .tracePath)
  }

  private var mapFilterBinding: Binding<MapFilterState> {
    MapFilterPreferences.binding(raw: $mapFilterRaw, host: .tracePath)
  }

  @State private var showingSavePrompt = false
  @State private var saveName = ""
  @State private var showingClearConfirmation = false
  @State private var showingSaveSuccess = false
  @State private var errorMessage: String?
  @State private var pinTapHaptic = 0
  @State private var rejectedTapHaptic = 0
  @State private var isCenteredOnUser = false

  @Namespace private var buttonNamespace

  var body: some View {
    ZStack {
      mapContent

      // Results banner at top
      if let result = mapViewModel.result, result.success {
        PathDistanceBanner(
          hopCount: result.hops.count - 2,
          totalPathDistance: traceViewModel.totalPathDistance
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.top)
      }

      // Floating buttons
      TracePathFloatingButtonsView(
        mapViewModel: mapViewModel,
        showingClearConfirmation: $showingClearConfirmation,
        presentedResult: $presentedResult,
        buttonNamespace: buttonNamespace
      )

      // Map controls toolbar
      TracePathMapToolbarView(
        mapViewModel: mapViewModel,
        mapStyleSelection: $mapStyleSelection,
        showLabels: $showLabels,
        clusteringEnabled: $clusteringEnabled,
        isNorthLocked: $isNorthLocked,
        isCenteredOnUser: $isCenteredOnUser,
        filter: MapFilterControl(host: .tracePath, state: mapFilterBinding)
      )
    }
    .onAppear {
      MapFilterPreferences.ensureMigrated(raw: &mapFilterRaw, host: .tracePath)
      mapViewModel.configure(
        traceViewModel: traceViewModel,
        userLocation: appState.bestAvailableLocation
      )
      mapViewModel.showLabels = showLabels
      mapViewModel.applyFilter(mapFilter)
      mapViewModel.rebuildOverlays()
      mapViewModel.performInitialCentering()
    }
    .onChange(of: showLabels) { _, newValue in
      mapViewModel.showLabels = newValue
    }
    .onChange(of: mapFilterRaw) { _, _ in
      mapViewModel.applyFilter(mapFilter)
    }
    .onChange(of: appState.bestAvailableLocation) { old, new in
      guard old?.coordinate.latitude != new?.coordinate.latitude
        || old?.coordinate.longitude != new?.coordinate.longitude else { return }
      mapViewModel.updateUserLocation(new)
    }
    .onChange(of: traceViewModel.availableNodes) { _, _ in
      // Contacts can load after path hops; lines, pins, and result styles all depend on them.
      mapViewModel.handleNodeTablesChanged()
    }
    .onChange(of: traceViewModel.discoveredRepeaters) { _, _ in
      // Discovered table can load after contacts; pins, path lines, and result styles depend on it.
      mapViewModel.handleNodeTablesChanged()
    }
    .onChange(of: traceViewModel.resultID) { _, _ in
      mapViewModel.updateOverlaysWithResults()
    }
    .alert(L10n.Contacts.Contacts.Trace.Map.saveTitle, isPresented: $showingSavePrompt) {
      TextField(L10n.Contacts.Contacts.Trace.Map.pathName, text: $saveName)
      Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {
        saveName = ""
      }
      Button(L10n.Contacts.Contacts.Common.save) {
        Task {
          let success = await mapViewModel.savePath(name: saveName)
          saveName = ""
          if success {
            showingSaveSuccess = true
          } else {
            errorMessage = L10n.Contacts.Contacts.Trace.Map.saveFailedMessage
          }
        }
      }
    } message: {
      Text(L10n.Contacts.Contacts.Trace.Map.saveMessage)
    }
    .sensoryFeedback(.impact(weight: .light), trigger: pinTapHaptic)
    .sensoryFeedback(.warning, trigger: rejectedTapHaptic)
    .alert(L10n.Contacts.Contacts.Trace.Map.savedTitle, isPresented: $showingSaveSuccess) {
      Button(L10n.Contacts.Contacts.Common.ok, role: .cancel) {}
    } message: {
      Text(L10n.Contacts.Contacts.Trace.Map.savedMessage)
    }
    .errorAlert($errorMessage, title: L10n.Contacts.Contacts.Trace.Map.saveFailedTitle)
  }

  // MARK: - Map Content

  private var mapContent: some View {
    MC1MapView(
      points: mapViewModel.mapPoints,
      lines: mapViewModel.mapLines,
      mapStyle: mapStyleSelection,
      isOffline: !appState.offlineMapService.isNetworkAvailable,
      showLabels: showLabels,
      clusteringEnabled: clusteringEnabled,
      showsUserLocation: true,
      isInteractive: true,
      showsScale: true,
      isNorthLocked: isNorthLocked,
      cameraRegion: $mapViewModel.cameraRegion,
      cameraRegionVersion: mapViewModel.cameraRegionVersion,
      cameraBottomSheetFraction: 0,
      onPointTap: { point, _ in
        let result = mapViewModel.handleMapPointTap(pointID: point.id)
        if result == .rejectedMiddleHop {
          rejectedTapHaptic += 1
        } else if result != .ignored {
          pinTapHaptic += 1
        }
      },
      onMapTap: nil,
      onCameraRegionChange: { region in
        mapViewModel.cameraRegion = region
      },
      isCenteredOnUser: $isCenteredOnUser
    )
    .ignoresSafeArea()
  }
}

#if DEBUG
  #Preview("Successful trace") {
    TracePathMapPreview()
      .environment(\.appState, AppState())
  }

  @MainActor
  private struct TracePathMapPreview: View {
    @State private var viewModel = TracePathMapPreview.makeViewModel()
    @State private var presentedResult: TraceResult?

    var body: some View {
      NavigationStack {
        TracePathMapView(traceViewModel: viewModel, presentedResult: $presentedResult)
          .navigationTitle(L10n.Contacts.Contacts.Trace.title)
          .navigationBarTitleDisplayMode(.inline)
          .toolbar {
            ToolbarItem(placement: .principal) {
              Picker(L10n.Contacts.Contacts.Trace.viewMode, selection: .constant(TracePathViewMode.map)) {
                ForEach(TracePathViewMode.allCases, id: \.self) { mode in
                  Text(mode.label).tag(mode)
                }
              }
              .pickerStyle(.segmented)
              .fixedSize()
            }
            ToolbarItem(placement: .primaryAction) {
              Button(L10n.Contacts.Contacts.Trace.saved, systemImage: "bookmark") {}
            }
          }
      }
    }

    private static func makeViewModel() -> TracePathViewModel {
      let viewModel = TracePathViewModel()
      let hops = previewHops
      viewModel.outboundPath = hops.dropFirst().dropLast().map { hop in
        PathHop(
          hashBytes: hop.hashBytes ?? Data([0x11]),
          publicKey: nil,
          resolvedName: hop.resolvedName
        )
      }
      viewModel.result = TraceResult(
        hops: hops,
        durationMs: 840,
        success: true,
        errorMessage: nil,
        tracedPathBytes: [0x11, 0x22],
        hashSize: 1
      )
      return viewModel
    }

    private static let previewHops: [TraceHop] = [
      previewHop(name: "Start", latitude: 37.7749, longitude: -122.4194, start: true),
      previewHop(name: "Ridge", latitude: 37.8044, longitude: -122.2712),
      previewHop(name: "Hill", latitude: 37.8715, longitude: -122.2730),
      previewHop(name: "End", latitude: 37.8716, longitude: -122.4194, end: true),
    ]

    private static func previewHop(
      name: String,
      latitude: Double,
      longitude: Double,
      start: Bool = false,
      end: Bool = false
    ) -> TraceHop {
      TraceHop(
        hashBytes: start || end ? nil : Data([0x11]),
        resolvedName: name,
        snr: 4,
        isStartNode: start,
        isEndNode: end,
        latitude: latitude,
        longitude: longitude
      )
    }
  }
#endif
