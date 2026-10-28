import MapLibre
import MC1Services
import SwiftUI

struct OfflineMapSettingsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var showingRegionPicker = false

  var body: some View {
    Group {
      if appState.offlineMapService.packs.isEmpty {
        ContentUnavailableView {
          Label(L10n.Settings.OfflineMaps.emptyTitle, systemImage: "map")
        } description: {
          Text(L10n.Settings.OfflineMaps.emptyDescription)
        } actions: {
          Button(L10n.Settings.OfflineMaps.downloadRegion, systemImage: "arrow.down.circle") {
            showingRegionPicker = true
          }
          .buttonStyle(.bordered)
        }
      } else {
        List {
          PacksSection()
          StorageSection()
        }
        .themedCanvas(theme)
        .toolbar {
          ToolbarItem(placement: .primaryAction) {
            Button(L10n.Settings.OfflineMaps.downloadRegion, systemImage: "plus") {
              showingRegionPicker = true
            }
          }
        }
      }
    }
    .navigationTitle(L10n.Settings.OfflineMaps.title)
    .sheet(isPresented: $showingRegionPicker) {
      RegionPickerSheet()
    }
  }
}

// MARK: - Packs Section

private struct PacksSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  var body: some View {
    Section {
      ForEach(appState.offlineMapService.packs) { pack in
        OfflinePackRow(pack: pack)
      }
      .onDelete { indexSet in
        if let index = indexSet.first {
          let pack = appState.offlineMapService.packs[index]
          Task { await appState.offlineMapService.deletePack(pack) }
        }
      }
    }
    .themedRowBackground(theme)
  }
}

// MARK: - Storage Section

private struct StorageSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  var body: some View {
    Section {
      LabeledContent(L10n.Settings.OfflineMaps.storageUsed) {
        Text(appState.offlineMapService.databaseSize, format: .byteCount(style: .file))
      }
    } header: {
      Text(L10n.Settings.OfflineMaps.storage)
    } footer: {
      Text(L10n.Settings.OfflineMaps.storageFooter)
    }
    .themedRowBackground(theme)
  }
}

// MARK: - Offline Pack Row

private struct OfflinePackRow: View {
  @Environment(\.appState) private var appState
  let pack: OfflinePack

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      HStack {
        Text(pack.name)
        Text("— \(pack.layer.label)")
          .foregroundStyle(.secondary)
      }

      HStack {
        if pack.isComplete {
          Text(L10n.Settings.OfflineMaps.complete)
            .foregroundStyle(.secondary)
        } else if pack.isPaused {
          Text(L10n.Settings.OfflineMaps.paused)
            .foregroundStyle(.secondary)
        } else {
          Text(L10n.Settings.OfflineMaps.downloading)
            .foregroundStyle(.secondary)
        }

        Spacer()

        VStack(alignment: .trailing) {
          Text(Int64(pack.completedBytes), format: .byteCount(style: .file))
          if let speed = pack.downloadSpeed, speed > 0 {
            Text("\(speed, format: .byteCount(style: .file))/s")
          }
        }
        .foregroundStyle(.secondary)
      }
      .font(.caption)

      if !pack.isComplete {
        HStack {
          ProgressView(value: pack.completedFraction)

          Button(
            pack.isPaused
              ? L10n.Settings.OfflineMaps.resume
              : L10n.Settings.OfflineMaps.pause,
            systemImage: pack.isPaused ? "play.fill" : "pause.fill"
          ) {
            if pack.isPaused {
              appState.offlineMapService.resumePack(pack)
            } else {
              appState.offlineMapService.pausePack(pack)
            }
          }
          .labelStyle(.iconOnly)
          .buttonStyle(.borderless)
        }
      }
    }
  }
}

// MARK: - Region Picker Sheet

