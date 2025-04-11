package com.example.berryharvest

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.berryharvest.data.repository.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized manager for tracking and broadcasting network status across the app.
 */
class NetworkStatusManager(private val context: Context) {

    // StateFlow for use with Kotlin coroutines
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // LiveData for traditional observers
    private val _connectionStateLiveData = MutableLiveData<ConnectionState>(ConnectionState.Disconnected)
    val connectionStateLiveData: LiveData<ConnectionState> = _connectionStateLiveData

    // For easy checking
    private var _isOnline = false
    val isOnline: Boolean get() = _isOnline

    // Reference to the network manager implementation
    private val networkManager = NetworkConnectivityManager(context)

    init {
        // Initialize with current network state
        updateConnectionState(networkManager.isNetworkAvailable())

        // Register for network changes
        networkManager.registerNetworkCallback { isAvailable ->
            updateConnectionState(isAvailable)
        }
    }

    private fun updateConnectionState(isAvailable: Boolean) {
        _isOnline = isAvailable
        val state = if (isAvailable) ConnectionState.Connected else ConnectionState.Disconnected
        _connectionState.value = state
        _connectionStateLiveData.postValue(state)
    }

    fun registerNetworkCallback(callback: (Boolean) -> Unit) {
        networkManager.registerNetworkCallback(callback)
    }

    fun isNetworkAvailable(): Boolean {
        return networkManager.isNetworkAvailable()
    }
}