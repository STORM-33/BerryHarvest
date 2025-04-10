package com.example.berryharvest

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.example.berryharvest.data.repository.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkConnectivityManager(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Store the callback to avoid garbage collection
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // Initialize with current state
        updateConnectionState()
        // Register for network changes
        registerNetworkCallback { isAvailable ->
            Log.d("Network", "Network state changed: $isAvailable")
        }
    }

    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val isAvailable = capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        Log.d("Network", "Network available: $isAvailable")
        return isAvailable
    }

    private fun updateConnectionState() {
        val isAvailable = isNetworkAvailable()
        _connectionState.value = if (isAvailable) {
            ConnectionState.Connected
        } else {
            ConnectionState.Disconnected
        }
    }

    fun registerNetworkCallback(callback: (Boolean) -> Unit) {
        // Unregister previous callback if exists
        unregisterCallback()

        // Create new callback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("Network", "Network available")
                _connectionState.value = ConnectionState.Connected
                callback(true)
            }

            override fun onLost(network: Network) {
                Log.d("Network", "Network lost")
                _connectionState.value = ConnectionState.Disconnected
                callback(false)
            }
        }

        // Register the callback
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            Log.e("Network", "Error registering network callback", e)
            _connectionState.value = ConnectionState.Error("Failed to monitor network state: ${e.message}")
        }
    }

    private fun unregisterCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e("Network", "Error unregistering network callback", e)
            }
        }
        networkCallback = null
    }
}