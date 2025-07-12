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

    // Store external callbacks
    private val externalCallbacks = mutableListOf<(Boolean) -> Unit>()

    init {
        // Initialize with current state
        updateConnectionState()
        // DON'T register a callback here - let NetworkStatusManager do it
        Log.d("Network", "NetworkConnectivityManager initialized")
    }

    fun isNetworkAvailable(): Boolean {
        try {
            val network = connectivityManager.activeNetwork
            if (network == null) {
                Log.d("Network", "No active network")
                return false
            }

            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val isAvailable = capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )

            Log.d("Network", "Network available: $isAvailable")
            return isAvailable
        } catch (e: Exception) {
            Log.e("Network", "Error checking network availability", e)
            return false
        }
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
        // Add to external callbacks list
        externalCallbacks.add(callback)

        // Only register system callback if this is the first external callback
        if (externalCallbacks.size == 1) {
            registerSystemCallback()
        }

        Log.d("Network", "Registered callback. Total callbacks: ${externalCallbacks.size}")
    }

    private fun registerSystemCallback() {
        // Unregister previous callback if exists
        unregisterSystemCallback()

        // Create new callback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("Network", "System callback: Network available")
                _connectionState.value = ConnectionState.Connected
                // Notify all external callbacks
                externalCallbacks.forEach { it(true) }
            }

            override fun onLost(network: Network) {
                Log.d("Network", "System callback: Network lost")
                _connectionState.value = ConnectionState.Disconnected
                // Notify all external callbacks
                externalCallbacks.forEach { it(false) }
            }
        }

        // Register the callback with the system
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
            Log.d("Network", "✅ System network callback registered")
        } catch (e: Exception) {
            Log.e("Network", "❌ Error registering network callback", e)
            _connectionState.value = ConnectionState.Error("Failed to monitor network state: ${e.message}")
        }
    }

    private fun unregisterSystemCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d("Network", "System network callback unregistered")
            } catch (e: Exception) {
                Log.e("Network", "Error unregistering network callback", e)
            }
        }
        networkCallback = null
    }

    fun unregisterNetworkCallback(callback: (Boolean) -> Unit) {
        externalCallbacks.remove(callback)

        // If no more external callbacks, unregister system callback
        if (externalCallbacks.isEmpty()) {
            unregisterSystemCallback()
        }

        Log.d("Network", "Unregistered callback. Remaining callbacks: ${externalCallbacks.size}")
    }

    fun getConnectionStateForDisplay(): String {
        return when (val state = _connectionState.value) {
            is ConnectionState.Connected -> "Підключено"
            is ConnectionState.Disconnected -> "Офлайн режим"
            is ConnectionState.Error -> "Помилка: ${state.message}"
        }
    }

    fun getConnectionStateColor(context: Context): Int {
        return when (_connectionState.value) {
            is ConnectionState.Connected -> context.getColor(android.R.color.holo_green_dark)
            is ConnectionState.Disconnected -> context.getColor(android.R.color.holo_orange_dark)
            is ConnectionState.Error -> context.getColor(android.R.color.holo_red_dark)
        }
    }

    // Cleanup method
    fun cleanup() {
        externalCallbacks.clear()
        unregisterSystemCallback()
        Log.d("Network", "NetworkConnectivityManager cleaned up")
    }
}