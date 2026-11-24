package app.musicflow.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.musicflow.core.common.ApplicationScope
import app.musicflow.core.model.NetworkStatus
import app.musicflow.core.model.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the default network. Emits on Wi-Fi ⇄ mobile transitions so the playback layer can
 * re-evaluate audio quality and resume after a drop.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun current(): NetworkStatus = statusOf(cm.activeNetwork?.let(cm::getNetworkCapabilities))

    val status: StateFlow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(statusOf(caps))
            }
            override fun onLost(network: Network) {
                trySend(current())
            }
            override fun onAvailable(network: Network) {
                trySend(statusOf(cm.getNetworkCapabilities(network)))
            }
        }
        trySend(current())
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, current())

    private fun statusOf(caps: NetworkCapabilities?): NetworkStatus {
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkStatus.Offline
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.OTHER
        }
        return NetworkStatus(
            type = type,
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }
}
