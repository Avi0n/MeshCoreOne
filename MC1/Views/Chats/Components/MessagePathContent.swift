import CoreLocation
import MC1Services
import SwiftUI

/// Hop list for one arrival: sender, hops, receiver, and optional copyable path hex.
struct MessagePathContent: View {
  let sender: MessagePathSender
  let arrival: MessagePathArrival
  let viewModel: MessagePathViewModel
  let receiverName: String
  let userLocation: CLLocation?
  var showsCopyBar: Bool = true

  @State private var copyHapticTrigger = 0

  var body: some View {
    if viewModel.isLoading {
      ProgressView()
        .frame(maxWidth: .infinity, alignment: .center)
        .padding()
    } else if arrival.isPathUnavailable {
      ContentUnavailableView(
        L10n.Chats.Chats.Path.Unavailable.title,
        systemImage: "point.topleft.down.to.point.bottomright.curvepath",
        description: Text(L10n.Chats.Chats.Path.Unavailable.description)
      )
    } else {
      let senderResolution = viewModel.senderResolution(for: sender, localDeviceName: receiverName)
      let pathHops = arrival.pathHops

      PathHopRowView(
        hopType: .sender,
        nodeName: senderResolution.displayName,
        nodeID: viewModel.senderNodeID(for: sender),
        snr: nil,
        matchKind: senderResolution.matchKind
      )

      PathRepeaterHopRows(
        hashes: pathHops.map(\.data),
        viewModel: viewModel,
        userLocation: userLocation
      )

      PathHopRowView(
        hopType: .receiver,
        nodeName: receiverName,
        nodeID: nil,
        snr: arrival.snr,
        rssi: arrival.rssi
      )

      if showsCopyBar, !pathHops.isEmpty {
        HStack {
          Button(L10n.Chats.Chats.Path.copyButton, systemImage: "doc.on.doc") {
            copyHapticTrigger += 1
            UIPasteboard.general.string = arrival.pathStringForClipboard
          }
          .labelStyle(.iconOnly)
          .buttonStyle(.borderless)
          .accessibilityLabel(L10n.Chats.Chats.Path.copyAccessibility)
          .accessibilityHint(L10n.Chats.Chats.Path.copyHint)

          Text(arrival.pathString)
            .font(.caption.monospaced())
            .foregroundStyle(.secondary)

          Spacer()
        }
        .padding(.top, 8)
        .sensoryFeedback(.success, trigger: copyHapticTrigger)
      }
    }
  }
}
