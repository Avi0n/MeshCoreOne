import SwiftUI

struct RegionPickerBottomCard: View {
  @Binding var regionName: String
  @Binding var includeTopo: Bool
  @Binding var baseMaximumZoom: Int
  @Binding var topographyMaximumZoom: Int
  @FocusState.Binding var nameIsFocused: Bool
  let downloadSummary: OfflineMapDownloadPolicy.Summary?
  let exceedsAvailableSpace: Bool
  let exceedsProviderLimit: Bool
  let isNetworkAvailable: Bool
  let isDownloading: Bool
  let maximumHeight: CGFloat

  private static let largeDownloadThreshold: Int64 = 500_000_000
  private static let overviewMaximumZoom = 4
  private static let regionalMaximumZoom = 8
  private static let localMaximumZoom = 11
  private static let contentSpacing: CGFloat = 12
  private static let cornerRadius: CGFloat = 12
  private static let bottomPadding: CGFloat = 4
  private static let estimateMinimumScale: CGFloat = 0.5
  private static let estimateLineLimit = 2

  @State private var contentHeight: CGFloat = 0

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: Self.contentSpacing) {
        TextField(L10n.Settings.OfflineMaps.regionName, text: $regionName)
          .textFieldStyle(.plain)
          .focused($nameIsFocused)
          .submitLabel(.done)
          .onSubmit { nameIsFocused = false }

        Divider()

        Picker(L10n.Settings.OfflineMaps.baseDetail, selection: $baseMaximumZoom) {
          ForEach(OfflineMapLayer.base.selectableMaximumZoom, id: \.self) { zoom in
            Text(detailValue(for: zoom)).tag(zoom)
          }
        }
        .pickerStyle(.menu)

        Toggle(L10n.Settings.OfflineMaps.Layer.topo, isOn: $includeTopo)
          .toggleStyle(.switch)

        if includeTopo {
          Picker(L10n.Settings.OfflineMaps.topographyDetail, selection: $topographyMaximumZoom) {
            ForEach(OfflineMapLayer.topo.selectableMaximumZoom, id: \.self) { zoom in
              Text(detailValue(for: zoom)).tag(zoom)
            }
          }
          .pickerStyle(.menu)
        }

        Text(L10n.Settings.OfflineMaps.Detail.explanation)
          .font(.caption)
          .foregroundStyle(.secondary)

        Divider()
        footerContent
      }
      .padding()
      .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
    }
    .scrollBounceBehavior(.basedOnSize)
    .scrollDismissesKeyboard(.interactively)
    .frame(height: min(contentHeight, max(0, maximumHeight - Self.bottomPadding)))
    .background(.regularMaterial, in: .rect(cornerRadius: Self.cornerRadius))
    .padding(.horizontal)
    .padding(.bottom, Self.bottomPadding)
  }

  private func detailValue(for zoom: Int) -> String {
    let tier = switch zoom {
    case ...Self.overviewMaximumZoom: L10n.Settings.OfflineMaps.Detail.overview
    case ...Self.regionalMaximumZoom: L10n.Settings.OfflineMaps.Detail.regional
    case ...Self.localMaximumZoom: L10n.Settings.OfflineMaps.Detail.local
    default: L10n.Settings.OfflineMaps.Detail.detailed
    }
    return L10n.Settings.OfflineMaps.Detail.value(tier, zoom.formatted())
  }

  private var footerContent: some View {
    VStack(alignment: .leading, spacing: Self.contentSpacing) {
      if let summary = downloadSummary {
        VStack(alignment: .leading) {
          Text(L10n.Settings.OfflineMaps.estimatedSize(summary.estimatedBytes.formatted(.byteCount(style: .file))))
            .lineLimit(Self.estimateLineLimit, reservesSpace: true)
            .minimumScaleFactor(Self.estimateMinimumScale)
          ForEach([OfflineMapLayer.base, .topo], id: \.self) { layer in
            if let estimate = summary.layers.first(where: { $0.layer == layer }) {
              layerEstimateRow(layer: layer, bytes: estimate.estimatedBytes)
            } else {
              layerEstimateRow(layer: layer, bytes: summary.estimatedBytes)
                .hidden()
                .accessibilityHidden(true)
            }
          }
          Text(L10n.Settings.OfflineMaps.estimateUncertainty)
        }
        .foregroundStyle(.secondary)
      }

      // Stable warning space prevents validation changes from resizing the selected map area.
      ZStack(alignment: .topLeading) {
        ForEach(warningMessages, id: \.self) { message in
          Text(message).hidden()
        }
        .accessibilityHidden(true)
        warningContent
      }
    }
    .font(.caption)
  }

  private func layerEstimateRow(layer: OfflineMapLayer, bytes: Int64) -> some View {
    Text(L10n.Settings.OfflineMaps.layerEstimate(layer.label, bytes.formatted(.byteCount(style: .file))))
      .lineLimit(Self.estimateLineLimit, reservesSpace: true)
      .minimumScaleFactor(Self.estimateMinimumScale)
  }

  private var warningMessages: [String] {
    [
      L10n.Settings.OfflineMaps.noNetwork,
      L10n.Settings.OfflineMaps.Error.providerLimitExceeded,
      L10n.Settings.OfflineMaps.storageRemediation,
      L10n.Settings.OfflineMaps.largeTileWarning,
      L10n.Settings.OfflineMaps.downloadHint,
      L10n.Settings.OfflineMaps.startingDownload,
    ]
  }

  @ViewBuilder
  private var warningContent: some View {
    if isDownloading {
      ProgressView(L10n.Settings.OfflineMaps.startingDownload)
    } else if !isNetworkAvailable {
      Text(L10n.Settings.OfflineMaps.noNetwork)
        .foregroundStyle(.orange)
    } else if exceedsProviderLimit {
      Text(L10n.Settings.OfflineMaps.Error.providerLimitExceeded)
        .foregroundStyle(.orange)
    } else if exceedsAvailableSpace {
      Text(L10n.Settings.OfflineMaps.storageRemediation)
        .foregroundStyle(.orange)
    } else if let bytes = downloadSummary?.estimatedBytes, bytes > Self.largeDownloadThreshold {
      Text(L10n.Settings.OfflineMaps.largeTileWarning)
        .foregroundStyle(.orange)
    } else {
      Text(L10n.Settings.OfflineMaps.downloadHint)
        .foregroundStyle(.secondary)
    }
  }
}
