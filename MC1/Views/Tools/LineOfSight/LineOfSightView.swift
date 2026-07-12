import CoreLocation
import MapKit
import MC1Services
import SwiftUI

private let analysisSheetDetentCollapsed: PresentationDetent = .fraction(0.25)
private let analysisSheetDetentHalf: PresentationDetent = .fraction(0.5)
private let analysisSheetDetentExpanded: PresentationDetent = .large

// MARK: - Line of Sight View

/// Full-screen map view for analyzing line-of-sight between two points
struct LineOfSightView: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss

  @State private var viewModel: LineOfSightViewModel
  @State private var sheetDetent: PresentationDetent = analysisSheetDetentCollapsed
  @State private var enableHalfDetent = false
  @State private var showAnalysisSheet: Bool
  @State private var editingPoint: PointID?
  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @State private var sheetBottomInset: CGFloat = 220
  @State private var isResultsExpanded = false
  @State private var isRFSettingsExpanded = false
  @State private var copyHapticTrigger = 0

  private let layoutMode: LineOfSightLayoutMode

  // One-time drag hint tooltip for repeater marker
  @AppStorage(AppStorageKey.hasSeenRepeaterDragHint.rawValue) private var hasSeenDragHint = AppStorageKey.defaultHasSeenRepeaterDragHint
  @State private var showDragHint = false
  @State private var repeaterMarkerCenter: CGPoint?
  @State private var isNavigatingBack = false

  private var isRelocating: Bool {
    viewModel.relocatingPoint != nil
  }

  private var shouldShowExpandedAnalysis: Bool {
    sheetDetent != analysisSheetDetentCollapsed
  }

  private var mapOverlayBottomPadding: CGFloat {
    showAnalysisSheet ? sheetBottomInset : 0
  }

  private var availableSheetDetents: Set<PresentationDetent> {
    if enableHalfDetent {
      [analysisSheetDetentCollapsed, analysisSheetDetentHalf, analysisSheetDetentExpanded]
    } else {
      [analysisSheetDetentCollapsed, analysisSheetDetentExpanded]
    }
  }

  // MARK: - Initialization

  init(preselectedContact: ContactDTO? = nil) {
    _viewModel = State(initialValue: LineOfSightViewModel(preselectedContact: preselectedContact))
    layoutMode = .mapWithSheet
    _showAnalysisSheet = State(initialValue: true)
  }

  init(viewModel: LineOfSightViewModel, layoutMode: LineOfSightLayoutMode) {
    _viewModel = State(initialValue: viewModel)
    self.layoutMode = layoutMode
    _showAnalysisSheet = State(initialValue: layoutMode == .mapWithSheet)
  }

  // MARK: - Body

  var body: some View {
    switch layoutMode {
    case .panel:
      ScrollView {
        analysisSheetContent
      }
      .scrollDismissesKeyboard(.immediately)

    case .map:
      mapCanvasWithBehaviors(showSheet: false)

    case .mapWithSheet:
      mapCanvasWithBehaviors(showSheet: true)
    }
  }

  @ViewBuilder
  private func mapCanvasWithBehaviors(showSheet: Bool) -> some View {
    let base = LOSMapCanvasView(
      viewModel: viewModel,
      appState: appState,
      mapStyleSelection: $mapStyleSelection,
      showLabels: $showLabels,
      isNorthLocked: $isNorthLocked,
      mapOverlayBottomPadding: mapOverlayBottomPadding,
      cameraBottomSheetFraction: showSheet ? 0.25 : 0,
      onRepeaterTap: { handleRepeaterTap($0) },
      onMapTap: { handleMapTap(at: $0) },
      onMapLongPress: { handleMapLongPress(at: $0) }
    )
    .onChange(of: viewModel.pointA) { oldValue, newValue in
      if oldValue == nil, newValue != nil, viewModel.pointB != nil {
        if showSheet {
          enableHalfDetent = true
          withAnimation {
            sheetDetent = analysisSheetDetentHalf
          }
        }
      }

      if showSheet, newValue == nil, viewModel.pointB == nil {
        withAnimation {
          sheetDetent = analysisSheetDetentCollapsed
        }
      }
    }
    .onChange(of: viewModel.pointB) { oldValue, newValue in
      if oldValue == nil, newValue != nil, viewModel.pointA != nil {
        if showSheet {
          enableHalfDetent = true
          withAnimation {
            sheetDetent = analysisSheetDetentHalf
          }
        }
      }

      if showSheet, newValue == nil, viewModel.pointA == nil {
        withAnimation {
          sheetDetent = analysisSheetDetentCollapsed
        }
      }
    }
    .onChange(of: sheetDetent) { oldValue, newValue in
      guard showSheet else { return }

      if isRelocating, newValue != analysisSheetDetentCollapsed {
        viewModel.relocatingPoint = nil
      }

      // Disable half detent once user drags away from it
      if oldValue == analysisSheetDetentHalf, newValue != analysisSheetDetentHalf {
        enableHalfDetent = false
      }
    }
    .onChange(of: viewModel.repeaterPoint) { oldValue, newValue in
      if oldValue == nil,
         newValue != nil,
         newValue?.isOnPath == true,
         !hasSeenDragHint {
        withAnimation(.easeIn(duration: 0.3)) {
          showDragHint = true
        }
        hasSeenDragHint = true
        Task {
          try? await Task.sleep(for: .seconds(5))
          withAnimation(.easeOut(duration: 0.3)) {
            showDragHint = false
          }
        }
      }
    }
    .onChange(of: viewModel.analysisStatus) { _, newStatus in
      handleAnalysisStatusChange(newStatus, showSheet: showSheet)
    }
    .task {
      appState.locationService.requestPermissionIfNeeded()
      viewModel.configure(
        dataStore: { [appState] in appState.offlineDataStore },
        radioID: { [appState] in appState.currentRadioID },
        deviceFrequencyKHz: appState.connectedDevice?.frequency
      )
      viewModel.showLabels = showLabels
      await viewModel.loadRepeaters()
      viewModel.centerOnAllRepeaters()
    }
    .onChange(of: showLabels) { _, newValue in
      viewModel.showLabels = newValue
    }

    if showSheet {
      base
        .navigationBarBackButtonHidden(true)
        .toolbar {
          ToolbarItem(placement: .topBarLeading) {
            Button {
              dismissLineOfSight()
            } label: {
              Label(L10n.Tools.Tools.LineOfSight.back, systemImage: "chevron.left")
                .labelStyle(.titleAndIcon)
            }
            .accessibilityLabel(L10n.Tools.Tools.LineOfSight.back)
          }
        }
        .liquidGlassToolbarBackground()
        .onDisappear {
          showAnalysisSheet = false
        }
        .sheet(isPresented: $showAnalysisSheet) {
          analysisSheet
            .onGeometryChange(for: CGFloat.self) { proxy in
              proxy.size.height - proxy.safeAreaInsets.bottom - 16
            } action: { inset in
              if sheetDetent == analysisSheetDetentCollapsed {
                sheetBottomInset = max(0, inset)
              }
            }
            .presentationDetents(availableSheetDetents, selection: $sheetDetent)
            .presentationDragIndicator(.visible)
            .presentationBackgroundInteraction(.enabled)
            .presentationBackground(.regularMaterial)
            .interactiveDismissDisabled()
        }
    } else {
      base
        .liquidGlassToolbarBackground()
    }
  }

  @MainActor
  private func dismissLineOfSight() {
    guard !isNavigatingBack else { return }
    isNavigatingBack = true

    showAnalysisSheet = false
    viewModel.relocatingPoint = nil

    // Yield to let showAnalysisSheet = false commit before dismiss fires,
    // avoiding a sheet-dismissal animation conflict.
    Task { @MainActor in
      await Task.yield()
      dismiss()
    }
  }

  // MARK: - Analysis Sheet

  private var analysisSheet: some View {
    NavigationStack {
      ScrollView {
        analysisSheetContent
      }
      .scrollDismissesKeyboard(.immediately)
      .toolbar(.hidden, for: .navigationBar)
    }
  }

  private var analysisSheetContent: some View {
    VStack(alignment: .leading, spacing: 16) {
      PointsSummarySectionView(
        viewModel: viewModel,
        copyHapticTrigger: $copyHapticTrigger,
        editingPoint: $editingPoint,
        onRelocate: { withAnimation { sheetDetent = analysisSheetDetentCollapsed } }
      )

      // Before analysis: show analyze button, then RF settings
      if viewModel.canAnalyze, !hasAnalysisResult {
        analyzeButtonSection
        RFSettingsSectionView(viewModel: viewModel, isRFSettingsExpanded: $isRFSettingsExpanded)
      }

      // After analysis: show button, results, terrain, then RF settings
      if case let .result(result) = viewModel.analysisStatus {
        analyzeButtonSection

        resultSummarySection(result)

        if shouldShowExpandedAnalysis {
          TerrainProfileSectionView(
            viewModel: viewModel,
            showDragHint: $showDragHint,
            repeaterMarkerCenter: $repeaterMarkerCenter
          )
          RFSettingsSectionView(viewModel: viewModel, isRFSettingsExpanded: $isRFSettingsExpanded)
        }
      }

      // Relay analysis: show relay-specific results card
      if case let .relayResult(result) = viewModel.analysisStatus {
        analyzeButtonSection

        RelayResultsCardView(result: result, isExpanded: $isResultsExpanded)

        if shouldShowExpandedAnalysis {
          TerrainProfileSectionView(
            viewModel: viewModel,
            showDragHint: $showDragHint,
            repeaterMarkerCenter: $repeaterMarkerCenter
          )
          RFSettingsSectionView(viewModel: viewModel, isRFSettingsExpanded: $isRFSettingsExpanded)
        }
      }

      if case let .error(message) = viewModel.analysisStatus {
        AnalysisErrorView(
          message: message,
          hasRepeater: viewModel.repeaterPoint != nil,
          onRetry: {
            if viewModel.repeaterPoint != nil {
              viewModel.analyzeWithRepeater()
            } else {
              viewModel.analyze()
            }
          }
        )
      }
    }
    .padding()
  }

  // MARK: - Analyze Button Section

  private var analyzeButtonSection: some View {
    AnalyzeButton(
      viewModel: viewModel,
      hasAnalysisResult: hasAnalysisResult,
      onAnalyze: {
        withAnimation { sheetDetent = analysisSheetDetentExpanded }
      }
    )
  }

  // MARK: - Result Summary Section

  private func resultSummarySection(_ result: PathAnalysisResult) -> some View {
    ResultsCardView(result: result, isExpanded: $isResultsExpanded)
  }

  // MARK: - Computed Properties

  private var analysisResult: PathAnalysisResult? {
    if case let .result(result) = viewModel.analysisStatus {
      return result
    }
    return nil
  }

  private var hasAnalysisResult: Bool {
    if case .result = viewModel.analysisStatus { return true }
    if case .relayResult = viewModel.analysisStatus { return true }
    return false
  }

  // MARK: - Helper Methods

  private func handleMapTap(at coordinate: CLLocationCoordinate2D) {
    if let relocating = viewModel.relocatingPoint {
      handleRelocation(to: coordinate, for: relocating)
    }
  }

  private func handleMapLongPress(at coordinate: CLLocationCoordinate2D) {
    if let relocating = viewModel.relocatingPoint {
      handleRelocation(to: coordinate, for: relocating)
      return
    }
    viewModel.selectPoint(at: coordinate)
  }

  private func handleRelocation(to coordinate: CLLocationCoordinate2D, for pointID: PointID) {
    switch pointID {
    case .pointA:
      viewModel.setPointA(coordinate: coordinate, contact: nil)
    case .pointB:
      viewModel.setPointB(coordinate: coordinate, contact: nil)
    case .repeater:
      viewModel.setRepeaterOffPath(coordinate: coordinate)
    }

    // Clear results and show Analyze button
    viewModel.clearAnalysisResults()
    viewModel.relocatingPoint = nil
    enableHalfDetent = true
    withAnimation {
      sheetDetent = analysisSheetDetentHalf
    }
  }

  private func handleAnalysisStatusChange(_ status: AnalysisStatus, showSheet: Bool) {
    switch status {
    case .result:
      if showSheet {
        sheetDetent = analysisSheetDetentExpanded
      }
    case .relayResult:
      break
    default:
      return
    }

    if viewModel.shouldAutoZoomOnNextResult {
      viewModel.shouldAutoZoomOnNextResult = false
      viewModel.zoomToShowBothPoints()
    }
  }

  private func handleRepeaterTap(_ contact: ContactDTO) {
    viewModel.toggleContact(contact)
  }
}

