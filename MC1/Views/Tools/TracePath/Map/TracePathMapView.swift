import MapKit
import MC1Services
import os.log
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "TracePathMapView")

/// Map-based view for building and visualizing trace paths
struct TracePathMapView: View {
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme
  @Bindable var traceViewModel: TracePathViewModel
  @Binding var presentedResult: TraceResult?
  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @AppStorage(AppStorageKey.mapColorSchemePreference.rawValue)
  private var mapColorSchemeRaw = AppStorageKey.defaultMapColorSchemePreference
  @AppStorage(AppStorageKey.mapFilterTracePath.rawValue)
  private var mapFilterRaw: String = ""
  @State private var mapViewModel = TracePathMapViewModel()

  private var mapIsDark: Bool {
    let preference = AppColorSchemePreference(rawValue: mapColorSchemeRaw) ?? .system
    return resolvedMapIsDark(preference: preference, colorScheme: colorScheme)
  }

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
      isDarkMode: mapIsDark,
      isOffline: !appState.offlineMapService.isNetworkAvailable,
      showLabels: showLabels,
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