private struct RegionPickerSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss

  @State private var regionName = ""
  @State private var isDownloading = false
  @State private var errorMessage: String?
  @State private var includeTopo = false
  @State private var selectionBounds: MLNCoordinateBounds?
  @State private var selectionBoundsProvider: (() -> MLNCoordinateBounds?)?
  @State private var availableBytes: Int64?

  private static let selectionPadding: CGFloat = 40

  var body: some View {
    NavigationStack {
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
          onMapTap: nil,
          onCameraRegionChange: nil,
          onMapGeometryChange: updateSelectionBounds
        )

        // Selection rectangle overlay
        RoundedRectangle(cornerRadius: 8)
          .strokeBorder(Color.accentColor, lineWidth: 2)
          .padding(Self.selectionPadding)
          .allowsHitTesting(false)
      }
      .navigationTitle(L10n.Settings.OfflineMaps.pickRegion)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Settings.OfflineMaps.cancel) {
            dismiss()
          }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Settings.OfflineMaps.download) {
            downloadRegion()
          }
          .disabled(
            regionName.isEmpty || isDownloading || exceedsAvailableSpace || exceedsProviderLimit
              || !appState.offlineMapService.isNetworkAvailable
              || selectionBounds == nil
          )
        }
      }
      .safeAreaInset(edge: .bottom) {
        RegionPickerBottomCard(
          regionName: $regionName,
          includeTopo: $includeTopo,
          estimatedDownloadBytes: estimatedDownloadBytes,
          exceedsAvailableSpace: exceedsAvailableSpace,
          exceedsProviderLimit: exceedsProviderLimit,
          isNetworkAvailable: appState.offlineMapService.isNetworkAvailable
        )
      }
      .errorAlert($errorMessage)
      .onAppear { refreshAvailableBytes() }
    }
  }

  // MARK: - Download Estimate

  private var selectedLayers: Set<OfflineMapLayer> {
    var layers: Set<OfflineMapLayer> = [.base]
    if includeTopo { layers.insert(.topo) }
    return layers
  }

  private var estimatedDownloadBytes: Int64? {
    guard let bounds = selectionBounds else { return nil }
    return estimatedDownloadBytes(for: bounds)
  }

  private func estimatedDownloadBytes(for bounds: MLNCoordinateBounds) -> Int64 {
    selectedLayers.reduce(Int64(0)) { total, layer in
      total + OfflineMapService.estimatedDownloadSize(bounds: bounds, minZoom: OfflineMapDownloadPolicy.minimumZoom, maxZoom: Int(layer.maxDownloadZoom), layer: layer)
    }
  }

  private var exceedsProviderLimit: Bool {
    selectionBounds.map { !OfflineMapDownloadPolicy.permits($0) } ?? false
  }

  private var exceedsAvailableSpace: Bool {
    guard let estimated = estimatedDownloadBytes,
          let available = availableBytes else { return false }
    return estimated > available
  }

  private func refreshAvailableBytes() {
    let values = try? URL.documentsDirectory.resourceValues(
      forKeys: [.volumeAvailableCapacityForImportantUsageKey]
    )
    availableBytes = values?.volumeAvailableCapacityForImportantUsage
  }

  // MARK: - Bounds

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

  // MARK: - Download

  private func downloadRegion() {
    guard !isDownloading, !regionName.isEmpty,
          appState.offlineMapService.isNetworkAvailable,
          let bounds = selectionBoundsProvider?() else { return }
    guard OfflineMapDownloadPolicy.permits(bounds) else {
      errorMessage = L10n.Settings.OfflineMaps.Error.providerLimitExceeded
      return
    }
    refreshAvailableBytes()
    if let availableBytes, estimatedDownloadBytes(for: bounds) > availableBytes {
      errorMessage = L10n.Settings.OfflineMaps.exceedsStorage
      return
    }
    isDownloading = true

    let layers = selectedLayers

    Task {
      defer { isDownloading = false }
      do {
        try await appState.offlineMapService.downloadRegion(
          name: regionName,
          bounds: bounds,
          layers: layers
        )
        dismiss()
      } catch {
        errorMessage = error.userFacingMessage
      }
    }
  }
}

// MARK: - Region Picker Bottom Card