// MARK: - Map Canvas View

private struct LOSMapCanvasView: View {
  @Bindable var viewModel: LineOfSightViewModel
  let appState: AppState
  @Environment(\.colorScheme) private var colorScheme
  @Binding var mapStyleSelection: MapStyleSelection
  @Binding var showLabels: Bool
  @Binding var isNorthLocked: Bool
  let mapOverlayBottomPadding: CGFloat
  let cameraBottomSheetFraction: CGFloat?
  let onRepeaterTap: (ContactDTO) -> Void
  let onMapTap: (CLLocationCoordinate2D) -> Void
  let onMapLongPress: (CLLocationCoordinate2D) -> Void

  @State private var isCenteredOnUser = false

  var body: some View {
    ZStack {
      MC1MapView(
        points: viewModel.mapPoints,
        lines: viewModel.mapLines,
        mapStyle: mapStyleSelection,
        isDarkMode: colorScheme == .dark,
        isOffline: !appState.offlineMapService.isNetworkAvailable,
        showLabels: showLabels,
        showsUserLocation: true,
        isInteractive: true,
        showsScale: true,
        isNorthLocked: isNorthLocked,
        cameraRegion: $viewModel.cameraRegion,
        cameraRegionVersion: viewModel.cameraRegionVersion,
        cameraBottomSheetFraction: cameraBottomSheetFraction,
        onPointTap: { point, _ in
          if let repeater = viewModel.repeatersWithLocation.first(where: { $0.id == point.id }) {
            onRepeaterTap(repeater)
          }
        },
        onMapTap: onMapTap,
        onMapLongPress: onMapLongPress,
        onCameraRegionChange: { region in
          viewModel.cameraRegion = region
        },
        isCenteredOnUser: $isCenteredOnUser
      )
      .ignoresSafeArea()

      VStack {
        Spacer()
        HStack {
          Spacer()
          MapControlsToolbar(
            onLocationTap: {
              // Unlike other maps, LOS needs a precise fresh fix (no device-GPS fallback,
              // tighter span), so it fetches live rather than using AppState.centerOnUserLocation.
              Task {
                if let location = try? await appState.locationService.requestCurrentLocation() {
                  viewModel.setCameraRegion(MKCoordinateRegion(
                    center: location.coordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                  ))
                  isCenteredOnUser = true
                }
              }
            },
            isCenteredOnUser: isCenteredOnUser,
            isNorthLocked: $isNorthLocked,
            showLabels: $showLabels,
            mapStyleSelection: $mapStyleSelection,
            viewportBounds: viewModel.cameraRegion?.toMLNCoordinateBounds()
          ) {
            EmptyView()
          }
        }
      }
      .padding(.bottom, mapOverlayBottomPadding)
    }
  }
}

