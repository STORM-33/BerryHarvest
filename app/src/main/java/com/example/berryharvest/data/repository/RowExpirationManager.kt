package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages automatic expiration of collected rows that are older than 3 days.
 */
class RowExpirationManager(private val application: Application) {

    private val app: BerryHarvestApplication
        get() = application as BerryHarvestApplication

    private val repository: RowRepository
        get() = app.repositoryProvider.rowRepository

    private val scope = CoroutineScope(Dispatchers.IO)
    private var periodicCheckJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    companion object {
        private const val TAG = "RowExpirationManager"
        private const val CHECK_INTERVAL_HOURS = 6L // Check every 6 hours
        private const val CHECK_INTERVAL_MS = CHECK_INTERVAL_HOURS * 60 * 60 * 1000L
    }

    /**
     * Start periodic checking for expired rows.
     */
    fun startPeriodicExpiration() {
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Starting periodic row expiration checks")

            periodicCheckJob = scope.launch {
                while (isRunning.get()) {
                    try {
                        checkAndExpireRows()

                        // Wait for the next check interval
                        delay(CHECK_INTERVAL_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in periodic expiration check", e)
                        // Continue running even if there's an error
                        delay(CHECK_INTERVAL_MS)
                    }
                }
            }
        }
    }

    /**
     * Stop periodic checking.
     */
    fun stopPeriodicExpiration() {
        isRunning.set(false)
        periodicCheckJob?.cancel()
        periodicCheckJob = null
        Log.d(TAG, "Stopped periodic row expiration checks")
    }

    /**
     * Manually trigger expiration check (e.g., when app comes to foreground).
     */
    fun checkNow() {
        scope.launch {
            checkAndExpireRows()
        }
    }

    private suspend fun checkAndExpireRows() {
        try {
            Log.d(TAG, "Checking for expired collected rows")

            val result = repository.expireOldCollectedRows()
            when (result) {
                is Result.Success -> {
                    val expiredCount = result.data
                    if (expiredCount > 0) {
                        Log.i(TAG, "Automatically expired $expiredCount collected rows that were older than 3 days")
                    } else {
                        Log.d(TAG, "No expired rows found")
                    }
                }
                is Result.Error -> {
                    Log.e(TAG, "Error expiring old rows", result.exception)
                }
                is Result.Loading -> { /* Not applicable */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during expiration check", e)
        }
    }

    /**
     * Check if there are rows that will expire soon (within 24 hours).
     * Returns the count of such rows.
     */
    suspend fun getExpiringSoonCount(): Int {
        return try {
            val result = repository.getRowsExpiringSoon()
            when (result) {
                is Result.Success -> {
                    val count = result.data.size
                    if (count > 0) {
                        Log.d(TAG, "$count rows will expire within 24 hours")
                    }
                    count
                }
                is Result.Error -> {
                    Log.e(TAG, "Error getting expiring soon count", result.exception)
                    0
                }
                is Result.Loading -> 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting expiring soon count", e)
            0
        }
    }
}