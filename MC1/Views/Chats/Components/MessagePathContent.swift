import CoreLocation
import MC1Services
import SwiftUI

/// Hop list for one arrival: sender, hops, receiver, and copyable path hex.
struct MessagePathContent: View {
  let message: MessageDTO
  let arrival: MessagePathArrival
  let viewModel: MessagePathViewModel
  let receiverName: String
  let userLocation: CLLocation?

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
      let senderResolution = viewModel.senderResolution(for: message, localDeviceName: receiverName)
      let pathHops = arrival.pathHops

      PathHopRowView(
        hopType: .sender,
        nodeName: senderResolution.displayName,
        nodeID: viewModel.senderNodeID(for: message),
        snr: nil,
        matchKind: senderResolution.matchKind
      )

      ForEach(Array(pathHops.enumerated()), id: \.offset) { index, hop in
        let repeaterResolution = viewModel.repeaterResolution(
          for: hop.data,
          userLocation: userLocation
        )
        PathHopRowView(
          hopType: .intermediate(index + 1),
          nodeName: repeaterResolution.displayName,
          nodeID: hop.hex,
          snr: nil,
          matchKind: repeaterResolution.matchKind
        )
      }

      PathHopRowView(
        hopType: .receiver,
        nodeName: receiverName,
        nodeID: nil,
        snr: arrival.snr
      )

      if !pathHops.isEmpty {
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
