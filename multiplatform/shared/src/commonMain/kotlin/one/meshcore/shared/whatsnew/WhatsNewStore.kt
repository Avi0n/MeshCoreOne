package one.meshcore.shared.whatsnew

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/** Preference key pinned to iOS `AppStorageKey.lastShownWhatsNewVersion`. */
const val LAST_SHOWN_WHATS_NEW_VERSION = "lastShownWhatsNewVersion"

/**
 * iOS `connectionUIQuiescent`: do not present What's New while a connection alert is up.
 * [pending] stays set so the sheet returns after the alert clears. Hiding must not call
 * [WhatsNewStore.markShown].
 */
fun shouldPresentWhatsNew(pending: WhatsNewRelease?, connectionAlertPresent: Boolean): Boolean =
    pending != null && !connectionAlertPresent

/**
 * Loads and persists the last-shown What's New version; exposes [pendingRelease] for the root sheet.
 */
class WhatsNewStore(private val preferences: PreferencesStore, private val scope: CoroutineScope) {
    private val _pendingRelease = MutableStateFlow<WhatsNewRelease?>(null)
    val pendingRelease: StateFlow<WhatsNewRelease?> = _pendingRelease.asStateFlow()

    /**
     * Launch-time evaluate (iOS `WhatsNewState.evaluate`). A show defers the baseline
     * to [markShown]; a suppress writes the current version now so the sheet never
     * reappears. Screenshot mode skips both.
     */
    fun evaluate(isOnboarded: Boolean, isScreenshotMode: Boolean = false) {
        if (isScreenshotMode) return
        scope.launch { evaluateStoredVersion(isOnboarded) }
    }

    /** Reads the persisted Android launch flags before deciding whether to present this release. */
    fun evaluateStoredInstallState() {
        scope.launch {
            if (preferences.getBoolean(PersistenceKeys.IS_SCREENSHOT_MODE_ENABLED) == true) return@launch
            val isOnboarded = preferences.getBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING) ?: false
            evaluateStoredVersion(isOnboarded)
        }
    }

    /** Desktop host: treat the install as already onboarded. */
    fun load() = evaluate(isOnboarded = true)

    fun markShown() {
        val pending = _pendingRelease.value ?: return
        _pendingRelease.value = null
        scope.launch {
            preferences.putString(LAST_SHOWN_WHATS_NEW_VERSION, pending.version.encode())
        }
    }

    private suspend fun evaluateStoredVersion(isOnboarded: Boolean) {
        val last = preferences.getString(LAST_SHOWN_WHATS_NEW_VERSION)
        val pending = WhatsNewCatalog.resolve(
            current = WhatsNewCatalog.currentVersion,
            baselineString = last,
            isOnboarded = isOnboarded,
        )
        if (pending != null) {
            _pendingRelease.value = pending
        } else {
            preferences.putString(LAST_SHOWN_WHATS_NEW_VERSION, WhatsNewCatalog.currentVersion.encode())
        }
    }
}
