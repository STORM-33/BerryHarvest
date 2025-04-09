package com.example.berryharvest.data.repository

/**
 * Represents the current network connection state.
 */
sealed class ConnectionState {
    object Connected : ConnectionState()
    object Disconnected : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    // Helper function to check if connected
    fun isConnected(): Boolean = this is Connected
}