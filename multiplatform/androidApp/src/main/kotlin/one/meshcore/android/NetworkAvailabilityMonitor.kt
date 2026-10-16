package one.meshcore.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.meshcore.shared.map.isNetworkOfflineFromInternetCapability

/**
 * Live network-offline flag for the map badge and style gate. iOS
 * `OfflineMapService` publishes `isNetworkAvailable` from `NWPathMonitor`;
 * this is the Android counterpart, driven by [ConnectivityManager.NetworkCallback].
 */
class NetworkAvailabilityMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _offline = MutableStateFlow(readOffline())
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()

        override fun onLost(network: Network) = publish()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publish()
    }

    fun start() {
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
        publish()
    }

    private fun publish() {
        _offline.value = readOffline()
    }

    private fun readOffline(): Boolean {
        val network = connectivity?.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        return isNetworkOfflineFromInternetCapability(
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
    }
}
