import MC1Services
import SwiftUI

/// Paths and maps for each arrival. Heard repeats of a sent message are arrivals too.
struct MessagePathDetailBlock: View {
  private static let expandedContentTopPadding: CGFloat = 8
  private static let expandedHorizontalPadding: CGFloat = 16
  private static let outgoingDisclosureSymbol = "arrow.triangle.branch"
  private static let incomingDisclosureSymbol = "point.topleft.down.to.point.bottomright.curvepath"

  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  let message: MessageDTO
  let arrivals: [MessagePathArrival]
  let repeats: [MessageRepeatDTO]?
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
          .padding(.horizontal, Self.expandedHorizontalPadding)
        expandedContent
          .padding(.horizontal, Self.expandedHorizontalPadding)
          .padding(.top, Self.expandedContentTopPadding)
          .padding(.bottom)
          .id("expandedContent")
      }
    }
    .onGeometryChange(for: CGFloat.self) { proxy in
      proxy.size.width
    } action: { containerWidth = $0 }
    .messagePathMapPreviews(
      sender: sender,
      arrivals: arrivals,
      selectedID: selectedBinding,
      showFullMap: $showFullMap,
      pathViewModel: pathViewModel,
      previewWidth: previewWidth
    )
    .onAppear {
      selectedID = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    }
    .onChange(of: arrivals.map(\.id)) { _, _ in
      selectedID = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    }
  }

  private var selectedBinding: Binding<UUID?> {
    Binding(
      get: { MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals) },
      set: { selectedID = $0 }
    )
  }

  private var previewWidth: CGFloat {
    max(0, containerWidth - 2 * Self.expandedHorizontalPadding)
  }

  private var sender: MessagePathSender {
    MessagePathSender(message: message)
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
      if message.isOutgoing, repeats == nil {
        ProgressView()
          .frame(maxWidth: .infinity, alignment: .center)
          .padding()
      } else if message.isOutgoing, arrivals.isEmpty {
        ContentUnavailableView(
          L10n.Chats.Chats.Repeats.EmptyState.title,
          systemImage: "arrow.triangle.branch",
          description: Text(L10n.Chats.Chats.Repeats.EmptyState.description)
        )
      } else {
        pathDetail
      }
    }
  }

  private var pathDetail: some View {
    MessagePathArrivalDetail(
      sender: sender,
      arrivals: arrivals,
      selectedID: selectedBinding,
      pathViewModel: pathViewModel,
      previewWidth: previewWidth,
      onShowFullMap: { showFullMap = true }
    )
  }
}
