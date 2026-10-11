// AndroidOnly: WP-307 Process-owned native snapshot and network integration; map failures never gate messaging.
package com.meshcoreone.android.app.container

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.meshcoreone.android.core.maps.MapLibreSnapshotRenderer
import com.meshcoreone.android.core.maps.MapSnapshotRenderer
import com.meshcoreone.android.core.maps.MapSnapshotRequest
import com.meshcoreone.android.core.maps.MapSnapshotStore
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface ChatMapNetwork {
    data object Online : ChatMapNetwork
    data object Offline : ChatMapNetwork
    data class Failed(val cause: Exception) : ChatMapNetwork
}

internal class AppChatMapPreviews(
    context: Context,
    scope: CoroutineScope,
    renderer: MapSnapshotRenderer<Bitmap> = nativeRenderer(context),
    private val online: () -> Boolean = {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        manager.activeNetwork?.let(manager::getNetworkCapabilities)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    },
    private val enabled: suspend () -> Boolean = { true },
) : AutoCloseable {
    private val logger = Logger.getLogger("com.meshcoreone.android.ChatMapPreviews")
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableNetwork = MutableStateFlow<ChatMapNetwork>(ChatMapNetwork.Offline)
    val network: StateFlow<ChatMapNetwork> = mutableNetwork.asStateFlow()
    @Volatile private var closed = false
    private var registered = false

    val snapshots = MapSnapshotStore(object : MapSnapshotRenderer<Bitmap> {
        override suspend fun render(request: MapSnapshotRequest): Bitmap? = try {
            if (!enabled()) {
                logger.fine("Chat map thumbnail request declined by privacy preference")
                null
            } else {
                withTimeoutOrNull(30_000) { renderer.render(request) }.also {
                    if (it == null) logger.warning("Chat map snapshot unavailable or timed out")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            logger.log(Level.WARNING, "Chat map snapshot failed", problem)
            null
        } catch (problem: LinkageError) {
            logger.log(Level.WARNING, "Native chat map renderer is unavailable", problem)
            null
        }
    }, scope, { it.allocationByteCount.toLong() })

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()
        override fun onLost(network: Network) = refreshNetwork()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshNetwork()
    }

    init {
        refreshNetwork()
        try {
            requireNotNull(connectivity).registerDefaultNetworkCallback(callback)
            registered = true
        } catch (problem: Exception) {
            logger.log(Level.WARNING, "Chat map network observation failed", problem)
            mutableNetwork.value = ChatMapNetwork.Failed(problem)
        }
    }

    fun refreshNetwork() {
        if (closed) return
        val next = try {
            if (online()) ChatMapNetwork.Online else ChatMapNetwork.Offline
        } catch (problem: Exception) {
            logger.log(Level.WARNING, "Chat map network availability failed", problem)
            ChatMapNetwork.Failed(problem)
        }
        val previous = mutableNetwork.value
        mutableNetwork.value = next
        if (next == ChatMapNetwork.Online && previous != ChatMapNetwork.Online) snapshots.clearFailures()
    }

    fun request(request: MapSnapshotRequest) {
        check(!closed) { "Chat map previews are closed" }
        snapshots.request(request)
    }

    fun retry(request: MapSnapshotRequest) {
        check(!closed) { "Chat map previews are closed" }
        refreshNetwork()
        snapshots.retry(request)
        snapshots.request(request)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (registered) {
            try {
                requireNotNull(connectivity).unregisterNetworkCallback(callback)
            } catch (problem: Exception) {
                logger.log(Level.WARNING, "Chat map network observation cleanup failed", problem)
            }
        }
        snapshots.close()
    }

    private companion object {
        fun nativeRenderer(context: Context): MapSnapshotRenderer<Bitmap> {
            val density = context.resources.displayMetrics.density
            val renderer = lazy {
                MapLibreSnapshotRenderer(context, (250 * density).roundToInt(), (150 * density).roundToInt())
            }
            return object : MapSnapshotRenderer<Bitmap> {
                override suspend fun render(request: MapSnapshotRequest): Bitmap? =
                    withContext(Dispatchers.Main.immediate) { renderer.value.render(request) }
            }
        }
    }
}
