package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.meshcore.shared.remotenode.PasswordApplyResult
import one.meshcore.shared.remotenode.SettingsApplyResult

/** How long an Apply button shows its success check before returning to idle. */
internal const val APPLY_SUCCESS_FLASH_MS = 1_500L

/**
 * Change Password Apply, matching iOS `NodeSecuritySection`. The button stays off until both fields
 * match so a mismatch tap cannot look like a live write.
 */
internal fun passwordChangeApplyEnabled(newPassword: String, confirmPassword: String, isChanging: Boolean): Boolean =
    newPassword.isNotEmpty() && newPassword == confirmPassword && !isChanging

/** iOS `NodeSettingsViewModel.flashSuccess`: drop applying, show the check, then clear it. */
internal suspend fun flashApplySuccess(
    kind: SettingsApplyFeedback.Kind,
    generation: Long? = null,
    setSuccess: (Boolean) -> Unit,
) {
    val context = currentCoroutineContext()
    val feedback = context[SettingsApplyFeedback]
    val token = generation ?: feedback?.begin(kind)
    context.ensureActive()
    setSuccess(true)
    if (feedback == null) delay(APPLY_SUCCESS_FLASH_MS) else feedback.waitUntilDisplayed()
    context.ensureActive()
    if (token == null || feedback?.isCurrent(kind, token) == true) setSuccess(false)
}

/** Marks a section applying, then flashes its success flag only when the write is [SettingsApplyResult.Applied]. */
internal fun <S> CoroutineScope.launchFlashedSettingsApply(
    state: MutableStateFlow<S>,
    kind: SettingsApplyFeedback.Kind,
    updates: SettingsApplyState<S, SettingsApplyResult>,
    apply: suspend () -> SettingsApplyResult,
    flash: (S, Boolean) -> S,
) {
    if (!isActive) return
    val feedback = coroutineContext[SettingsApplyFeedback]
    val token = feedback?.begin(kind)
    state.update(updates.begin)
    launch {
        try {
            val result = apply()
            currentCoroutineContext().ensureActive()
            if (token != null && feedback?.isCurrent(kind, token) != true) return@launch
            state.update { current -> updates.finish(current, result) }
            if (result == SettingsApplyResult.Applied) {
                flashApplySuccess(kind, token) { success -> state.update { current -> flash(current, success) } }
            }
        } catch (stopped: CancellationException) {
            if (this@launchFlashedSettingsApply.isActive &&
                (token == null || feedback?.isCurrent(kind, token) == true)
            ) {
                state.update(updates.cancelled)
            }
            throw stopped
        }
    }
}

/**
 * Marks a password change busy, then flashes success only when the write is applied.
 * A refusal publishes its banner and does not flash.
 */
internal fun <S> CoroutineScope.launchPasswordChange(
    state: MutableStateFlow<S>,
    updates: SettingsApplyState<S, ApplyBanner?>,
    change: suspend () -> PasswordApplyResult,
    flash: (S, Boolean) -> S,
) {
    if (!isActive) return
    val feedback = coroutineContext[SettingsApplyFeedback]
    val token = feedback?.begin(SettingsApplyFeedback.Kind.PASSWORD)
    state.update(updates.begin)
    launch {
        try {
            val banner = change().toBanner()
            currentCoroutineContext().ensureActive()
            if (token != null && feedback?.isCurrent(SettingsApplyFeedback.Kind.PASSWORD, token) != true) return@launch
            state.update { current -> updates.finish(current, banner) }
            if (banner == null) {
                flashApplySuccess(SettingsApplyFeedback.Kind.PASSWORD, token) { success ->
                    state.update { current -> flash(current, success) }
                }
            }
        } catch (stopped: CancellationException) {
            if (this@launchPasswordChange.isActive &&
                (token == null || feedback?.isCurrent(SettingsApplyFeedback.Kind.PASSWORD, token) == true)
            ) {
                state.update(updates.cancelled)
            }
            throw stopped
        }
    }
}

/** Marks a radio apply busy, then publishes the restart banner. Success does not flash. */
internal fun <S> CoroutineScope.launchRadioApply(
    state: MutableStateFlow<S>,
    updates: SettingsApplyState<S, SettingsApplyResult>,
    apply: suspend () -> SettingsApplyResult,
) {
    if (!isActive) return
    val feedback = coroutineContext[SettingsApplyFeedback]
    val token = feedback?.begin(SettingsApplyFeedback.Kind.RADIO)
    state.update(updates.begin)
    launch {
        try {
            val result = apply()
            currentCoroutineContext().ensureActive()
            if (token != null && feedback?.isCurrent(SettingsApplyFeedback.Kind.RADIO, token) != true) return@launch
            state.update { current -> updates.finish(current, result) }
        } catch (stopped: CancellationException) {
            if (this@launchRadioApply.isActive &&
                (token == null || feedback?.isCurrent(SettingsApplyFeedback.Kind.RADIO, token) == true)
            ) {
                state.update(updates.cancelled)
            }
            throw stopped
        }
    }
}

/** Marks a device action busy, then publishes the banner for its result. */
internal fun <S> CoroutineScope.launchDeviceAction(
    state: MutableStateFlow<S>,
    kind: SettingsApplyFeedback.Kind,
    updates: SettingsApplyState<S, ApplyBanner>,
    act: suspend () -> ApplyBanner,
) {
    if (!isActive) return
    val feedback = coroutineContext[SettingsApplyFeedback]
    val token = feedback?.begin(kind)
    state.update(updates.begin)
    launch {
        try {
            val banner = act()
            currentCoroutineContext().ensureActive()
            if (token != null && feedback?.isCurrent(kind, token) != true) return@launch
            state.update { current -> updates.finish(current, banner) }
        } catch (stopped: CancellationException) {
            if (this@launchDeviceAction.isActive && (token == null || feedback?.isCurrent(kind, token) == true)) {
                state.update(updates.cancelled)
            }
            throw stopped
        }
    }
}
