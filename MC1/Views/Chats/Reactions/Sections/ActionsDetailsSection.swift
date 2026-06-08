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

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if availability.canShowRepeatDetails || availability.canViewPath {
                HStack(spacing: 0) {
                    ActionsExpandableDetailRow(
                        message: message,
                        availability: availability,
                        isDetailExpanded: $isDetailExpanded,
                        repeats: repeats,
                        contacts: contacts,
                        discoveredNodes: discoveredNodes,
                        pathViewModel: pathViewModel
                    )

                    if availability.canViewPath {
                        Divider()
                            .frame(height: 24)
                        Button {
                            showPathMap = true
                        } label: {
                            Image(systemName: "map")
                                .frame(width: 44, height: 44)
                                .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(L10n.Chats.Chats.Path.title)
                    }
                }
                .sheet(isPresented: $showPathMap) {
                    MessagePathMapView(message: message, pathViewModel: pathViewModel)
                }
            }

            Text(L10n.Chats.Chats.Message.Action.details)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
                .padding(.top, 12)
                .padding(.bottom, 4)

            if message.isOutgoing {
                ActionsOutgoingDetailsRows(message: message)
            } else {
                ActionsIncomingDetailsRows(message: message)
            }
        }
    }
}

private struct ActionsExpandableDetailRow: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let message: MessageDTO
    let availability: MessageActionAvailability
    @Binding var isDetailExpanded: Bool
    let repeats: [MessageRepeatDTO]?
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let pathViewModel: MessagePathViewModel

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(reduceMotion ? nil : .default) {
                    isDetailExpanded.toggle()
                }
            } label: {
                HStack {
                    Label(
                        availability.canShowRepeatDetails
                            ? L10n.Chats.Chats.Message.Action.repeatDetails
                            : L10n.Chats.Chats.Message.Action.viewPath,
                        systemImage: availability.canShowRepeatDetails
                            ? "arrow.triangle.branch"
                            : "point.topleft.down.to.point.bottomright.curvepath"
                    )
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
            .accessibilityValue(
                isDetailExpanded
                    ? L10n.Chats.Chats.Message.Action.expanded
                    : L10n.Chats.Chats.Message.Action.collapsed
            )

            if isDetailExpanded {
                Divider()
                    .padding(.horizontal)
                ActionsExpandedContent(
                    message: message,
                    availability: availability,
                    repeats: repeats,
                    contacts: contacts,
                    discoveredNodes: discoveredNodes,
                    pathViewModel: pathViewModel
                )
                .padding(.horizontal)
                .padding(.bottom)
                .id("expandedContent")
            }
        }
    }
}

private struct ActionsExpandedContent: View {
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
        } else if availability.canViewPath {
            MessagePathContent(
                message: message,
                viewModel: pathViewModel,
                receiverName: appState.connectedDevice?.nodeName ?? L10n.Chats.Chats.Path.Receiver.you,
                userLocation: appState.bestAvailableLocation
            )
        }
    }
}

private struct ActionsOutgoingDetailsRows: View {
    let message: MessageDTO

    var body: some View {
        ActionInfoRow(text: L10n.Chats.Chats.Message.Info.sent(
            message.senderDate.formatted(date: .abbreviated, time: .standard)))

        if let rtt = message.roundTripTime {
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.roundTrip(Int(rtt)))
        }

        if message.heardRepeats > 0 {
            let word = message.heardRepeats == 1
                ? L10n.Chats.Chats.Message.Repeat.singular
                : L10n.Chats.Chats.Message.Repeat.plural
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.heardRepeats(message.heardRepeats, word))
        }
    }
}

private struct ActionsIncomingDetailsRows: View {
    let message: MessageDTO

    var body: some View {
        ActionInfoRow(
            text: L10n.Chats.Chats.Message.Info.hops(hopCountFormatted(message)),
            icon: "arrowshape.bounce.right"
        )

        if let hashSize = message.pathHashSizeIfKnown {
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.pathHash(hashSize))
        }

        if message.routeType == .tcFlood {
            ActionInfoRow(
                text: message.regionScope.map { L10n.Chats.Chats.Message.Info.floodedUnder($0) }
                    ?? L10n.Chats.Chats.Message.Info.regionUnresolved,
                icon: "globe"
            )
        }

        let sentText = L10n.Chats.Chats.Message.Info.sent(
            message.senderDate.formatted(date: .abbreviated, time: .standard))
        let adjusted = message.timestampCorrected ? " " + L10n.Chats.Chats.Message.Info.adjusted : ""
        ActionInfoRow(text: sentText + adjusted)

        if message.timestampCorrected {
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.originalSendTime(
                message.wireSentDate.formatted(date: .abbreviated, time: .standard)))
        }

        ActionInfoRow(text: L10n.Chats.Chats.Message.Info.received(
            message.createdAt.formatted(date: .abbreviated, time: .standard)))

        if let snr = message.snr {
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.snr(snrFormatted(snr)))
        }
    }

    private func snrFormatted(_ snr: Double) -> String {
        let quality = SNRQuality(snr: snr).localizedLabel
        return "\(snr.formatted(.number.precision(.fractionLength(1)))) dB (\(quality))"
    }

    private func hopCountFormatted(_ message: MessageDTO) -> String {
        if message.isDirectRouted {
            return L10n.Chats.Chats.Message.Hops.direct
        }
        return "\(message.hopCount)"
    }
}

private struct ActionInfoRow: View {
    let text: String
    var icon: String?

    var body: some View {
        HStack {
            if let icon {
                Image(systemName: icon)
                    .foregroundStyle(.secondary)
            }
            Text(text)
            Spacer()
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .padding(.horizontal)
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(text)
    }
}
