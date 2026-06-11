import Foundation

/// Thread-safe notification preferences (read-only snapshot from UserDefaults)
public struct NotificationPreferences: Sendable {
    public let contactMessagesEnabled: Bool
    public let channelMessagesEnabled: Bool
    public let roomMessagesEnabled: Bool
    public let newContactDiscoveredEnabled: Bool
    public let discoveryContactEnabled: Bool
    public let discoveryRepeaterEnabled: Bool
    public let discoveryRoomEnabled: Bool
    public let reactionNotificationsEnabled: Bool
    public let soundEnabled: Bool
    public let badgeEnabled: Bool
    public let lowBatteryEnabled: Bool

    public init() {
        let defaults = UserDefaults.standard
        func enabled(_ key: AppStorageKey) -> Bool {
            defaults.object(forKey: key.rawValue) as? Bool ?? AppStorageKey.defaultNotificationEnabled
        }
        self.contactMessagesEnabled = enabled(.notifyContactMessages)
        self.channelMessagesEnabled = enabled(.notifyChannelMessages)
        self.roomMessagesEnabled = enabled(.notifyRoomMessages)
        self.newContactDiscoveredEnabled = enabled(.notifyNewContacts)
        self.discoveryContactEnabled = enabled(.notifyNewContactsContact)
        self.discoveryRepeaterEnabled = enabled(.notifyNewContactsRepeater)
        self.discoveryRoomEnabled = enabled(.notifyNewContactsRoom)
        self.reactionNotificationsEnabled = enabled(.notifyReactions)
        self.soundEnabled = enabled(.notificationSoundEnabled)
        self.badgeEnabled = enabled(.notificationBadgeEnabled)
        self.lowBatteryEnabled = enabled(.notifyLowBattery)
    }
}

/// Observable store for notification preferences (used by Settings UI for two-way binding)
@Observable
@MainActor
public final class NotificationPreferencesStore {
    private let defaults = UserDefaults.standard

    /// Observation anchor for the UserDefaults-backed computed properties:
    /// `@Observable` only instruments stored properties, so every getter reads
    /// this and every setter bumps it, making writes visible to SwiftUI.
    private var revision = 0

    private func isEnabled(_ key: AppStorageKey) -> Bool {
        _ = revision
        return defaults.object(forKey: key.rawValue) as? Bool ?? AppStorageKey.defaultNotificationEnabled
    }

    private func setEnabled(_ newValue: Bool, for key: AppStorageKey) {
        defaults.set(newValue, forKey: key.rawValue)
        revision += 1
    }

    // MARK: - Message Notifications

    /// Enable notifications for contact (direct) messages
    public var contactMessagesEnabled: Bool {
        get { isEnabled(.notifyContactMessages) }
        set { setEnabled(newValue, for: .notifyContactMessages) }
    }

    /// Enable notifications for channel messages
    public var channelMessagesEnabled: Bool {
        get { isEnabled(.notifyChannelMessages) }
        set { setEnabled(newValue, for: .notifyChannelMessages) }
    }

    /// Enable notifications for room messages
    public var roomMessagesEnabled: Bool {
        get { isEnabled(.notifyRoomMessages) }
        set { setEnabled(newValue, for: .notifyRoomMessages) }
    }

    /// Enable notifications when new contacts are discovered
    public var newContactDiscoveredEnabled: Bool {
        get { isEnabled(.notifyNewContacts) }
        set {
            let wasEnabled = isEnabled(.notifyNewContacts)
            setEnabled(newValue, for: .notifyNewContacts)
            // Only auto-enable children on first activation (keys never written before)
            if newValue && !wasEnabled {
                let hasExistingChoices = defaults.object(forKey: AppStorageKey.notifyNewContactsContact.rawValue) != nil
                    || defaults.object(forKey: AppStorageKey.notifyNewContactsRepeater.rawValue) != nil
                    || defaults.object(forKey: AppStorageKey.notifyNewContactsRoom.rawValue) != nil
                if !hasExistingChoices {
                    discoveryContactEnabled = true
                    discoveryRepeaterEnabled = true
                    discoveryRoomEnabled = true
                }
            }
        }
    }

    /// Enable discovery notifications for companion (chat) nodes
    public var discoveryContactEnabled: Bool {
        get { isEnabled(.notifyNewContactsContact) }
        set { setEnabled(newValue, for: .notifyNewContactsContact) }
    }

    /// Enable discovery notifications for repeater nodes
    public var discoveryRepeaterEnabled: Bool {
        get { isEnabled(.notifyNewContactsRepeater) }
        set { setEnabled(newValue, for: .notifyNewContactsRepeater) }
    }

    /// Enable discovery notifications for room nodes
    public var discoveryRoomEnabled: Bool {
        get { isEnabled(.notifyNewContactsRoom) }
        set { setEnabled(newValue, for: .notifyNewContactsRoom) }
    }

    /// Enable notifications when someone reacts to your messages
    public var reactionNotificationsEnabled: Bool {
        get { isEnabled(.notifyReactions) }
        set { setEnabled(newValue, for: .notifyReactions) }
    }

    // MARK: - Sound & Badge

    /// Enable notification sounds
    public var soundEnabled: Bool {
        get { isEnabled(.notificationSoundEnabled) }
        set { setEnabled(newValue, for: .notificationSoundEnabled) }
    }

    /// Enable badge count on app icon
    public var badgeEnabled: Bool {
        get { isEnabled(.notificationBadgeEnabled) }
        set { setEnabled(newValue, for: .notificationBadgeEnabled) }
    }

    // MARK: - Low Battery

    /// Enable low battery warning notifications
    public var lowBatteryEnabled: Bool {
        get { isEnabled(.notifyLowBattery) }
        set { setEnabled(newValue, for: .notifyLowBattery) }
    }

    public init() {}
}
