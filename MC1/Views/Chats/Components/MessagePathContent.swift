// MC1/Views/Chats/Components/MessagePathContent.swift
import CoreLocation
import MC1Services
import SwiftUI

/// Inline content for message path visualization, extracted from MessagePathSheet.
/// Shows sender, intermediate hops, receiver, raw path hex, and a copy button.
struct MessagePathContent: View {
    let message: MessageDTO
    let viewModel: MessagePathViewModel
    let receiverName: String
    let userLocation: CLLocation?

    @State private var copyHapticTrigger = 0

    /// Path hops chunked by hash size, each as (hashData, hexString) pair
    private var pathHops: [(data: Data, hex: String)] {
        guard let pathNodes = message.pathNodes else { return [] }
        let size = message.pathHashSize
        return stride(from: 0, to: pathNodes.count, by: size).map { start in
            let end = min(start + size, pathNodes.count)
            let chunk = Data(pathNodes[start..<end])
            return (chunk, chunk.uppercaseHexString())
        }
    }

    var body: some View {
        if viewModel.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity, alignment: .center)
                .padding()
        } else if message.pathNodes == nil {
            ContentUnavailableView(
                L10n.Chats.Chats.Path.Unavailable.title,
                systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                description: Text(L10n.Chats.Chats.Path.Unavailable.description)
            )
        } else {
            let senderResolution = viewModel.senderResolution(for: message)

            // Sender
            PathHopRowView(
                hopType: .sender,
                nodeName: senderResolution.displayName,
                nodeID: viewModel.senderNodeID(for: message),
                snr: nil,
                matchKind: senderResolution.matchKind
            )

            // Intermediate hops
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

            // Receiver
            PathHopRowView(
                hopType: .receiver,
                nodeName: receiverName,
                nodeID: nil,
                snr: message.snr
            )

            // Raw path hex + copy button
            if !pathHops.isEmpty {
                HStack {
                    Button(L10n.Chats.Chats.Path.copyButton, systemImage: "doc.on.doc") {
                        copyHapticTrigger += 1
                        UIPasteboard.general.string = message.pathStringForClipboard
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L10n.Chats.Chats.Path.copyAccessibility)
                    .accessibilityHint(L10n.Chats.Chats.Path.copyHint)

                    Text(message.pathString)
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
