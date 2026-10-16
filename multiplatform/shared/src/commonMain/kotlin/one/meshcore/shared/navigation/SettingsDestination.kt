package one.meshcore.shared.navigation

/**
 * Destinations under the settings hub, matching iOS `SettingsDetail`. Device-scoped rows are cleared on
 * disconnect so a detail pane cannot strand a gone device.
 */
enum class SettingsDestination {
    DEVICE_INFO,

    /** Nested under Device Info (iOS `PublicKeyView`); not listed as a hub row. */
    PUBLIC_KEY,
    RADIO,

    /** Nested under Radio (iOS `PresetLocationView`); not listed as a hub row. */
    PRESET_LOCATION,
    LOCATION,
    CONNECTION,
    ADVANCED,

    /** Nested under Advanced (iOS ConfigExportImportSection); not listed as a hub row. */
    CONFIG_EXPORT,

    /** Nested under Advanced (iOS ConfigExportImportSection); not listed as a hub row. */
    CONFIG_IMPORT,

    /** Nested under Advanced (iOS `RegionManagementView`); not listed as a hub row. */
    REGION_MANAGEMENT,
    TRUSTED_CONTACTS,
    NOTIFICATIONS,
    CHATS,

    /** Nested under Chats (iOS `BlockingSection`); not listed as a hub row. */
    BLOCKED_CONTACTS,

    /** Nested under Chats (iOS `BlockingSection`); not listed as a hub row. */
    BLOCKED_CHANNEL_SENDERS,
    APPEARANCE,
    MAPS,

    /** Nested under Maps (iOS `MapsSettingsView` Offline Maps link); not listed as a hub row. */
    OFFLINE_MAPS,
    LANGUAGE,

    /** Nested under Language (iOS `TranslateIntoLanguageView`); not listed as a hub row. */
    TRANSLATE_INTO,
    BACKUP,
    SUPPORT,
    DANGER_ZONE,

    /** Nested under About (iOS `FeedbackView`); not listed as a hub row. */
    FEEDBACK,

    /** Nested under Feedback (iOS `DiagnosticsSection`); not listed as a hub row. */
    DIAGNOSTICS,
    ;

    /** My Device rows only apply while a radio is connected (mirrors iOS `requiresDevice`). */
    val requiresDevice: Boolean
        get() = when (this) {
            DEVICE_INFO, PUBLIC_KEY, RADIO, PRESET_LOCATION, LOCATION, CONNECTION, ADVANCED,
            CONFIG_EXPORT, REGION_MANAGEMENT, TRUSTED_CONTACTS, DANGER_ZONE,
            -> true
            NOTIFICATIONS, CHATS, BLOCKED_CONTACTS, BLOCKED_CHANNEL_SENDERS, APPEARANCE, MAPS, OFFLINE_MAPS,
            LANGUAGE, TRANSLATE_INTO, BACKUP, CONFIG_IMPORT, SUPPORT, FEEDBACK, DIAGNOSTICS,
            -> false
        }

    /**
     * The page system Back / the nested toolbar returns to, matching iOS Settings `NavigationStack`
     * (`SettingsSubpage`, region management, Translate Into, Offline Maps). Null is the settings hub.
     */
    val backParent: SettingsDestination?
        get() = when (this) {
            PUBLIC_KEY -> DEVICE_INFO
            PRESET_LOCATION -> RADIO
            CONFIG_EXPORT, CONFIG_IMPORT, REGION_MANAGEMENT, TRUSTED_CONTACTS, DANGER_ZONE -> ADVANCED
            BLOCKED_CONTACTS, BLOCKED_CHANNEL_SENDERS -> CHATS
            OFFLINE_MAPS -> MAPS
            TRANSLATE_INTO -> LANGUAGE
            DIAGNOSTICS -> FEEDBACK
            else -> null
        }
}
