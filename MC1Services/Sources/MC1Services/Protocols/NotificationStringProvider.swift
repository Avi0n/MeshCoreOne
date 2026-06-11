import Foundation

/// Provides localized strings for notifications.
///
/// This protocol allows the app layer to inject localized strings into
/// MC1Services without the service layer depending on L10n directly.
public protocol NotificationStringProvider: Sendable {
    /// Returns the notification title for a discovered contact of the given type.
    /// - Parameter type: The type of contact discovered
    /// - Returns: Localized notification title (e.g., "New Repeater Discovered")
    func discoveryNotificationTitle(for type: ContactType) -> String

    /// Returns the localized title for the "Reply" notification action.
    var replyActionTitle: String { get }

    /// Returns the localized title for the "Send" button in notification quick reply.
    var sendButtonTitle: String { get }

    /// Returns the localized placeholder for the notification quick reply text input.
    var messagePlaceholder: String { get }

    /// Returns the localized title for the "Mark as Read" notification action.
    var markAsReadActionTitle: String { get }

    /// Returns the localized title for a low battery warning notification.
    var lowBatteryTitle: String { get }

    /// Returns the localized body for a low battery warning notification.
    /// - Parameters:
    ///   - deviceName: The name of the device with low battery
    ///   - percentage: The current battery percentage
    /// - Returns: Localized notification body
    func lowBatteryBody(deviceName: String, percentage: Int) -> String

    /// Returns the localized title for a failed quick-reply notification.
    var quickReplyFailedTitle: String { get }

    /// Returns the localized body for a failed quick-reply notification.
    /// - Parameter conversationName: The contact or channel display name
    /// - Returns: Localized notification body
    func quickReplyFailedBody(conversationName: String) -> String

    /// Returns the localized fallback display name for a discovered contact with no advertised name.
    var unknownContactName: String { get }

    /// Returns the localized fallback display name for a channel with no stored name.
    /// - Parameter index: The channel's slot index
    func defaultChannelName(index: Int) -> String

    /// Returns the localized body for a reaction notification.
    /// - Parameters:
    ///   - emoji: The reaction emoji
    ///   - messagePreview: Truncated preview of the reacted-to message
    func reactionNotificationBody(emoji: String, messagePreview: String) -> String
}
