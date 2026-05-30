import MC1Services
import SwiftUI
import UserNotifications

/// Notification toggle settings
struct NotificationSettingsSection: View {
    @Environment(\.appTheme) private var theme
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var preferences = NotificationPreferencesStore()
    @State private var authorizationStatus: UNAuthorizationStatus = .notDetermined

    var body: some View {
        @Bindable var preferences = preferences
        Section {
            switch authorizationStatus {
            case .notDetermined:
                Button {
                    Task {
                        await requestAuthorization()
                    }
                } label: {
                    TintedLabel(L10n.Settings.Notifications.enable, systemImage: "bell.badge")
                }

            case .denied:
                VStack(alignment: .leading, spacing: 8) {
                    Label(L10n.Settings.Notifications.disabled, systemImage: "bell.slash")
                        .foregroundStyle(.secondary)

                    Button(L10n.Settings.Notifications.openSettings) {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            openURL(url)
                        }
                    }
                    .font(.subheadline)
                }

            default:
                Toggle(isOn: $preferences.contactMessagesEnabled) {
                    TintedLabel(L10n.Settings.Notifications.contactMessages, systemImage: "message")
                }
                Toggle(isOn: $preferences.channelMessagesEnabled) {
                    TintedLabel(L10n.Settings.Notifications.channelMessages, systemImage: "person.3")
                }
                Toggle(isOn: $preferences.roomMessagesEnabled) {
                    TintedLabel(L10n.Settings.Notifications.roomMessages, systemImage: "bubble.left.and.bubble.right")
                }
                Toggle(isOn: $preferences.reactionNotificationsEnabled) {
                    TintedLabel(L10n.Settings.Notifications.reactions, systemImage: "face.smiling")
                }
                Toggle(isOn: $preferences.lowBatteryEnabled) {
                    TintedLabel(L10n.Settings.Notifications.lowBattery, systemImage: "battery.25")
                }
                Toggle(isOn: Binding(
                    get: { preferences.newContactDiscoveredEnabled },
                    set: { newValue in
                        withAnimation { preferences.newContactDiscoveredEnabled = newValue }
                    }
                )) {
                    TintedLabel(L10n.Settings.Notifications.newContactDiscovered, systemImage: "person.badge.plus")
                }
                if preferences.newContactDiscoveredEnabled {
                    Toggle(isOn: $preferences.discoveryContactEnabled) {
                        TintedLabel(L10n.Settings.Notifications.discoveryContact, systemImage: "person")
                    }
                    .listRowInsets(EdgeInsets(top: 0, leading: 52, bottom: 0, trailing: 20))
                    Toggle(isOn: $preferences.discoveryRepeaterEnabled) {
                        TintedLabel(L10n.Settings.Notifications.discoveryRepeater, systemImage: "antenna.radiowaves.left.and.right")
                    }
                    .listRowInsets(EdgeInsets(top: 0, leading: 52, bottom: 0, trailing: 20))
                    Toggle(isOn: $preferences.discoveryRoomEnabled) {
                        TintedLabel(L10n.Settings.Notifications.discoveryRoom, systemImage: "door.left.hand.open")
                    }
                    .listRowInsets(EdgeInsets(top: 0, leading: 52, bottom: 0, trailing: 20))
                }
            }
        }
        .themedRowBackground(theme)
        .task {
            await refreshAuthorizationStatus()
        }
        .onChange(of: scenePhase) {
            if scenePhase == .active {
                Task {
                    await refreshAuthorizationStatus()
                }
            }
        }
    }

    private func refreshAuthorizationStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        authorizationStatus = settings.authorizationStatus
    }

    private func requestAuthorization() async {
        let granted = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
        authorizationStatus = (granted == true) ? .authorized : .denied
    }
}
