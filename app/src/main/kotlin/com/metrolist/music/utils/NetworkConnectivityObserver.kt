/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple NetworkConnectivityObserver based on OuterTune's implementation
 * Provides network connectivity monitoring for auto-play functionality
 */
class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkStatus = MutableStateFlow(isCurrentlyConnected())
    val networkStatus = _networkStatus.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkStatus.value = isCurrentlyConnected()
        }

        override fun onLost(network: Network) {
            // During onLost the lost network can still be reported as the active one, so don't
            // trust activeNetwork alone or the status can stay "connected" after it drops.
            val active = connectivityManager.activeNetwork
            _networkStatus.value = active != null && active != network && isCurrentlyConnected()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _networkStatus.value = isCurrentlyConnected()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // Fallback: assume connected if registration fails
            _networkStatus.value = true
        }
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
    
    /**
     * Check current connectivity state synchronously
     */
    fun isCurrentlyConnected(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            // Check if we have internet capability
            networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        private var instance: NetworkConnectivityObserver? = null

        /** One app-wide observer, shared by Hilt injection and the Compose UI. */
        fun get(context: Context): NetworkConnectivityObserver =
            instance ?: synchronized(this) {
                instance ?: NetworkConnectivityObserver(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Whether the device has a network right now, updating live as it drops and returns.
 * FletchTunes' offline mode keys off this: offline, Library shows only what is downloaded.
 */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val status = remember { NetworkConnectivityObserver.get(context).networkStatus }
    return status.collectAsState().value
}
