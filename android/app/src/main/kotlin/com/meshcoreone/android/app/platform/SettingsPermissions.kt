// AndroidOnly: WP-407 Activity-owned permission prompts; no radio session is created by a platform permission.
package com.meshcoreone.android.app.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.meshcoreone.android.feature.settings.device.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

internal class SettingsPermissions(
    private val context: Context,
    private val launchNotifications: () -> Unit,
    private val launchLocation: () -> Unit,
) {
    private val requests = context.getSharedPreferences("platform_permission_requests", Context.MODE_PRIVATE)
    private var notificationAnswer: CompletableDeferred<Boolean>? = null
    private var locationAnswer: CompletableDeferred<Boolean>? = null
    private val locationState = MutableStateFlow(LocationAuthorization.NOT_DETERMINED)
    private val requesting = MutableStateFlow(false)

    val notifications = object : NotificationPermissionPort {
        override suspend fun status(): NotificationAuthorization {
            val enabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            val granted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (enabled && granted) requests.edit().putBoolean("notifications", true).apply()
            return when {
                enabled && granted -> NotificationAuthorization.AUTHORIZED
                granted || Build.VERSION.SDK_INT < 33 || requests.getBoolean("notifications", false) -> NotificationAuthorization.DENIED
                else -> NotificationAuthorization.NOT_DETERMINED
            }
        }
        override suspend fun request(): Boolean {
            if (Build.VERSION.SDK_INT < 33) return status() == NotificationAuthorization.AUTHORIZED
            notificationAnswer?.let { return it.await() }
            val answer = CompletableDeferred<Boolean>()
            notificationAnswer = answer
            requests.edit().putBoolean("notifications", true).apply()
            try { launchNotifications() }
            catch (failure: RuntimeException) {
                notificationAnswer = null
                answer.completeExceptionally(failure)
                throw failure
            }
            return answer.await()
        }
    }
    val location = object : LocationPermissionPort {
        override val authorization = locationState
        override val isRequestingLocation = requesting
        override fun requestPermissionIfNeeded() {
            refresh()
            if (locationState.value == LocationAuthorization.NOT_DETERMINED && !requesting.value) {
                locationAnswer = CompletableDeferred()
                requesting.value = true
                requests.edit().putBoolean("location", true).apply()
                try { launchLocation() }
                catch (failure: RuntimeException) {
                    requesting.value = false
                    locationAnswer?.completeExceptionally(failure)
                    locationAnswer = null
                    throw failure
                }
            }
        }
        override suspend fun requestCurrentLocation() {
            requestPermissionIfNeeded()
            locationAnswer?.await()
            refresh()
        }
    }
    fun refresh() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requests.edit().putBoolean("location", true).apply()
        }
        locationState.value = when {
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
                LocationAuthorization.AUTHORIZED
            requests.getBoolean("location", false) -> LocationAuthorization.DENIED
            else -> LocationAuthorization.NOT_DETERMINED
        }
    }
    fun notificationResult(granted: Boolean) { notificationAnswer?.complete(granted); notificationAnswer = null }
    fun locationResult(granted: Boolean) {
        locationAnswer?.complete(granted)
        locationAnswer = null
        requesting.value = false
        refresh()
    }
    fun close() {
        notificationAnswer?.cancel(); notificationAnswer = null
        locationAnswer?.cancel(); locationAnswer = null
        requesting.value = false
    }
}

@Composable
internal fun rememberSettingsPermissions(): SettingsPermissions {
    val context = LocalContext.current
    var ports by remember { mutableStateOf<SettingsPermissions?>(null) }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ports?.notificationResult(it) }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ports?.locationResult(it) }
    val result = remember(context, notification, location) {
        SettingsPermissions(context, { notification.launch(Manifest.permission.POST_NOTIFICATIONS) },
            { location.launch(Manifest.permission.ACCESS_COARSE_LOCATION) })
    }
    SideEffect { ports = result }
    LifecycleResumeEffect(result) { result.refresh(); onPauseOrDispose {} }
    DisposableEffect(result) { onDispose { result.close() } }
    return result
}
