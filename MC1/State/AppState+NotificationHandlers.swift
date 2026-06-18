import Foundation
import MC1Services

// MARK: - Notification Handlers

extension AppState {

    /// Configure notification handlers once services are available.
    /// The transaction scripts live in `NotificationActionHandler`; this
    /// installs thin forwarders and injects the app-layer inputs.
    func configureNotificationHandlers() {
        guard let services else { return }

        // Navigation-related notification tap handlers (delegated to NavigationCoordinator)
        navigation.configureNotificationHandlers(
            notificationService: services.notificationService,
            dataStore: services.dataStore,
            connectedDevice: { [weak self] in self?.connectedDevice }
        )

        let handler = services.notificationActionHandler
        handler.configure(
            isConnectionReady: { [weak self] in self?.connectionState == .ready },
            localNodeName: { [weak self] in self?.connectedDevice?.nodeName }
        )

        services.notificationService.onQuickReply = { contactID, text in
            await handler.handleQuickReply(contactID: contactID, text: text)
        }

        services.notificationService.onChannelQuickReply = { radioID, channelIndex, text in
            await handler.handleChannelQuickReply(radioID: radioID, channelIndex: channelIndex, text: text)
        }

        services.notificationService.onMarkAsRead = { contactID, messageID in
            await handler.handleMarkAsRead(contactID: contactID, messageID: messageID)
        }

        services.notificationService.onChannelMarkAsRead = { radioID, channelIndex, messageID in
            await handler.handleChannelMarkAsRead(radioID: radioID, channelIndex: channelIndex, messageID: messageID)
        }

        services.notificationService.onRoomMarkAsRead = { sessionID, messageID in
            await handler.handleRoomMarkAsRead(sessionID: sessionID, messageID: messageID)
        }
    }

    /// Handle posting a notification when someone reacts to the user's message
    func handleReactionNotification(messageID: UUID) async {
        guard let services else { return }
        await services.notificationActionHandler.handleReactionNotification(messageID: messageID)
    }
}
