package one.meshcore.shared.ui

import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Re-reads a system accessibility flag when a setting URI changes or the host resumes. iOS
 * environment values update live; a one-shot `remember(context)` froze the Android toggle until
 * process restart.
 *
 * [extraSubscribe] may register a second listener (for example Android 14+ contrast) and must
 * return an unsubscribe.
 */
@Composable
internal fun rememberAccessibilitySetting(
    vararg uris: Uri,
    extraSubscribe: ((Context, refresh: () -> Unit) -> (() -> Unit))? = null,
    read: (Context) -> Boolean,
): Boolean {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var enabled by remember(appContext) { mutableStateOf(read(appContext)) }

    DisposableEffect(appContext, uris.toList()) {
        fun refresh() {
            enabled = read(appContext)
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
            override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
        }
        val resolver = appContext.contentResolver
        for (uri in uris) {
            resolver.registerContentObserver(uri, false, observer)
        }
        val lifecycleOwner = context.findLifecycleOwner()
        val resumeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner?.lifecycle?.addObserver(resumeObserver)
        val unsubscribeExtra = extraSubscribe?.invoke(appContext, ::refresh)
        onDispose {
            resolver.unregisterContentObserver(observer)
            lifecycleOwner?.lifecycle?.removeObserver(resumeObserver)
            unsubscribeExtra?.invoke()
        }
    }
    return enabled
}

internal tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}
