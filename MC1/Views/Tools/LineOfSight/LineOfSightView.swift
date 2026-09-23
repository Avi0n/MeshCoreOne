import CoreLocation
import MC1Services
import SwiftUI
import UIKit

private let analysisSheetDetentCollapsed: PresentationDetent = .fraction(0.25)
private let analysisSheetDetentHalf: PresentationDetent = .fraction(0.5)
private let analysisSheetDetentExpanded: PresentationDetent = .large

// MARK: - Line of Sight View

/// Sole Line of Sight workspace: one view model, one editor/sheet owner, and a
/// layout choice between paired analysis/map and map plus analysis sheet.
struct LineOfSightView: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appTheme) private var theme

  @State private var viewModel: LineOfSightViewModel
  @State private var layoutMode: LineOfSightLayoutMode = .mapWithSheet
  @State private var workspaceSize: CGSize = .zero
  @State private var sheetDetent: PresentationDetent = analysisSheetDetentCollapsed
  @State private var enableHalfDetent = false
  @State private var showAnalysisSheet = false
  @State private var editingPoint: PointID?
  @State private var analysisSheetMinY: CGFloat = 0
  @State private var mapOverlayMaxY: CGFloat = 0
  @State private var isResultsExpanded = false
  @State private var isRFSettingsExpanded = false
  @State private var copyHapticTrigger = 0
  @AppStorage(AppStorageKey.hasSeenRepeaterDragHint.rawValue)
  private var hasSeenDragHint = AppStorageKey.defaultHasSeenRepeaterDragHint
  @State private var showDragHint = false
  @State private var repeaterMarkerCenter: CGPoint?
  @State private var isNavigatingBack = false

  private var isRelocating: Bool {
    viewModel.relocatingPoint != nil
  }

  private var shouldShowExpandedAnalysis: Bool {
    layoutMode == .paired || sheetDetent != analysisSheetDetentCollapsed
  }

  private var mapOverlayBottomPadding: CGFloat {
    guard layoutMode == .mapWithSheet, showAnalysisSheet else { return 0 }
    return LineOfSightMapOverlayPadding.amount(
      overlayMaxY: mapOverlayMaxY,
      sheetMinY: analysisSheetMinY
    )
  }

  private var availableSheetDetents: Set<PresentationDetent> {
    if enableHalfDetent {
      [analysisSheetDetentCollapsed, analysisSheetDetentHalf, analysisSheetDetentExpanded]
    } else {
      [analysisSheetDetentCollapsed, analysisSheetDetentExpanded]
    }
  }

  private var isWorkspaceActive: Bool {
    if appState.navigation.selectedTool == .lineOfSight {
      return appState.navigation.isToolWorkspaceActive(.lineOfSight)
    }
    return true
  }

  private var workspaceBindID: String {
    "\(appState.servicesVersion)-\(appState.currentRadioID?.uuidString ?? "offline")"
  }

  init(preselectedContact: ContactDTO? = nil) {
    _viewModel = State(initialValue: LineOfSightViewModel(preselectedContact: preselectedContact))
  }

  var body: some View {
    workspaceWithObservers
  }

  private var workspaceWithObservers: some View {
    workspaceWithChrome
      .task(id: workspaceBindID) {
        await bindWorkspaceIfActive()
      }
      .onChange(of: isWorkspaceActive, handleWorkspaceActiveChange)
      .onChange(of: viewModel.pointA, handlePointAChange)
      .onChange(of: viewModel.pointB, handlePointBChange)
      .onChange(of: sheetDetent, handleSheetDetentChange)
      .onChange(of: viewModel.repeaterPoint, handleRepeaterPointChange)
      .onChange(of: viewModel.analysisStatus) { _, newStatus in
        handleAnalysisStatusChange(newStatus)
      }
  }

  private var workspaceWithChrome: some View {
    workspaceContent
      .navigationBarBackButtonHidden(layoutMode == .mapWithSheet && showAnalysisSheet)
      .liquidGlassToolbarBackground()
      .background {
        GeometryReader { proxy in
          LineOfSightLayoutProbe(identifier: layoutMode.accessibilityIdentifier)
            .onAppear { applyLayout(for: proxy.size) }
            .onChange(of: proxy.size) { _, size in
              applyLayout(for: size)
            }
        }
      }
  }

  private var workspaceContent: some View {
    Group {
      switch layoutMode {
      case .paired:
        pairedWorkspace
      case .mapWithSheet:
        sheetWorkspace
      }
    }
  }

  private var pairedWorkspace: some View {
    HStack(spacing: 0) {
      ScrollView {
        analysisContent
      }
      .scrollDismissesKeyboard(.immediately)
      .frame(width: LineOfSightLayoutMode.analysisColumnWidth(in: workspaceSize))
      .frame(maxHeight: .infinity)
      .background(analysisSurface)

      Divider()

      // Bleed tiles under the status bar without lifting the analysis list.
      mapCanvas(cameraBottomSheetFraction: 0)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(.container, edges: .top)
    }
  }

  private var sheetWorkspace: some View {
    mapCanvas(cameraBottomSheetFraction: showAnalysisSheet ? 0.25 : 0)
      .toolbar { compactBackToolbar }
      .sheet(isPresented: $showAnalysisSheet) {
        analysisSheetPresentation
      }
  }

  @ToolbarContentBuilder
  private var compactBackToolbar: some ToolbarContent {
    if showAnalysisSheet {
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
  }

  private var analysisSheetPresentation: some View {
    analysisSheet
      .onGeometryChange(for: CGFloat.self) { proxy in
        proxy.frame(in: .global).minY
      } action: { minY in
        if sheetDetent == analysisSheetDetentCollapsed {
          analysisSheetMinY = minY
        }
      }
      .presentationDetents(availableSheetDetents, selection: $sheetDetent)
      .presentationDragIndicator(.visible)
      .presentationBackgroundInteraction(.enabled)
      .presentationBackground(.regularMaterial)
      .interactiveDismissDisabled()
  }

  private var analysisSheet: some View {
    NavigationStack {
      ScrollView {
        analysisContent
      }
      .scrollDismissesKeyboard(.immediately)
      .toolbar(.hidden, for: .navigationBar)
    }
  }

  private var analysisContent: some View {
    LineOfSightAnalysisView(
      viewModel: viewModel,
      showsExpandedResults: shouldShowExpandedAnalysis,
      copyHapticTrigger: $copyHapticTrigger,
      editingPoint: $editingPoint,
      isResultsExpanded: $isResultsExpanded,
      isRFSettingsExpanded: $isRFSettingsExpanded,
      showDragHint: $showDragHint,
      repeaterMarkerCenter: $repeaterMarkerCenter,
      onRelocate: {
        if layoutMode == .mapWithSheet {
          withAnimation { sheetDetent = analysisSheetDetentCollapsed }
        }
      },
      onAnalyze: {
        if layoutMode == .mapWithSheet {
          withAnimation { sheetDetent = analysisSheetDetentExpanded }
        }
      }
    )
  }

  private var analysisSurface: Color {
    theme.surfaces?.canvas ?? Color(.systemBackground)
  }

  private func mapCanvas(cameraBottomSheetFraction: CGFloat) -> some View {
    LineOfSightMapView(
      viewModel: viewModel,
      mapOverlayBottomPadding: mapOverlayBottomPadding,
      cameraBottomSheetFraction: cameraBottomSheetFraction,
      onRepeaterTap: { handleRepeaterTap($0) },
      onMapTap: { handleMapTap(at: $0) },
      onMapLongPress: { handleMapLongPress(at: $0) }
    )
    .onGeometryChange(for: CGFloat.self) { proxy in
      proxy.frame(in: .global).maxY
    } action: { maxY in
      mapOverlayMaxY = maxY
    }
  }

  @MainActor
  private func bindWorkspaceIfActive() async {
    guard isWorkspaceActive else { return }
    appState.locationService.requestPermissionIfNeeded()
    viewModel.configure(
      dataStore: { [appState] in appState.offlineDataStore },
      radioID: { [appState] in appState.currentRadioID },
      deviceFrequencyKHz: appState.connectedDevice?.frequency
    )
    await viewModel.loadRepeaters()
    viewModel.applyInitialRepeaterCameraFitIfNeeded()
  }

  private func applyLayout(for size: CGSize) {
    guard size.width > 0, size.height > 0 else { return }
    workspaceSize = size
    let newMode = LineOfSightLayoutMode.preferred(in: size)
    let modeChanged = newMode != layoutMode
    layoutMode = newMode
    if newMode == .paired {
      showAnalysisSheet = false
    } else if !isNavigatingBack {
      showAnalysisSheet = true
    }
    if modeChanged {
      viewModel.restoreCameraAfterMapRecreation()
    }
  }

  @MainActor
  private func dismissLineOfSight() {
    guard !isNavigatingBack else { return }
    isNavigatingBack = true
    showAnalysisSheet = false
    viewModel.relocatingPoint = nil
    Task { @MainActor in
      await Task.yield()
      dismiss()
    }
  }

  private func handleWorkspaceActiveChange(_: Bool, isActive: Bool) {
    guard isActive else { return }
    if layoutMode == .mapWithSheet, !isNavigatingBack {
      showAnalysisSheet = true
    }
    Task { await bindWorkspaceIfActive() }
  }

  private func handlePointAChange(oldValue: SelectedPoint?, newValue: SelectedPoint?) {
    handlePointPresenceChange(
      oldIsNil: oldValue == nil,
      newIsNil: newValue == nil,
      otherIsNil: viewModel.pointB == nil
    )
  }

  private func handlePointBChange(oldValue: SelectedPoint?, newValue: SelectedPoint?) {
    handlePointPresenceChange(
      oldIsNil: oldValue == nil,
      newIsNil: newValue == nil,
      otherIsNil: viewModel.pointA == nil
    )
  }

  private func handleSheetDetentChange(oldValue: PresentationDetent, newValue: PresentationDetent) {
    guard layoutMode == .mapWithSheet else { return }
    if isRelocating, newValue != analysisSheetDetentCollapsed {
      viewModel.relocatingPoint = nil
    }
    if oldValue == analysisSheetDetentHalf, newValue != analysisSheetDetentHalf {
      enableHalfDetent = false
    }
  }

  private func handleRepeaterPointChange(oldValue: RepeaterPoint?, newValue: RepeaterPoint?) {
    guard oldValue == nil,
          newValue != nil,
          newValue?.isOnPath == true,
          !hasSeenDragHint else { return }
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

  private func handlePointPresenceChange(oldIsNil: Bool, newIsNil: Bool, otherIsNil: Bool) {
    guard layoutMode == .mapWithSheet else { return }
    if oldIsNil, !newIsNil, !otherIsNil {
      enableHalfDetent = true
      withAnimation {
        sheetDetent = analysisSheetDetentHalf
      }
    }
    if newIsNil, otherIsNil {
      withAnimation {
        sheetDetent = analysisSheetDetentCollapsed
      }
    }
  }

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

    viewModel.clearAnalysisResults()
    viewModel.relocatingPoint = nil
    if layoutMode == .mapWithSheet {
      enableHalfDetent = true
      withAnimation {
        sheetDetent = analysisSheetDetentHalf
      }
    }
  }

  private func handleAnalysisStatusChange(_ status: AnalysisStatus) {
    switch status {
    case .result:
      if layoutMode == .mapWithSheet {
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

// MARK: - Layout probe

/// UIKit identifier probe. SwiftUI identifiers often do not copy onto `UIView`.
private struct LineOfSightLayoutProbe: UIViewRepresentable {
  var identifier: String

  func makeUIView(context: Context) -> UIView {
    let view = UIView()
    view.isUserInteractionEnabled = false
    view.backgroundColor = .clear
    view.accessibilityIdentifier = identifier
    return view
  }

  func updateUIView(_ uiView: UIView, context: Context) {
    uiView.accessibilityIdentifier = identifier
  }
}

// MARK: - Preview

#Preview("Empty") {
  NavigationStack {
    LineOfSightView()
  }
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
    lastHeardTimestamp: nil,
    nickname: nil,
    isBlocked: false,
    isMuted: false,
    isFavorite: false,
    lastMessageDate: nil,
    unreadCount: 0
  )

  NavigationStack {
    LineOfSightView(preselectedContact: contact)
  }
  .environment(\.appState, AppState())
}