// MARK: - Frequency Input Row

/// Extracted view for frequency input with its own @FocusState
/// This is necessary because @FocusState doesn't work properly when declared in a parent view
/// and used in sheet content.
struct FrequencyInputRow: View {
  @Bindable var viewModel: LineOfSightViewModel
  @FocusState private var isFocused: Bool
  @State private var text: String = ""

  var body: some View {
    HStack {
      Label(L10n.Tools.Tools.LineOfSight.frequency, systemImage: "antenna.radiowaves.left.and.right")
        .foregroundStyle(.secondary)
      Spacer()
      TextField(L10n.Tools.Tools.LineOfSight.mhz, text: $text)
        .keyboardType(.decimalPad)
        .multilineTextAlignment(.trailing)
        .frame(width: 80)
        .focused($isFocused)
        .onChange(of: text) { _, newValue in
          let replaced = newValue.replacing(",", with: ".")
          if replaced != newValue {
            text = replaced
          }
        }
        .onChange(of: isFocused) { _, focused in
          if focused {
            // Sync text from view model when gaining focus
            text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
          } else {
            // Commit when focus is lost
            commitEdit()
          }
        }

      Text(L10n.Tools.Tools.LineOfSight.mhz)
        .foregroundStyle(.secondary)

      if isFocused {
        Button {
          commitEdit()
          isFocused = false
        } label: {
          Image(systemName: "checkmark.circle.fill")
            .foregroundStyle(.green)
            .font(.title2)
        }
        .buttonStyle(.plain)
      }
    }
    .onAppear {
      text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
    }
  }

