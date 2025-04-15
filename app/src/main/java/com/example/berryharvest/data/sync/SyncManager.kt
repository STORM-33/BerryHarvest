package com.example.berryharvest.data.sync

import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Manages data synchronization across all repositories when the network becomes available.
 */
class SyncManager(
    private val app: BerryHarvestApplication,
    private val coroutineScope: CoroutineScope
) {
    private val TAG = "SyncManager"
    private val isSyncing = AtomicBoolean(false)
    private var syncJobActive = false
    private var lastSyncTime = AtomicLong(0)
    private var syncAttempts = AtomicInteger(0)
    private val MAX_SYNC_ATTEMPTS = 3
    private val MIN_SYNC_INTERVAL = 10000L // 10 seconds minimum between syncs

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
        Log.d(TAG, "Starting sync monitoring")

        // Setup network monitoring
        coroutineScope.launch {
            app.networkStatusManager.connectionState
                .filter { it is ConnectionState.Connected }
                .collect {
                    Log.d(TAG, "Network connection detected, triggering sync")

                    // Only attempt sync if we haven't synced recently
                    if (System.currentTimeMillis() - lastSyncTime.get() > MIN_SYNC_INTERVAL) {
                        performSync()
                    } else {
                        Log.d(TAG, "Skipping sync, last sync was too recent")
                    }
                }
        }

        // Also schedule periodic sync attempts when online
        startPeriodicSyncJob()

        // Schedule periodic updating of pending operations count
        startPendingOperationsMonitoring()
    }

    private fun startPendingOperationsMonitoring() {
        coroutineScope.launch {
            while (true) {
                updatePendingOperationsCount()
                delay(30.seconds)
            }
        }
    }

    private fun updatePendingOperationsCount() {
        _pendingOperationsCount.value = app.repositoryProvider.getTotalPendingOperationsCount()
    }

    private fun startPeriodicSyncJob() {
        if (syncJobActive) return

        syncJobActive = true
        coroutineScope.launch {
            while (syncJobActive) {
                if (app.networkStatusManager.isNetworkAvailable()) {
                    // Only sync if we have pending changes and haven't synced recently
                    if (hasPendingChanges() &&
                        System.currentTimeMillis() - lastSyncTime.get() > MIN_SYNC_INTERVAL) {
                        Log.d(TAG, "Performing periodic sync check")
                        performSync(silent = true)
                    }
                }
                delay(60.seconds)
            }
        }
    }

    fun stopPeriodicSyncJob() {
        syncJobActive = false
    }

    suspend fun performSync(silent: Boolean = false): Result<Boolean> {
        // Prevent multiple simultaneous syncs
        if (isSyncing.getAndSet(true)) {
            Log.d(TAG, "Sync already in progress, skipping")
            return Result.Success(false)
        }

        if (!silent) {
            _syncStatus.value = SyncStatus.InProgress
        }

        // Check if we've exceeded max attempts
        if (syncAttempts.incrementAndGet() > MAX_SYNC_ATTEMPTS) {
            Log.d(TAG, "Maximum sync attempts reached, resetting and skipping")
            syncAttempts.set(0)
            isSyncing.set(false)
            _syncStatus.value = SyncStatus.Failed("Maximum sync attempts reached")
            return Result.Error(Exception("Maximum sync attempts reached"))
        }

        try {
            if (!app.networkStatusManager.isNetworkAvailable()) {
                Log.d(TAG, "No network available for sync")
                syncAttempts.decrementAndGet()
                isSyncing.set(false)
                _syncStatus.value = SyncStatus.Failed("Network not available")
                return Result.Error(Exception("Network not available"))
            }

            Log.d(TAG, "Starting sync process (attempt: ${syncAttempts.get()})")

            // Set timeout for the entire sync operation
            val result = withTimeout(30.seconds) {
                // Use the repositoryProvider's syncAllRepositories method
                val success = app.repositoryProvider.syncAllRepositories()

                if (success) {
                    Log.d(TAG, "Sync completed successfully")
                    Result.Success(true)
                } else {
                    Log.e(TAG, "Sync completed with errors")
                    Result.Error(Exception("Sync completed with errors"))
                }
            }

            // Reset attempts on success
            if (result is Result.Success) {
                syncAttempts.set(0)
                _syncStatus.value = SyncStatus.Completed
                // Update pending operations count after successful sync
                updatePendingOperationsCount()
            } else {
                _syncStatus.value = SyncStatus.Failed((result as? Result.Error)?.message ?: "Unknown error")
            }

            return result

        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Sync operation timed out after 30 seconds")
            _syncStatus.value = SyncStatus.Failed("Sync timed out")
            return Result.Error(Exception("Sync timed out"))
        } catch (e: Exception) {
            Log.e(TAG, "Error during sync", e)
            _syncStatus.value = SyncStatus.Failed(e.message ?: "Unknown error")
            return Result.Error(e)
        } finally {
            lastSyncTime.set(System.currentTimeMillis())
            isSyncing.set(false)
        }
    }

    fun hasPendingChanges(): Boolean {
        return app.repositoryProvider.hasPendingOperations()
    }

    fun getPendingChangesCount(): Int {
        return app.repositoryProvider.getTotalPendingOperationsCount()
    }
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