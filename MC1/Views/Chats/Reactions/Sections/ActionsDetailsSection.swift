import MC1Services
import SwiftUI

struct ActionsDetailsSection: View {
    let message: MessageDTO
    let availability: MessageActionAvailability
    @Binding var isDetailExpanded: Bool
    let repeats: [MessageRepeatDTO]?
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let pathViewModel: MessagePathViewModel

    @State private var showPathMap = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var rowLabel: String {
        if availability.canShowRepeatDetails { return L10n.Chats.Chats.Message.Action.repeatDetails }
        if availability.canViewPath { return L10n.Chats.Chats.Message.Action.viewPath }
        return L10n.Chats.Chats.Message.Action.details
    }

    private var rowIcon: String {
        if availability.canShowRepeatDetails { return "arrow.triangle.branch" }
        if availability.canViewPath { return "point.topleft.down.to.point.bottomright.curvepath" }
        return "info.circle"
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Button {
                    withAnimation(reduceMotion ? nil : .default) {
                        isDetailExpanded.toggle()
                    }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: rowIcon)
                            .font(.body)
                            .frame(width: 24, alignment: .center)
                        Text(rowLabel)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .rotationEffect(.degrees(isDetailExpanded ? 90 : 0))
                            .foregroundStyle(.secondary)
                            .font(.caption.weight(.semibold))
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .contentShape(.rect)
                }
                .foregroundStyle(.primary)
                .buttonStyle(.plain)
                .accessibilityValue(isDetailExpanded
                    ? L10n.Chats.Chats.Message.Action.expanded
                    : L10n.Chats.Chats.Message.Action.collapsed)

                if availability.canViewPath {
                    Rectangle()
                        .fill(.separator)
                        .frame(width: 0.5, height: 24)
                    Button { showPathMap = true } label: {
                        Image(systemName: "map")
                            .font(.body)
                            .frame(width: 52, height: 52)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(L10n.Chats.Chats.Path.title)
                }
            }

            if isDetailExpanded {
                Divider().padding(.leading, 52)

                VStack(spacing: 0) {
                    if message.isOutgoing {
                        outgoingRows
                    } else {
                        incomingRows
                    }
                }

                if availability.canShowRepeatDetails || availability.canViewPath {
                    Divider().padding(.leading, 52)
                    ExpandedPathContent(
                        message: message,
                        availability: availability,
                        repeats: repeats,
                        contacts: contacts,
                        discoveredNodes: discoveredNodes,
                        pathViewModel: pathViewModel
                    )
                    .padding(.bottom, 4)
                }
            }
        }
        .sheet(isPresented: $showPathMap) {
            MessagePathMapView(message: message, pathViewModel: pathViewModel)
        }
    }

    private var outgoingRows: some View {
        VStack(spacing: 0) {
            InfoRow(text: L10n.Chats.Chats.Message.Info.sent(
                message.senderDate.formatted(date: .abbreviated, time: .standard)))
            if let rtt = message.roundTripTime {
                Divider().padding(.leading, 52)
                InfoRow(text: L10n.Chats.Chats.Message.Info.roundTrip(Int(rtt)))
            }
            if message.heardRepeats > 0 {
                let word = message.heardRepeats == 1
                    ? L10n.Chats.Chats.Message.Repeat.singular
                    : L10n.Chats.Chats.Message.Repeat.plural
                Divider().padding(.leading, 52)
                InfoRow(text: L10n.Chats.Chats.Message.Info.heardRepeats(message.heardRepeats, word))
            }
        }
    }

    private var incomingRows: some View {
        VStack(spacing: 0) {
            InfoRow(
                text: L10n.Chats.Chats.Message.Info.hops(hopCountFormatted(message)),
                icon: "arrowshape.bounce.right"
            )
            if message.routeType == .tcFlood {
                Divider().padding(.leading, 52)
                InfoRow(
                    text: message.regionScope.map { L10n.Chats.Chats.Message.Info.floodedUnder($0) }
                        ?? L10n.Chats.Chats.Message.Info.regionUnresolved,
                    icon: "globe"
                )
            }
            Divider().padding(.leading, 52)
            InfoRow(text: L10n.Chats.Chats.Message.Info.sent(
                message.senderDate.formatted(date: .abbreviated, time: .standard)
            ) + (message.timestampCorrected ? " " + L10n.Chats.Chats.Message.Info.adjusted : ""))
            Divider().padding(.leading, 52)
            InfoRow(text: L10n.Chats.Chats.Message.Info.received(
                message.createdAt.formatted(date: .abbreviated, time: .standard)))
            if let snr = message.snr {
                Divider().padding(.leading, 52)
                InfoRow(text: L10n.Chats.Chats.Message.Info.snr(snrString(snr)))
            }
        }
    }

    private func hopCountFormatted(_ message: MessageDTO) -> String {
        message.isDirectRouted ? L10n.Chats.Chats.Message.Hops.direct : "\(message.hopCount)"
    }

    private func snrString(_ snr: Double) -> String {
        "\(snr.formatted(.number.precision(.fractionLength(1)))) dB (\(SNRQuality(snr: snr).localizedLabel))"
    }
}

private struct InfoRow: View {
    let text: String
    var icon: String?

    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon)
                    .font(.body)
                    .frame(width: 24, alignment: .center)
                    .foregroundStyle(.tertiary)
            } else {
                Color.clear.frame(width: 24)
            }
            Text(text)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .font(.subheadline)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(text)
    }
}

private struct ExpandedPathContent: View {
    @Environment(\.appState) private var appState

    let message: MessageDTO
    let availability: MessageActionAvailability
    let repeats: [MessageRepeatDTO]?
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let pathViewModel: MessagePathViewModel

    var body: some View {
        if availability.canShowRepeatDetails {
            RepeatDetailsContent(
                repeats: repeats,
                contacts: contacts,
                discoveredNodes: discoveredNodes,
                userLocation: appState.bestAvailableLocation
            )
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        } else if availability.canViewPath {
            MessagePathContent(
                message: message,
                viewModel: pathViewModel,
                receiverName: appState.connectedDevice?.nodeName ?? L10n.Chats.Chats.Path.Receiver.you,
                userLocation: appState.bestAvailableLocation
            )
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .id("expandedContent")
        }
    }
}
