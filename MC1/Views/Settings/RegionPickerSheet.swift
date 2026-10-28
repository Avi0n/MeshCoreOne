import MapLibre
import SwiftUI

struct RegionPickerSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss

  @State private var regionName = ""
  @State private var isDownloading = false
  @State private var errorMessage: String?
  @State private var includeTopo = false
  @State private var baseMaximumZoom = OfflineMapLayer.base.defaultMaximumZoom
  @State private var topographyMaximumZoom = OfflineMapLayer.topo.defaultMaximumZoom
  @State private var selectionBounds: MLNCoordinateBounds?
  @State private var selectionBoundsProvider: (() -> MLNCoordinateBounds?)?
  @State private var availableBytes: Int64?
  @FocusState private var nameIsFocused: Bool

  private static let selectionPadding: CGFloat = 40
  private static let minimumSelectionSide: CGFloat = 120
  private static let minimumControlsHeight: CGFloat = 100
  private static let selectionCornerRadius: CGFloat = 8
  private static let selectionLineWidth: CGFloat = 2

  var body: some View {
    NavigationStack {
      GeometryReader { geometry in
        VStack(spacing: 0) {
          selectionMap
          RegionPickerBottomCard(
            regionName: $regionName,
            includeTopo: $includeTopo,
            baseMaximumZoom: $baseMaximumZoom,
            topographyMaximumZoom: $topographyMaximumZoom,
            nameIsFocused: $nameIsFocused,
            downloadSummary: downloadSummary,
            exceedsAvailableSpace: exceedsAvailableSpace,
            exceedsProviderLimit: exceedsProviderLimit,
            isNetworkAvailable: appState.offlineMapService.isNetworkAvailable,
            isDownloading: isDownloading,
            maximumHeight: min(geometry.size.height, max(Self.minimumControlsHeight, geometry.size.height - minimumMapHeight))
          )
          .disabled(isDownloading)
        }
      }
      .navigationTitle(L10n.Settings.OfflineMaps.pickRegion)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Settings.OfflineMaps.cancel) { dismiss() }
            .disabled(isDownloading)
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Settings.OfflineMaps.download, action: downloadRegion)
            .disabled(
              regionName.isEmpty || isDownloading || exceedsAvailableSpace || exceedsProviderLimit
                || !appState.offlineMapService.isNetworkAvailable || selectionBounds == nil
            )
        }
      }
      .errorAlert($errorMessage)
      .onAppear { refreshAvailableBytes() }
    }
    .interactiveDismissDisabled(isDownloading)
  }

  private var minimumMapHeight: CGFloat {
    Self.selectionPadding * 2 + Self.minimumSelectionSide
  }

  private var selectionMap: some View {
    ZStack {
      MC1MapView(
        points: [],
        lines: [],
        mapStyle: .standard,
        isOffline: !appState.offlineMapService.isNetworkAvailable,
        showLabels: false,
        showsUserLocation: true,
        isInteractive: true,
        showsScale: false,
        isNorthLocked: true,
        cameraRegion: .constant(nil),
        cameraRegionVersion: 0,
        onPointTap: nil,
        onMapTap: { _ in nameIsFocused = false },
        onCameraRegionChange: nil,
        onMapGeometryChange: updateSelectionBounds
      )
      .allowsHitTesting(!isDownloading)
      .disabled(isDownloading)

      RoundedRectangle(cornerRadius: Self.selectionCornerRadius)
        .strokeBorder(Color.accentColor, lineWidth: Self.selectionLineWidth)
        .padding(Self.selectionPadding)
        .allowsHitTesting(false)
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
  }

  private var options: OfflineMapDownloadOptions {
    OfflineMapDownloadOptions(baseMaximumZoom: baseMaximumZoom, topographyMaximumZoom: includeTopo ? topographyMaximumZoom : nil)
  }

  private var downloadSummary: OfflineMapDownloadPolicy.Summary? {
    selectionBounds.map { OfflineMapDownloadPolicy.summary(in: $0, options: options) }
  }

  private var exceedsProviderLimit: Bool {
    downloadSummary.map { !$0.permitsDownload } ?? false
  }

  private var exceedsAvailableSpace: Bool {
    guard let estimated = downloadSummary?.estimatedBytes, let available = availableBytes else { return false }
    return estimated > available
  }

  private func refreshAvailableBytes() {
    let values = try? URL.documentsDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
    availableBytes = values?.volumeAvailableCapacityForImportantUsage
  }

  private func updateSelectionBounds(_ mapView: MLNMapView) {
    let bounds = MC1MapView.selectionBounds(in: mapView, padding: Self.selectionPadding)
    if let bounds, let selectionBounds,
       MLNCoordinateBoundsEqualToCoordinateBounds(bounds, selectionBounds) { return }
    if bounds == nil, selectionBounds == nil { return }
    selectionBoundsProvider = { [weak mapView] in
      guard let mapView else { return nil }
      return MC1MapView.selectionBounds(in: mapView, padding: Self.selectionPadding)
    }
    selectionBounds = bounds
  }

  private func downloadRegion() {
    guard !isDownloading, !regionName.isEmpty,
          appState.offlineMapService.isNetworkAvailable,
          let bounds = selectionBoundsProvider?() else { return }
    let name = regionName
    let options = options
    let service = appState.offlineMapService
    let summary = OfflineMapDownloadPolicy.summary(in: bounds, options: options)
    guard summary.permitsDownload else {
      errorMessage = L10n.Settings.OfflineMaps.Error.providerLimitExceeded
      return
    }
    refreshAvailableBytes()
    if let availableBytes, summary.estimatedBytes > availableBytes {
      errorMessage = L10n.Settings.OfflineMaps.storageRemediation
      return
    }
    isDownloading = true
    nameIsFocused = false

    Task {
      defer { isDownloading = false }
      do {
        try await service.downloadRegion(name: name, bounds: bounds, options: options)
        dismiss()
      } catch {
        errorMessage = error.userFacingMessage
      }
    }
  }
}
