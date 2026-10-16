package one.meshcore.shared.chat.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_timestamp_unavailable
import one.meshcore.shared.ui.findLifecycleOwner
import org.jetbrains.compose.resources.stringResource
import java.time.ZoneId
import java.util.Locale

@Composable
internal actual fun rememberTimestampFormatter(): TimestampFormatter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = context.findLifecycleOwner()
    return rememberAndroidTimestampFormatter(
        context,
        lifecycleOwner,
        configuration,
        readSettings = ::readTimestampSettings,
        subscribe = ::subscribeTimestampSettings,
        unavailableLabel = stringResource(Res.string.android_timestamp_unavailable),
    )
}

@Composable
internal fun rememberAndroidTimestampFormatter(
    context: Context,
    lifecycleOwner: LifecycleOwner?,
    configuration: Configuration,
    readSettings: (Context) -> TimestampSettings,
    subscribe: (Context, LifecycleOwner?, () -> Unit) -> (() -> Unit),
    unavailableLabel: String,
): TimestampFormatter {
    var revision by remember { mutableIntStateOf(0) }
    val refresh by rememberUpdatedState { revision += 1 }
    DisposableEffect(context, lifecycleOwner, subscribe) {
        val unsubscribe = subscribe(context, lifecycleOwner, refresh)
        onDispose { unsubscribe() }
    }
    val settings = remember(context, configuration, revision, readSettings) { readSettings(context) }
    return remember(settings, unavailableLabel) {
        AndroidTimestampFormatter(settings.locale, settings.is24Hour, settings.zone, unavailableLabel)
    }
}

internal fun readTimestampSettings(context: Context): TimestampSettings = TimestampSettings(
    context.resources.configuration.locales[0],
    DateFormat.is24HourFormat(context),
    ZoneId.systemDefault(),
)

internal fun subscribeTimestampSettings(
    context: Context,
    lifecycleOwner: LifecycleOwner?,
    refresh: () -> Unit,
): () -> Unit {
    val callbacks = TimestampSettingsCallbacks(refresh)
    val resolver = context.contentResolver
    resolver.registerContentObserver(callbacks.settingUri, false, callbacks.observer)
    val receiver = callbacks.receiver
    val filter = callbacks.timezoneFilter
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
    }
    val resumeObserver = callbacks.resumeObserver
    lifecycleOwner?.lifecycle?.addObserver(resumeObserver)
    return {
        resolver.unregisterContentObserver(callbacks.observer)
        context.unregisterReceiver(receiver)
        lifecycleOwner?.lifecycle?.removeObserver(resumeObserver)
    }
}

internal data class TimestampSettings(val locale: Locale, val is24Hour: Boolean, val zone: ZoneId)

internal class TimestampSettingsCallbacks(refresh: () -> Unit) {
    val settingUri: Uri = Settings.System.getUriFor(Settings.System.TIME_12_24)
    val timezoneFilter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
        override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }
    val resumeObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) refresh()
    }
}
