package com.example.berryharvest.data.sync

import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Manages data synchronization across all repositories when the network becomes available.
 * Enhanced version with proper race condition prevention and better error handling.
 */
class SyncManager(
    private val app: BerryHarvestApplication,
    private val coroutineScope: CoroutineScope
) {
    private val TAG = "SyncManager"

    // Mutex to prevent race conditions in sync operations
    private val syncMutex = Mutex()

    // Atomic flags for thread-safe state management
    private val isSyncing = AtomicBoolean(false)
    private val isShutdown = AtomicBoolean(false)
    private val lastSyncTime = AtomicLong(0)
    private val syncAttempts = AtomicInteger(0)

    // Jobs for lifecycle management
    private var networkMonitoringJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var pendingOperationsJob: Job? = null

    // Configuration constants
    private val MAX_SYNC_ATTEMPTS = 3
    private val MIN_SYNC_INTERVAL = 10000L // 10 seconds minimum between syncs
    private val SYNC_TIMEOUT = 30.seconds
    private val PERIODIC_SYNC_INTERVAL = 60.seconds
    private val PENDING_OPS_CHECK_INTERVAL = 30.seconds

    // Add a sync status flow to allow UI components to observe sync state
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    // Track the number of pending operations
    private val _pendingOperationsCount = MutableStateFlow(0)
    val pendingOperationsCount: StateFlow<Int> = _pendingOperationsCount.asStateFlow()

    init {
        // Initialize pending operations count
        updatePendingOperationsCount()
    }

    fun startSyncMonitoring() {
        if (isShutdown.get()) {
            Log.w(TAG, "Cannot start sync monitoring - manager is shut down")
            return
        }

        Log.d(TAG, "Starting sync monitoring")

        // Cancel any existing jobs first
        stopSyncMonitoring()

        // Setup network monitoring with proper exception handling
        networkMonitoringJob = coroutineScope.launch {
            try {
                app.networkStatusManager.connectionState
                    .filter { it is ConnectionState.Connected }
                    .collect {
                        if (!isShutdown.get()) {
                            Log.d(TAG, "Network connection detected, triggering sync")

                            // Only attempt sync if we haven't synced recently
                            if (System.currentTimeMillis() - lastSyncTime.get() > MIN_SYNC_INTERVAL) {
                                performSync()
                            } else {
                                Log.d(TAG, "Skipping sync, last sync was too recent")
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in network monitoring", e)
                if (!isShutdown.get()) {
                    // Restart monitoring after a delay if not shutting down
                    delay(5000)
                    startSyncMonitoring()
                }
            }
        }

        // Start periodic sync job
        startPeriodicSyncJob()

        // Start pending operations monitoring
        startPendingOperationsMonitoring()
    }

    fun stopSyncMonitoring() {
        Log.d(TAG, "Stopping sync monitoring")

        networkMonitoringJob?.cancel()
        networkMonitoringJob = null

        stopPeriodicSyncJob()
        stopPendingOperationsMonitoring()
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down SyncManager")
        isShutdown.set(true)
        stopSyncMonitoring()

        // Cancel any ongoing sync
        if (isSyncing.get()) {
            Log.d(TAG, "Cancelling ongoing sync due to shutdown")
        }
    }

    private fun startPendingOperationsMonitoring() {
        pendingOperationsJob = coroutineScope.launch {
            try {
                while (!isShutdown.get()) {
                    updatePendingOperationsCount()
                    delay(PENDING_OPS_CHECK_INTERVAL)
                }
            } catch (e: Exception) {
                if (!isShutdown.get()) {
                    Log.e(TAG, "Error in pending operations monitoring", e)
                }
            }
        }
    }

    private fun stopPendingOperationsMonitoring() {
        pendingOperationsJob?.cancel()
        pendingOperationsJob = null
    }

    private fun updatePendingOperationsCount() {
        try {
            val count = app.repositoryProvider.getTotalPendingOperationsCount()
            _pendingOperationsCount.value = count
        } catch (e: Exception) {
            Log.e(TAG, "Error updating pending operations count", e)
        }
    }

    private fun startPeriodicSyncJob() {
        if (periodicSyncJob?.isActive == true) return

        periodicSyncJob = coroutineScope.launch {
            try {
                while (!isShutdown.get()) {
                    delay(PERIODIC_SYNC_INTERVAL)

                    if (!isShutdown.get() && app.networkStatusManager.isNetworkAvailable()) {
                        // Only sync if we have pending changes and haven't synced recently
                        if (hasPendingChanges() &&
                            System.currentTimeMillis() - lastSyncTime.get() > MIN_SYNC_INTERVAL) {
                            Log.d(TAG, "Performing periodic sync check")
                            performSync(silent = true)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isShutdown.get()) {
                    Log.e(TAG, "Error in periodic sync job", e)
                }
            }
        }
    }

    fun stopPeriodicSyncJob() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }

    /**
     * Performs synchronization with proper race condition prevention and error handling
     */
    suspend fun performSync(silent: Boolean = false): Result<Boolean> {
        if (isShutdown.get()) {
            return Result.Error(Exception("SyncManager is shut down"))
        }

        // Use mutex to prevent multiple simultaneous syncs
        return syncMutex.withLock {
            performSyncInternal(silent)
        }
    }

    private suspend fun performSyncInternal(silent: Boolean): Result<Boolean> {
        // Double-check after acquiring lock
        if (isSyncing.get()) {
            Log.d(TAG, "Sync already in progress, skipping")
            return Result.Success(false)
        }

        return withContext(Dispatchers.IO) {
            isSyncing.set(true)

            try {
                if (!silent) {
                    withContext(Dispatchers.Main) {
                        _syncStatus.value = SyncStatus.InProgress
                    }
                }

                // Check if we've exceeded max attempts
                val currentAttempts = syncAttempts.incrementAndGet()
                if (currentAttempts > MAX_SYNC_ATTEMPTS) {
                    Log.d(TAG, "Maximum sync attempts reached, resetting and skipping")
                    syncAttempts.set(0)
                    withContext(Dispatchers.Main) {
                        _syncStatus.value = SyncStatus.Failed("Maximum sync attempts reached")
                    }
                    return@withContext Result.Error(Exception("Maximum sync attempts reached"))
                }

                if (!app.networkStatusManager.isNetworkAvailable()) {
                    Log.d(TAG, "No network available for sync")
                    syncAttempts.decrementAndGet()
                    withContext(Dispatchers.Main) {
                        _syncStatus.value = SyncStatus.Failed("Network not available")
                    }
                    return@withContext Result.Error(Exception("Network not available"))
                }

                Log.d(TAG, "Starting sync process (attempt: $currentAttempts)")

                // Set timeout for the entire sync operation
                val result = withTimeout(SYNC_TIMEOUT) {
                    try {
                        // Use the repositoryProvider's syncAllRepositories method
                        val success = app.repositoryProvider.syncAllRepositories()

                        if (success) {
                            Log.d(TAG, "Sync completed successfully")
                            Result.Success(true)
                        } else {
                            Log.e(TAG, "Sync completed with errors")
                            Result.Error(Exception("Sync completed with errors"))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during repository sync", e)
                        Result.Error(e)
                    }
                }

                // Handle result
                when (result) {
                    is Result.Success -> {
                        // Reset attempts on success
                        syncAttempts.set(0)
                        withContext(Dispatchers.Main) {
                            _syncStatus.value = SyncStatus.Completed
                        }
                        // Update pending operations count after successful sync
                        updatePendingOperationsCount()
                    }
                    is Result.Error -> {
                        withContext(Dispatchers.Main) {
                            _syncStatus.value = SyncStatus.Failed(result.message)
                        }
                    }
                    is Result.Loading -> {
                        // This shouldn't happen in this context
                        Log.w(TAG, "Unexpected Loading result from sync")
                    }
                }

                result

            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Sync operation timed out after ${SYNC_TIMEOUT.inWholeSeconds} seconds")
                withContext(Dispatchers.Main) {
                    _syncStatus.value = SyncStatus.Failed("Sync timed out")
                }
                Result.Error(Exception("Sync timed out"))
            } catch (e: Exception) {
                Log.e(TAG, "Error during sync", e)
                withContext(Dispatchers.Main) {
                    _syncStatus.value = SyncStatus.Failed(e.message ?: "Unknown error")
                }
                Result.Error(e)
            } finally {
                lastSyncTime.set(System.currentTimeMillis())
                isSyncing.set(false)
            }
        }
    }

    fun hasPendingChanges(): Boolean {
        return try {
            app.repositoryProvider.hasPendingOperations()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pending changes", e)
            false
        }
    }

    fun getPendingChangesCount(): Int {
        return try {
            app.repositoryProvider.getTotalPendingOperationsCount()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pending changes count", e)
            0
        }
    }

    /**
     * Force a sync attempt regardless of timing constraints
     */
    suspend fun forcSync(): Result<Boolean> {
        Log.d(TAG, "Force sync requested")
        lastSyncTime.set(0) // Reset timing constraint
        syncAttempts.set(0) // Reset attempt counter
        return performSync(silent = false)
    }

    /**
     * Check if sync is currently in progress
     */
    fun isSyncInProgress(): Boolean = isSyncing.get()

    /**
     * Get the time of the last successful sync
     */
    fun getLastSyncTime(): Long = lastSyncTime.get()

    /**
     * Get current sync attempt count
     */
    fun getCurrentSyncAttempts(): Int = syncAttempts.get()
}

/**
 * Represents the current status of the synchronization process.
 */
sealed class SyncStatus {
    object Idle : SyncStatus()
    object InProgress : SyncStatus()
    object Completed : SyncStatus()
    data class Failed(val reason: String) : SyncStatus()
}