private struct RegionPickerBottomCard: View {
  @Binding var regionName: String
  @Binding var includeTopo: Bool
  let estimatedDownloadBytes: Int64?
  let exceedsAvailableSpace: Bool
  let exceedsProviderLimit: Bool
  let isNetworkAvailable: Bool

  /// Warn when estimated download exceeds 500 MB.
  private static let largeDownloadThreshold: Int64 = 500_000_000

  @State private var footerMinHeight: CGFloat = 0

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      TextField(L10n.Settings.OfflineMaps.regionName, text: $regionName)
        .textFieldStyle(.plain)

      Divider()

      VStack(alignment: .leading) {
        Text(L10n.Settings.OfflineMaps.layers)
          .font(.caption)
          .foregroundStyle(.secondary)

        Toggle(L10n.Settings.OfflineMaps.Layer.topo, isOn: $includeTopo)
      }
      .toggleStyle(.switch)
      .controlSize(.mini)

      Divider()

      footerContent
        .frame(minHeight: footerMinHeight, alignment: .top)
        .background {
          // Measure the tallest possible footer state (estimate + warning)
          // to reserve stable space regardless of current state or Dynamic Type size.
          tallestFooterState
            .hidden()
            .onGeometryChange(for: CGFloat.self) { proxy in
              proxy.size.height
            } action: { height in
              footerMinHeight = height
            }
        }
    }
    .padding()
    .background(.regularMaterial, in: .rect(cornerRadius: 12))
    .padding(.horizontal)
    .padding(.bottom, 4)
  }

  private var footerContent: some View {
    VStack(alignment: .leading, spacing: 12) {
      if !isNetworkAvailable {
        HStack {
          Image(systemName: "wifi.slash")
            .foregroundStyle(.red)
          Text(L10n.Settings.OfflineMaps.noNetwork)
            .foregroundStyle(.red)
        }
        .font(.caption)
      } else if exceedsProviderLimit {
        Text(L10n.Settings.OfflineMaps.Error.providerLimitExceeded)
          .foregroundStyle(.red)
          .font(.caption)
      } else if let bytes = estimatedDownloadBytes {
        let isLarge = bytes > Self.largeDownloadThreshold

        HStack {
          if exceedsAvailableSpace {
            Image(systemName: "xmark.circle.fill")
              .foregroundStyle(.red)
          } else if isLarge {
            Image(systemName: "exclamationmark.triangle.fill")
              .foregroundStyle(.orange)
          }

          Text(L10n.Settings.OfflineMaps.estimatedSize(
            bytes.formatted(.byteCount(style: .file))
          ))
          .foregroundStyle(exceedsAvailableSpace ? .red : isLarge ? .orange : .secondary)
        }
        .font(.caption)

        if exceedsAvailableSpace {
          Text(L10n.Settings.OfflineMaps.exceedsStorage)
            .font(.caption)
            .foregroundStyle(.red)
        } else if isLarge {
          Text(L10n.Settings.OfflineMaps.largeTileWarning)
            .font(.caption)
            .foregroundStyle(.orange)
        } else {
          Text(L10n.Settings.OfflineMaps.downloadHint)
            .font(.caption)
            .foregroundStyle(.secondary)
        }
      } else {
        Text(L10n.Settings.OfflineMaps.downloadHint)
          .font(.caption)
          .foregroundStyle(.secondary)
      }
    }
  }

  /// The tallest possible single footer state: estimate line + the longest warning.
  /// Rendered hidden to measure the height needed at the current Dynamic Type size.
  private var tallestFooterState: some View {
    VStack(alignment: .leading, spacing: 12) {
      HStack {
        Image(systemName: "xmark.circle.fill")
        Text(L10n.Settings.OfflineMaps.estimatedSize("999 GB"))
      }
      .font(.caption)

      Text(L10n.Settings.OfflineMaps.exceedsStorage)
        .font(.caption)
    }
  }
}

#Preview {
  NavigationStack {
    OfflineMapSettingsView()
      .environment(\.appState, AppState())
  }
}
