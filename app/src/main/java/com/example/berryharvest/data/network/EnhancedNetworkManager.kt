package com.example.berryharvest.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.example.berryharvest.data.repository.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enhanced network connectivity manager that provides real-time updates
 * about network status and capabilities.
 */
class EnhancedNetworkManager(private val context: Context) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        // Initialize with current network state
        updateConnectionState()
        // Register for network changes
        registerNetworkCallback()
    }

    private fun updateConnectionState() {
        val isAvailable = isNetworkAvailable()
        _connectionState.value = if (isAvailable) {
            ConnectionState.Connected
        } else {
            ConnectionState.Disconnected
        }
        Log.d("NetworkManager", "Network state updated: ${_connectionState.value}")
    }

    private fun registerNetworkCallback() {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("NetworkManager", "Network available")
                _connectionState.value = ConnectionState.Connected
            }

            override fun onLost(network: Network) {
                Log.d("NetworkManager", "Network lost")
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onUnavailable() {
                Log.d("NetworkManager", "Network unavailable")
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )

                val connectionState = if (hasInternet) {
                    ConnectionState.Connected
                } else {
                    ConnectionState.Disconnected
                }

                if (_connectionState.value != connectionState) {
                    Log.d("NetworkManager", "Network capabilities changed: $connectionState")
                    _connectionState.value = connectionState
                }
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("NetworkManager", "Could not register network callback", e)
            _connectionState.value = ConnectionState.Error("Failed to monitor network state: ${e.message}")
        }
    }

    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val isAvailable = capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                )
        Log.d("NetworkManager", "Network available: $isAvailable")
        return isAvailable
    }
}