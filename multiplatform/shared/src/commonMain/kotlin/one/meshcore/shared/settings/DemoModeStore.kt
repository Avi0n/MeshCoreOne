package one.meshcore.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferenceLoadGuard
import one.meshcore.shared.prefs.PreferencesStore

/** Number of rapid taps required to unlock demo mode (mirrors iOS 3-tap unlock). */
const val DEMO_MODE_UNLOCK_TAP_COUNT = 3

/**
 * Demo / screenshot mode flags for Play review and fixtures. Mirrors iOS `DemoModeManager`, which
 * sets the two flags and grants no entitlement: demo mode changes what the app shows, never what the
 * user owns, so a secret tap sequence can never hand out paid content.
 */
class DemoModeStore(private val preferences: PreferencesStore, private val scope: CoroutineScope) {
    private val loadGuard = PreferenceLoadGuard<Field>()
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isScreenshotModeEnabled = MutableStateFlow(false)
    val isScreenshotModeEnabled: StateFlow<Boolean> = _isScreenshotModeEnabled.asStateFlow()

    private var unlockTapCount = 0

    fun load() {
        val loadSnapshot = loadGuard.beginLoad()
        scope.launch {
            val isUnlocked = preferences.getBoolean(PersistenceKeys.IS_DEMO_MODE_UNLOCKED) == true
            val isEnabled = preferences.getBoolean(PersistenceKeys.IS_DEMO_MODE_ENABLED) == true
            val isScreenshotModeEnabled = preferences.getBoolean(PersistenceKeys.IS_SCREENSHOT_MODE_ENABLED) == true
            if (loadGuard.isCurrent(loadSnapshot, Field.UNLOCKED)) _isUnlocked.value = isUnlocked
            if (loadGuard.isCurrent(loadSnapshot, Field.ENABLED)) _isEnabled.value = isEnabled
            if (loadGuard.isCurrent(loadSnapshot, Field.SCREENSHOT)) {
                _isScreenshotModeEnabled.value = isScreenshotModeEnabled
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (!_isUnlocked.value && enabled) return
        loadGuard.recordMutation(Field.ENABLED)
        _isEnabled.value = enabled
        scope.launch {
            preferences.putBoolean(PersistenceKeys.IS_DEMO_MODE_ENABLED, enabled)
        }
    }

    fun setScreenshotModeEnabled(enabled: Boolean) {
        if (!_isUnlocked.value && enabled) return
        loadGuard.recordMutation(Field.SCREENSHOT)
        _isScreenshotModeEnabled.value = enabled
        scope.launch {
            preferences.putBoolean(PersistenceKeys.IS_SCREENSHOT_MODE_ENABLED, enabled)
        }
    }

    /**
     * Records a secret unlock tap. After [DEMO_MODE_UNLOCK_TAP_COUNT] taps, unlocks and enables demo mode.
     * @return true when unlock completed on this tap
     */
    fun registerUnlockTap(): Boolean {
        if (_isUnlocked.value) return false
        unlockTapCount += 1
        if (unlockTapCount < DEMO_MODE_UNLOCK_TAP_COUNT) return false
        unlockTapCount = 0
        unlock()
        return true
    }

    fun unlock() {
        loadGuard.recordMutation(Field.UNLOCKED, Field.ENABLED)
        _isUnlocked.value = true
        _isEnabled.value = true
        scope.launch {
            preferences.putBoolean(PersistenceKeys.IS_DEMO_MODE_UNLOCKED, true)
            preferences.putBoolean(PersistenceKeys.IS_DEMO_MODE_ENABLED, true)
        }
    }

    fun resetUnlockTaps() {
        unlockTapCount = 0
    }

    private enum class Field { UNLOCKED, ENABLED, SCREENSHOT }
}
