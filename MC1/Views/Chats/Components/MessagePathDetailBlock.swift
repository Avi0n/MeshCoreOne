import MC1Services
import SwiftUI

/// Capsules, compact preview map, hop list, and expand-to-full-map sheet.
struct MessagePathDetailBlock: View {
  private static let expandedContentTopPadding: CGFloat = 8
  private static let outgoingDisclosureSymbol = "arrow.triangle.branch"
  private static let incomingDisclosureSymbol = "point.topleft.down.to.point.bottomright.curvepath"

  @Environment(\.appState) private var appState
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.colorScheme) private var colorScheme

  let message: MessageDTO
  let arrivals: [MessagePathArrival]
  let pathViewModel: MessagePathViewModel
  @Binding var isDetailExpanded: Bool

  @State private var selectedID: UUID?
  @State private var showFullMap = false
  @State private var containerWidth: CGFloat = 0

  var body: some View {
    VStack(spacing: 0) {
      disclosureButton

      if isDetailExpanded {
        Divider()
          .padding(.horizontal, MessagePathPreviewSnapshot.expandedHorizontalPadding)
        expandedContent
          .padding(.horizontal, MessagePathPreviewSnapshot.expandedHorizontalPadding)
          .padding(.top, Self.expandedContentTopPadding)
          .padding(.bottom)
          .id("expandedContent")
      }
    }
    .onGeometryChange(for: CGFloat.self) { proxy in
      proxy.size.width
    } action: { containerWidth = $0 }
    .task(id: prefetchID) {
      await pathViewModel.prefetchPreviews(
        message: message,
        arrivals: arrivals,
        selectedID: selectedID,
        connectedDevice: appState.connectedDevice,
        userLocation: appState.bestAvailableLocation,
        isDark: colorScheme == .dark,
        isOffline: isOffline,
        containerWidth: containerWidth
      )
    }
    .onAppear {
      selectedID = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    }
    .onChange(of: arrivals.map(\.id)) { _, _ in
      selectedID = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    }
    .sheet(isPresented: $showFullMap) {
      MessagePathMapView(
        message: message,
        arrivals: arrivals,
        selectedID: selectedBinding,
        pathViewModel: pathViewModel
      )
    }
  }

  private var selectedBinding: Binding<UUID?> {
    Binding(
      get: { MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals) },
      set: { selectedID = $0 }
    )
  }

  private var selectedArrival: MessagePathArrival? {
    let selected = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    return arrivals.first { $0.id == selected } ?? arrivals.first
  }

  private var disclosureButton: some View {
    Button {
      withAnimation(reduceMotion ? nil : .default) {
        isDetailExpanded.toggle()
      }
    } label: {
      HStack {
        Label(disclosureTitle, systemImage: disclosureSymbol)
        Spacer()
        Image(systemName: "chevron.right")
          .rotationEffect(.degrees(isDetailExpanded ? 90 : 0))
          .foregroundStyle(.secondary)
          .font(.caption)
          .accessibilityHidden(true)
      }
      .padding()
      .contentShape(.rect)
    }
    .foregroundStyle(.primary)
    .accessibilityLabel(disclosureTitle)
    .accessibilityValue(
      isDetailExpanded
        ? L10n.Chats.Chats.Message.Action.expanded
        : L10n.Chats.Chats.Message.Action.collapsed
    )
  }

  private var disclosureTitle: String {
    message.isOutgoing
      ? L10n.Chats.Chats.Message.Action.repeatDetails
      : L10n.Chats.Chats.Message.Action.pathDetails
  }

  private var disclosureSymbol: String {
    message.isOutgoing
      ? Self.outgoingDisclosureSymbol
      : Self.incomingDisclosureSymbol
  }

  private var expandedContent: some View {
    VStack(alignment: .leading, spacing: 12) {
      if arrivals.count > 1 {
        MessagePathArrivalCapsules(
          arrivals: arrivals,
          selectedID: selectedBinding,
          reduceMotion: reduceMotion
        )
      }

      previewMap

      if let selectedArrival {
        MessagePathContent(
          message: message,
          arrival: selectedArrival,
          viewModel: pathViewModel,
          receiverName: appState.connectedDevice?.nodeName ?? L10n.Chats.Chats.Path.Receiver.you,
          userLocation: appState.bestAvailableLocation
        )
      }
    }
  }

  private var previewMapModel: MessagePathMapView.CanvasModel? {
    MessagePathMapView.canvasModel(
      message: message,
      arrivals: arrivals,
      selectedID: selectedBinding.wrappedValue,
      pathViewModel: pathViewModel,
      connectedDevice: appState.connectedDevice,
      userLocation: appState.bestAvailableLocation
    )
  }

  private var isOffline: Bool {
    !appState.offlineMapService.isNetworkAvailable
  }

  private var previewKey: MessagePathPreviewSnapshot.Key? {
    guard let arrivalID = selectedArrival?.id else { return nil }
    return MessagePathPreviewSnapshot.key(
      arrivalID: arrivalID,
      isDark: colorScheme == .dark,
      isOffline: isOffline,
      containerWidth: containerWidth
    )
  }

  private var prefetchID: PrefetchID {
    PrefetchID(
      arrivalIDs: arrivals.map(\.id),
      isLoading: pathViewModel.isLoading,
      isDark: colorScheme == .dark,
      isOffline: isOffline,
      widthBucket: MessagePathPreviewSnapshot.bucketedWidth(
        max(0, containerWidth - 2 * MessagePathPreviewSnapshot.expandedHorizontalPadding)
      )
    )
  }

  @ViewBuilder
  private var previewMap: some View {
    if previewMapModel?.showsPathMap == true, let key = previewKey {
      if let image = pathViewModel.previewImage(for: key) {
        MessagePathPreviewMap(
          image: image,
          didFail: false,
          onExpand: { showFullMap = true },
          onRetry: {}
        )
      } else if pathViewModel.previewFailed(for: key) {
        MessagePathPreviewMap(
          image: nil,
          didFail: true,
          onExpand: { showFullMap = true },
          onRetry: {
            Task {
              await pathViewModel.retryPreview(
                arrivalID: key.arrivalID,
                message: message,
                arrivals: arrivals,
                connectedDevice: appState.connectedDevice,
                userLocation: appState.bestAvailableLocation,
                isDark: colorScheme == .dark,
                isOffline: isOffline,
                containerWidth: containerWidth
              )
            }
          }
        )
      } else if let stale = pathViewModel.lastPreviewImage {
        MessagePathPreviewMap(
          image: stale,
          didFail: false,
          onExpand: { showFullMap = true },
          onRetry: {}
        )
      }
    }
  }

  private struct PrefetchID: Hashable {
    let arrivalIDs: [UUID]
    let isLoading: Bool
    let isDark: Bool
    let isOffline: Bool
    let widthBucket: Int
  }
}
