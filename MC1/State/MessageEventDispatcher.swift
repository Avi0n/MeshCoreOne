import Foundation
import MC1Services

/// Owns the service-callback registrations that feed `MessageEventStream`
/// and the session-state counter on `AppState`. Extracted from
/// `AppState.wireMessageEvents` to keep `AppState` focused on app-level state.
///
/// Holds `AppState` weakly to avoid extending its lifetime; the stream is held
/// strongly because the dispatcher is the sole producer.
///
/// Each callback wraps `stream.send(.foo)` in `await MainActor.run { ... }`.
/// The hop is correct because callbacks are `@Sendable` closures stored on
/// producer actors (`SyncCoordinator`, `MessageService`, etc.) and invoked from
/// that actor's isolation; `MainActor.assumeIsolated` would only be safe if
/// the callsite were provably on Main.
@MainActor
final class MessageEventDispatcher {
    private weak var appState: AppState?
    private let stream: MessageEventStream

    init(appState: AppState, stream: MessageEventStream) {
        self.appState = appState
        self.stream = stream
    }

    func wire(services: ServiceContainer) async {
        await wireSyncCoordinator(services.syncCoordinator)
        await wireHeardRepeats(services.heardRepeatsService)
        await wireSessionState(remoteNode: services.remoteNodeService, roomServer: services.roomServerService)
        await wireRoomStatus(services.roomServerService)
        await wireMessageService(services.messageService)
    }

    private func wireSyncCoordinator(_ syncCoordinator: SyncCoordinator) async {
        await syncCoordinator.setMessageEventCallbacks(
            onDirectMessageReceived: { [stream] message, contact in
                await MainActor.run {
                    stream.send(.directMessageReceived(message: message, contact: contact))
                }
            },
            onChannelMessageReceived: { [stream] message, channelIndex in
                await MainActor.run {
                    stream.send(.channelMessageReceived(message: message, channelIndex: channelIndex))
                }
            },
            onRoomMessageReceived: { [stream] message in
                await MainActor.run {
                    stream.send(.roomMessageReceived(message: message, sessionID: message.sessionID))
                }
            },
            onReactionReceived: { [weak appState, stream] messageID, summary in
                await MainActor.run {
                    stream.send(.reactionReceived(messageID: messageID, summary: summary))
                }
                await appState?.handleReactionNotification(messageID: messageID)
            }
        )
    }

    private func wireHeardRepeats(_ heardRepeatsService: HeardRepeatsService) async {
        await heardRepeatsService.setRepeatRecordedHandler { [stream] messageID, count in
            await MainActor.run {
                stream.send(.heardRepeatRecorded(messageID: messageID, count: count))
            }
        }
    }

    private func wireSessionState(
        remoteNode: RemoteNodeService,
        roomServer: RoomServerService
    ) async {
        await remoteNode.setSessionStateChangedHandler { [weak appState] _, _ in
            await MainActor.run {
                appState?.handleSessionStateChange()
            }
        }
        await roomServer.setConnectionRecoveryHandler { [weak appState] _ in
            await MainActor.run {
                appState?.handleSessionStateChange()
            }
        }
    }

    private func wireRoomStatus(_ roomServer: RoomServerService) async {
        await roomServer.setStatusUpdateHandler { [stream] messageID, status in
            await MainActor.run {
                if status == .failed {
                    stream.send(.roomMessageFailed(messageID: messageID))
                } else {
                    stream.send(.roomMessageStatusUpdated(messageID: messageID))
                }
            }
        }
    }

    private func wireMessageService(_ messageService: MessageService) async {
        await messageService.setAckConfirmationHandler { [stream] messageID, status, roundTripTime in
            await MainActor.run {
                stream.send(.messageStatusResolved(messageID: messageID, status: status, roundTripTime: roundTripTime))
            }
        }
        await messageService.setMessageSentHandler { [stream] messageID, status, roundTripTime in
            await MainActor.run {
                stream.send(.messageStatusResolved(messageID: messageID, status: status, roundTripTime: roundTripTime))
            }
        }
        await messageService.setMessageResentHandler { [stream] messageID in
            await MainActor.run {
                stream.send(.messageResent(messageID: messageID))
            }
        }
        await messageService.setRetryStatusHandler { [stream] messageID, attempt, maxAttempts in
            await MainActor.run {
                stream.send(.messageRetrying(messageID: messageID, attempt: attempt, maxAttempts: maxAttempts))
            }
        }
        await messageService.setRoutingChangedHandler { [stream] contactID, isFlood in
            await MainActor.run {
                stream.send(.routingChanged(contactID: contactID, isFlood: isFlood))
            }
        }
        await messageService.setMessageFailedHandler { [stream] messageID in
            await MainActor.run {
                stream.send(.messageFailed(messageID: messageID))
            }
        }
    }
}
