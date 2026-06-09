import MC1Services
import SwiftUI

struct MessageInfoSheet: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss

    let message: MessageDTO
    let senderName: String
    let pathViewModel: MessagePathViewModel

    @State private var showPathMap = false
    @State private var isLoading = true
    @State private var contacts: [ContactDTO] = []
    @State private var discoveredNodes: [DiscoveredNodeDTO] = []
    @State private var repeats: [MessageRepeatDTO]?

    private var availability: MessageActionAvailability {
        MessageActionAvailability(message: message)
    }

    var body: some View {
        NavigationStack {
            List {
                Section(senderName) {
                    if message.isOutgoing {
                        outgoingRows
                    } else {
                        incomingRows
                    }
                }

                if availability.canViewPath || availability.canShowRepeatDetails {
                    pathSection
                }
            }
            .navigationTitle(L10n.Chats.Chats.Message.Action.details)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.Localizable.Common.done) { dismiss() }
                }
                if availability.canViewPath {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(L10n.Chats.Chats.Path.title, systemImage: "map") {
                            showPathMap = true
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $showPathMap) {
            MessagePathMapView(message: message, pathViewModel: pathViewModel)
        }
        .task {
            guard let services = appState.services else { isLoading = false; return }
            if availability.canShowRepeatDetails {
                contacts = (try? await services.dataStore.fetchContacts(radioID: message.radioID)) ?? []
                discoveredNodes = (try? await services.dataStore.fetchDiscoveredNodes(radioID: message.radioID)) ?? []
                repeats = await services.heardRepeatsService.refreshRepeats(for: message.id)
            } else if availability.canViewPath {
                await pathViewModel.loadContacts(services: services, radioID: message.radioID)
            }
            isLoading = false
        }
    }

    @ViewBuilder
    private var outgoingRows: some View {
        Text(L10n.Chats.Chats.Message.Info.sent(
            message.senderDate.formatted(date: .abbreviated, time: .standard)))
        if let rtt = message.roundTripTime {
            Text(L10n.Chats.Chats.Message.Info.roundTrip(Int(rtt)))
        }
        if message.heardRepeats > 0 {
            let word = message.heardRepeats == 1
                ? L10n.Chats.Chats.Message.Repeat.singular
                : L10n.Chats.Chats.Message.Repeat.plural
            Text(L10n.Chats.Chats.Message.Info.heardRepeats(message.heardRepeats, word))
        }
    }

    @ViewBuilder
    private var incomingRows: some View {
        Text(L10n.Chats.Chats.Message.Info.hops(
            message.isDirectRouted ? L10n.Chats.Chats.Message.Hops.direct : "\(message.hopCount)"
        ))
        if let snr = message.snr {
            Text(L10n.Chats.Chats.Message.Info.snr(
                "\(snr.formatted(.number.precision(.fractionLength(1)))) dB (\(SNRQuality(snr: snr).localizedLabel))"
            ))
        }
        if message.routeType == .tcFlood {
            Text(message.regionScope.map { L10n.Chats.Chats.Message.Info.floodedUnder($0) }
                ?? L10n.Chats.Chats.Message.Info.regionUnresolved)
        }
        Text(L10n.Chats.Chats.Message.Info.sent(
            message.senderDate.formatted(date: .abbreviated, time: .standard))
            + (message.timestampCorrected ? " \(L10n.Chats.Chats.Message.Info.adjusted)" : ""))
        Text(L10n.Chats.Chats.Message.Info.received(
            message.createdAt.formatted(date: .abbreviated, time: .standard)))
    }

    @ViewBuilder
    private var pathSection: some View {
        if isLoading {
            Section { ProgressView().frame(maxWidth: .infinity) }
        } else if availability.canShowRepeatDetails {
            Section(L10n.Chats.Chats.Message.Action.repeatDetails) {
                RepeatDetailsContent(
                    repeats: repeats,
                    contacts: contacts,
                    discoveredNodes: discoveredNodes,
                    userLocation: appState.bestAvailableLocation
                )
            }
        } else if availability.canViewPath {
            Section(L10n.Chats.Chats.Path.title) {
                MessagePathContent(
                    message: message,
                    viewModel: pathViewModel,
                    receiverName: appState.connectedDevice?.nodeName ?? L10n.Chats.Chats.Path.Receiver.you,
                    userLocation: appState.bestAvailableLocation
                )
            }
        }
    }
}
