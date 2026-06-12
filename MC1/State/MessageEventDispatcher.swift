import Foundation
import MC1Services

/// Owns the service event-stream subscriptions that feed `MessageEventStream`
/// and the session-state counter on `AppState`. Extracted from
/// `AppState.wireMessageEvents` to keep `AppState` focused on app-level state.
///
/// Holds `AppState` weakly to avoid extending its lifetime; the stream is held
/// strongly because the dispatcher is the sole producer.
///
/// Each subscription is registered synchronously in the wiring method before
/// its consuming task starts, so events emitted during the connection-ready /
/// sync-start window are never dropped. Each consuming task then `for await`s
/// the captured stream. `ServiceContainer.tearDown()` finishes every stream
/// and `cancelAll()` cancels the tasks, so neither a stale container nor a
/// stale subscription can outlive a reconnect.
@MainActor
final class MessageEventDispatcher {
    private weak var appState: AppState?
    private let stream: MessageEventStream

    /// Stream-consuming tasks, cancelled on re-wire and on disconnect.
    /// Each consumed stream also ends when its `ServiceContainer` is torn down.
    private var tasks: [Task<Void, Never>] = []

    init(appState: AppState, stream: MessageEventStream) {
        self.appState = appState
        self.stream = stream
    }

    func wire(services: ServiceContainer) {
        cancelAll()
        wireSyncCoordinator(services.syncCoordinator)
        wireHeardRepeats(services.heardRepeatsService)
        wireRemoteNode(services.remoteNodeService)
        wireRoomServer(services.roomServerService)
        wireMessageService(services.messageService)
    }

    /// Cancels every stream-consuming task. Called before re-wiring against a
    /// fresh `ServiceContainer` and from `AppState`'s disconnect teardown.
    func cancelAll() {
        for task in tasks {
            task.cancel()
        }
        tasks.removeAll()
    }

    private func wireSyncCoordinator(_ syncCoordinator: SyncCoordinator) {
        let events = syncCoordinator.dataEvents()
        let task = Task { [weak appState, stream] in
            for await event in events {
                switch event {
                case .directMessageReceived(let message, let contact):
                    stream.send(.directMessageReceived(message: message, contact: contact))
                case .channelMessageReceived(let message, let channelIndex):
                    stream.send(.channelMessageReceived(message: message, channelIndex: channelIndex))
                case .roomMessageReceived(let message):
                    stream.send(.roomMessageReceived(message: message, sessionID: message.sessionID))
                case .reactionReceived(let messageID, let summary):
                    stream.send(.reactionReceived(messageID: messageID, summary: summary))
                    await appState?.handleReactionNotification(messageID: messageID)
                case .contactsChanged, .conversationsChanged:
                    break
                }
            }
        }
        tasks.append(task)
    }

    private func wireHeardRepeats(_ heardRepeatsService: HeardRepeatsService) {
        let events = heardRepeatsService.events()
        let task = Task { [stream] in
            for await event in events {
                stream.send(.heardRepeatRecorded(messageID: event.messageID, count: event.count))
            }
        }
        tasks.append(task)
    }

    private func wireRemoteNode(_ remoteNodeService: RemoteNodeService) {
        let events = remoteNodeService.events()
        let task = Task { [weak appState] in
            for await event in events {
                switch event {
                case .sessionStateChanged:
                    appState?.handleSessionStateChange()
                }
            }
        }
        tasks.append(task)
    }

    private func wireRoomServer(_ roomServerService: RoomServerService) {
        let events = roomServerService.events()
        let task = Task { [weak appState, stream] in
            for await event in events {
                switch event {
                case .statusUpdated(let messageID, let status):
                    if status == .failed {
                        stream.send(.roomMessageFailed(messageID: messageID))
                    } else {
                        stream.send(.roomMessageStatusUpdated(messageID: messageID))
                    }
                case .connectionRecovered:
                    appState?.handleSessionStateChange()
                }
            }
        }
        tasks.append(task)
    }

    private func wireMessageService(_ messageService: MessageService) {
        let events = messageService.statusEvents()
        let task = Task { [stream] in
            for await event in events {
                switch event {
                case .statusResolved(let messageID, let status, let roundTripTime):
                    stream.send(.messageStatusResolved(messageID: messageID, status: status, roundTripTime: roundTripTime))
                case .resent(let messageID):
                    stream.send(.messageResent(messageID: messageID))
                case .retrying(let messageID, let attempt, let maxAttempts):
                    stream.send(.messageRetrying(messageID: messageID, attempt: attempt, maxAttempts: maxAttempts))
                case .routingChanged(let contactID, let isFlood):
                    stream.send(.routingChanged(contactID: contactID, isFlood: isFlood))
                case .failed(let messageID):
                    stream.send(.messageFailed(messageID: messageID))
                }
            }
        }
        tasks.append(task)
    }
}
