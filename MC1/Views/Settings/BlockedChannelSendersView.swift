import OSLog
import MC1Services
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "BlockedChannelSendersView")

/// Settings screen listing blocked channel sender names with swipe-to-unblock.
struct BlockedChannelSendersView: View {
    @Environment(\.appState) private var appState

    @State private var blockedSenders: [BlockedChannelSenderDTO] = []
    @State private var isLoading = false

    var body: some View {
        Group {
            if isLoading {
                ProgressView()
            } else if blockedSenders.isEmpty {
                ContentUnavailableView(
                    L10n.Settings.Blocking.ChannelSenders.Empty.title,
                    systemImage: "hand.raised.slash",
                    description: Text(L10n.Settings.Blocking.ChannelSenders.Empty.description)
                )
            } else {
                List {
                    ForEach(blockedSenders) { sender in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(sender.name)
                                .font(.body)
                            Text(sender.dateBlocked, format: .dateTime.month().day().year())
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .onDelete(perform: unblock)
                }
            }
        }
        .navigationTitle(L10n.Settings.Blocking.ChannelSenders.title)
        .toolbar {
            if !blockedSenders.isEmpty {
                EditButton()
            }
        }
        .task {
            await loadBlockedSenders()
        }
    }

    private func loadBlockedSenders() async {
        guard let services = appState.services,
              let radioID = appState.connectedDevice?.radioID else { return }
        isLoading = true
        defer { isLoading = false }

        do {
            blockedSenders = try await services.dataStore.fetchBlockedChannelSenders(
                radioID: radioID
            )
        } catch {
            logger.error("Failed to load blocked channel senders: \(error)")
            blockedSenders = []
        }
    }

    private func unblock(at offsets: IndexSet) {
        let sendersToUnblock = offsets.map { blockedSenders[$0] }
        blockedSenders.remove(atOffsets: offsets)

        Task {
            guard let services = appState.services,
                  let radioID = appState.connectedDevice?.radioID else { return }

            for sender in sendersToUnblock {
                do {
                    try await services.dataStore.deleteBlockedChannelSender(
                        radioID: radioID,
                        name: sender.name
                    )
                } catch {
                    logger.error("Failed to delete blocked sender '\(sender.name)': \(error)")
                }
            }

            await services.syncCoordinator.refreshBlockedContactsCache(
                radioID: radioID,
                dataStore: services.dataStore
            )
            services.syncCoordinator.notifyConversationsChanged()
        }
    }
}
