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
        self.contactMessagesEnabled = defaults.object(forKey: "notifyContactMessages") as? Bool ?? true
        self.channelMessagesEnabled = defaults.object(forKey: "notifyChannelMessages") as? Bool ?? true
        self.roomMessagesEnabled = defaults.object(forKey: "notifyRoomMessages") as? Bool ?? true
        self.newContactDiscoveredEnabled = defaults.object(forKey: "notifyNewContacts") as? Bool ?? true
        self.discoveryContactEnabled = defaults.object(forKey: "notifyNewContactsContact") as? Bool ?? true
        self.discoveryRepeaterEnabled = defaults.object(forKey: "notifyNewContactsRepeater") as? Bool ?? true
        self.discoveryRoomEnabled = defaults.object(forKey: "notifyNewContactsRoom") as? Bool ?? true
        self.reactionNotificationsEnabled = defaults.object(forKey: "notifyReactions") as? Bool ?? true
        self.soundEnabled = defaults.object(forKey: "notificationSoundEnabled") as? Bool ?? true
        self.badgeEnabled = defaults.object(forKey: "notificationBadgeEnabled") as? Bool ?? true
        self.lowBatteryEnabled = defaults.object(forKey: "notifyLowBattery") as? Bool ?? true
    }
}

/// Observable store for notification preferences (used by Settings UI for two-way binding)
@MainActor
@Observable
public final class NotificationPreferencesStore {
    private let defaults = UserDefaults.standard

    // MARK: - Message Notifications

    /// Enable notifications for contact (direct) messages
    public var contactMessagesEnabled: Bool {
        get { defaults.object(forKey: "notifyContactMessages") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notifyContactMessages") }
    }

    /// Enable notifications for channel messages
    public var channelMessagesEnabled: Bool {
        get { defaults.object(forKey: "notifyChannelMessages") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notifyChannelMessages") }
    }

    /// Enable notifications for room messages
    public var roomMessagesEnabled: Bool {
        get { defaults.object(forKey: "notifyRoomMessages") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notifyRoomMessages") }
    }

    /// Enable notifications when new contacts are discovered
    public var newContactDiscoveredEnabled: Bool {
        get { defaults.object(forKey: "notifyNewContacts") as? Bool ?? true }
        set {
            let wasEnabled = defaults.object(forKey: "notifyNewContacts") as? Bool ?? true
            defaults.set(newValue, forKey: "notifyNewContacts")
            // Only auto-enable children on first activation (keys never written before)
            if newValue && !wasEnabled {
                let hasExistingChoices = defaults.object(forKey: "notifyNewContactsContact") != nil
                    || defaults.object(forKey: "notifyNewContactsRepeater") != nil
                    || defaults.object(forKey: "notifyNewContactsRoom") != nil
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
        get { defaults.object(forKey: "notifyNewContactsContact") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notifyNewContactsContact") }
    }

    /// Enable discovery notifications for repeater nodes
    public var discoveryRepeaterEnabled: Bool {
        get { defaults.object(forKey: "notifyNewContactsRepeater") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notifyNewContactsRepeater") }
    }

    /// Enable discovery notifications for room nodes
    public var discoveryRoomEnabled: Bool {
        get { defaults.object(forKey: "notifyNewContactsRoom") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notifyNewContactsRoom") }
    }

    /// Enable notifications when someone reacts to your messages
    public var reactionNotificationsEnabled: Bool {
        get { defaults.object(forKey: "notifyReactions") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notifyReactions") }
    }

    // MARK: - Sound & Badge

    /// Enable notification sounds
    public var soundEnabled: Bool {
        get { defaults.object(forKey: "notificationSoundEnabled") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notificationSoundEnabled") }
    }

    /// Enable badge count on app icon
    public var badgeEnabled: Bool {
        get { defaults.object(forKey: "notificationBadgeEnabled") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notificationBadgeEnabled") }
    }

    // MARK: - Low Battery

    /// Enable low battery warning notifications
    public var lowBatteryEnabled: Bool {
        get { defaults.object(forKey: "notifyLowBattery") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "notifyLowBattery") }
    }

    public init() {}
}
