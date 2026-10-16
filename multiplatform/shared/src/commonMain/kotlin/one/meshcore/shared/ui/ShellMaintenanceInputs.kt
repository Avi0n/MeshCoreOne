package one.meshcore.shared.ui

import one.meshcore.shared.contact.StaleNodeRemovalResult
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.settings.ui.NodeConfigImportWorkspace

interface ShellMaintenanceHost {
    val nodeConfigImportWorkspace: NodeConfigImportWorkspace?

    /** Import 32-byte private key onto the connected radio (Device Info). Null when disconnected. */
    val importPrivateKey: (suspend (ByteArray) -> Unit)?

    /** Auto-remove stale non-favorite nodes threshold in days (0 = off). */
    val staleCleanupThresholdDays: () -> Int

    /** Persist threshold and optionally force a cleanup pass when connected. */
    val onStaleCleanupThresholdChange: (Int) -> Unit

    /** Last successful stale-node cleanup, epoch seconds; 0 means never run. */
    val lastStaleCleanupSeconds: () -> Long

    /** Count of non-favorite contacts for Danger Zone remove. */
    val unfavoritedCount: suspend () -> Int

    /** Remove all non-favorite contacts (Danger Zone). */
    val removeUnfavorited: suspend () -> StaleNodeRemovalResult

    /** Danger Zone: forget device, keep local radio partition data. */
    val forgetKeepData: suspend () -> Unit

    /** Danger Zone: forget device and delete local radio partition data. */
    val forgetDeleteData: suspend () -> Unit

    /**
     * Danger Zone: factory-reset the radio then forget with data delete.
     * Returns false when services are unavailable (iOS `servicesUnavailable`); do not forget.
     */
    val factoryReset: suspend () -> Boolean

    /** Live node-config service (export/import device JSON); null when disconnected. */
    val nodeConfigService: () -> NodeConfigService?

    /** Share exported node-config JSON as a file (filename includes `.json`). */
    val shareNodeConfigJson: (json: String, filename: String) -> Unit

    /**
     * Pick a node-config JSON file and return its UTF-8 text, or null if cancelled.
     * Null provider means the import file button is a no-op (desktop without a picker).
     */
    val pickNodeConfigJson: (suspend () -> String?)?

    /** Local-time `yyyy-MM-dd-HHmmss` stamp for export basenames. */
    val nodeConfigExportTimestamp: () -> String

    /** Live OS notification-permission state for Settings → Notifications. */
    val isNotificationsAuthorized: () -> Boolean

    /** Whether the notification permission has been asked at all; false means never asked, not denied. */
    val isNotificationPermissionDetermined: () -> Boolean

    val onRequestNotificationPermission: () -> Unit

    /** Opens the platform's system notification settings page (denied-permission deep link). */
    val onOpenSystemNotificationSettings: () -> Unit

    /** App version name for the Settings About footer; `null` hides it. */
    val appVersionName: String?

    /** App build number for the Settings About footer; `null` hides it. */
    val appBuildNumber: String?
}

/** Danger-zone actions, node-config transfer, notification permission, and About. */
data class ShellMaintenanceInputs(
    override val nodeConfigImportWorkspace: NodeConfigImportWorkspace? = null,
    override val importPrivateKey: (suspend (ByteArray) -> Unit)? = null,
    override val staleCleanupThresholdDays: () -> Int = { 0 },
    override val onStaleCleanupThresholdChange: (Int) -> Unit = {},
    override val lastStaleCleanupSeconds: () -> Long = { 0L },
    override val unfavoritedCount: suspend () -> Int = { 0 },
    override val removeUnfavorited: suspend () -> StaleNodeRemovalResult =
        {
            StaleNodeRemovalResult(removed = 0, total = 0)
        },
    override val forgetKeepData: suspend () -> Unit = {},
    override val forgetDeleteData: suspend () -> Unit = {},
    override val factoryReset: suspend () -> Boolean = { true },
    override val nodeConfigService: () -> NodeConfigService? = { null },
    override val shareNodeConfigJson: (json: String, filename: String) -> Unit = { _, _ -> },
    override val pickNodeConfigJson: (suspend () -> String?)? = null,
    override val nodeConfigExportTimestamp: () -> String = { "0" },
    override val isNotificationsAuthorized: () -> Boolean = { true },
    override val isNotificationPermissionDetermined: () -> Boolean = { true },
    override val onRequestNotificationPermission: () -> Unit = {},
    override val onOpenSystemNotificationSettings: () -> Unit = {},
    override val appVersionName: String? = null,
    override val appBuildNumber: String? = null,
) : ShellMaintenanceHost