  private func commitEdit() {
    guard let parsed = viewModel.parseFrequency(text) else {
      // Reject empty, unparseable, or non-positive input by restoring
      // the last valid value rather than passing it to analysis.
      text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
      return
    }
    viewModel.frequencyMHz = parsed
    viewModel.commitFrequencyChange()
  }
}

// MARK: - Analyze Button

private struct AnalyzeButton: View {
  var viewModel: LineOfSightViewModel
  let hasAnalysisResult: Bool
  let onAnalyze: () -> Void

  var body: some View {
    Button {
      viewModel.shouldAutoZoomOnNextResult = true
      onAnalyze()
      if viewModel.repeaterPoint != nil {
        viewModel.analyzeWithRepeater()
      } else {
        viewModel.analyze()
      }
    } label: {
      if viewModel.isAnalyzing {
        HStack {
          ProgressView()
            .controlSize(.small)
          Text(L10n.Tools.Tools.LineOfSight.analyzing)
        }
        .frame(maxWidth: .infinity)
      } else {
        Label(L10n.Tools.Tools.LineOfSight.analyze, systemImage: "waveform.path")
          .frame(maxWidth: .infinity)
      }
    }
    .liquidGlassProminentButtonStyle()
    .controlSize(.large)
    .disabled(viewModel.isAnalyzing)
  }
}

// MARK: - Preview

#Preview("Empty") {
  LineOfSightView()
    .environment(\.appState, AppState())
}

#Preview("With Contact") {
  let contact = ContactDTO(
    id: UUID(),
    radioID: UUID(),
    publicKey: Data(repeating: 0x01, count: 32),
    name: "Test Contact",
    typeRawValue: 0,
    flags: 0,
    outPathLength: PacketBuilder.floodPathSentinel,
    outPath: Data(),
    lastAdvertTimestamp: 0,
    latitude: 37.7749,
    longitude: -122.4194,
    lastModified: 0,
    nickname: nil,
    isBlocked: false,
    isMuted: false,
    isFavorite: false,
    lastMessageDate: nil,
    unreadCount: 0
  )

  LineOfSightView(preselectedContact: contact)
    .environment(\.appState, AppState())
}
