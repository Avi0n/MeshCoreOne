package one.meshcore.shared.ui

import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.settings.ui.BackupSettingsWorkspace

interface ShellSharingHost {
    val backupWorkspace: BackupSettingsWorkspace?

    /** Reads the platform clipboard for the Add-contact Paste-URL action; `null` when empty/unavailable. */
    val readClipboard: () -> String?

    /** Copies text to the platform clipboard (contact/channel key + secret copy actions). */
    val copyToClipboard: (String) -> Unit

    /** Shares text through the platform share sheet (contact QR share / backup JSON). */
    val shareText: (String) -> Unit

    /**
     * Shares text with an email/share subject. Null falls back to [shareText] so hosts that only
     * copy still deliver the body.
     */
    val shareTextWithSubject: ((text: String, subject: String) -> Unit)?

    /**
     * Shares a debug-log dump as a file. Null falls back to [shareText], which can fail on a
     * large dump (Android Binder extra limit).
     */
    val shareDebugLogs: ((String) -> Unit)?

    /** Shares already-decoded image bytes. Null hides the loaded-image share control. */
    val shareImageBytes: ((ByteArray) -> Unit)?

    /**
     * Settings → Backup export: collect Room rows, encode zlib `.mc1backup`, share as a file stream.
     * No-op when the platform has not wired a database.
     */
    val exportBackup: suspend () -> one.meshcore.shared.backup.BackupExportSuccessSummary?

    /**
     * Shortcuts Send Message: enqueue a staged `meshcore://send` on the durable queue.
     * Default is "must foreground" so a host that omitted this never claims a queued send.
     */
    val enqueueSendMessage: suspend (
        one.meshcore.shared.navigation.MeshCoreDeepLink.SendMessage,
    ) -> one.meshcore.shared.navigation.SendMessageShortcutOutcome

    /**
     * Shortcuts Send Advert: transmit a staged `meshcore://advert` with no location prompt
     * (iOS `SendAdvertIntent` uses `allowLocationPrompt: false`). Default is a no-op.
     */
    val sendAdvertShortcut: suspend (flood: Boolean) -> one.meshcore.shared.navigation.SendAdvertShortcutOutcome

    /**
     * Settings → Backup import (product path): open a document picker, read `.mc1backup` bytes,
     * and return uncompressed JSON. Merge waits for the preview confirm. Null when the host
     * has not wired a picker.
     */
    val importBackupFile: (suspend () -> String?)?

    /**
     * Settings → Backup import (secondary paste path): decode uncompressed JSON and merge into Room.
     */
    val importBackup: suspend (String) -> BackupImportResult?

    /** Settings → Diagnostics: export last-day debug log text for sharesheet. */
    val exportDebugLogs: suspend () -> String

    /** Settings → Diagnostics: clear persisted debug log buffer. */
    val clearDebugLogs: suspend () -> Unit

    /** Opens the app's system settings page (camera-permission-denied deep link). */
    val openAppSettings: () -> Unit

    /** Opens the platform per-app language picker (Settings → Language row). */
    val openAppLanguageSettings: () -> Unit

    /** Opens the platform Bluetooth settings page (onboarding troubleshooting deep link). */
    val openBluetoothSettings: () -> Unit

    /**
     * Opens the per-app Unrestricted battery prompt (Settings → Connection). Null on hosts
     * without Doze (desktop).
     */
    val openBatteryOptimizationSettings: (() -> Unit)?

    /** Fires the success haptic after a QR scan imports. */
    val scanHaptic: () -> Unit

    /** Routes a `meshcore://` link tapped inside a message through the deep-link router (stages a sheet). */
    val routeMeshCoreLink: (String) -> Unit

    /** Opens a plain web URL tapped inside a message in the platform browser. */
    val openExternalUrl: (String) -> Unit

    /**
     * System Translation overlay when in-bubble ML Kit fails (iOS `.translationPresentation`).
     * Android sends the text to a PROCESS_TEXT handler; desktop is a no-op.
     */
    val presentSystemTranslation: (String) -> Unit
}

/** Clipboard, share sheet, backup, and link actions. */
data class ShellSharingInputs(
    override val backupWorkspace: BackupSettingsWorkspace? = null,
    override val readClipboard: () -> String? = { null },
    override val copyToClipboard: (String) -> Unit = {},
    override val shareText: (String) -> Unit = {},
    override val shareTextWithSubject: ((text: String, subject: String) -> Unit)? = null,
    override val shareDebugLogs: ((String) -> Unit)? = null,
    override val shareImageBytes: ((ByteArray) -> Unit)? = null,
    override val exportBackup: suspend () -> one.meshcore.shared.backup.BackupExportSuccessSummary? = { null },
    override val enqueueSendMessage: suspend (
        one.meshcore.shared.navigation.MeshCoreDeepLink.SendMessage,
    ) -> one.meshcore.shared.navigation.SendMessageShortcutOutcome =
        {
            one.meshcore.shared.navigation.SendMessageShortcutOutcome.MustForeground
        },
    override val sendAdvertShortcut: suspend (
        flood: Boolean,
    ) -> one.meshcore.shared.navigation.SendAdvertShortcutOutcome =
        { one.meshcore.shared.navigation.SendAdvertShortcutOutcome.NotConnected },
    override val importBackupFile: (suspend () -> String?)? = null,
    override val importBackup: suspend (String) -> BackupImportResult? = { null },
    override val exportDebugLogs: suspend () -> String = { "" },
    override val clearDebugLogs: suspend () -> Unit = {},
    override val openAppSettings: () -> Unit = {},
    override val openAppLanguageSettings: () -> Unit = {},
    override val openBluetoothSettings: () -> Unit = {},
    override val openBatteryOptimizationSettings: (() -> Unit)? = null,
    override val scanHaptic: () -> Unit = {},
    override val routeMeshCoreLink: (String) -> Unit = {},
    override val openExternalUrl: (String) -> Unit = {},
    override val presentSystemTranslation: (String) -> Unit = {},
) : ShellSharingHost
