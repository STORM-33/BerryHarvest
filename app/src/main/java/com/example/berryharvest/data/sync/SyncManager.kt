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
 * Enhanced SyncManager that prevents interference with UI loading.
 * Includes startup grace period and intelligent sync scheduling.
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
    private val startupTime = AtomicLong(System.currentTimeMillis())

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
    private val STARTUP_GRACE_PERIOD = 5000L // 5 seconds after startup before allowing sync

    // Sync status tracking
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _pendingOperationsCount = MutableStateFlow(0)
    val pendingOperationsCount: StateFlow<Int> = _pendingOperationsCount.asStateFlow()

    // UI loading protection
    private val uiLoadingProtection = AtomicBoolean(true)

    init {
        updatePendingOperationsCount()

        // Disable UI loading protection after startup grace period
        coroutineScope.launch {
            delay(STARTUP_GRACE_PERIOD)
            uiLoadingProtection.set(false)
            Log.d(TAG, "UI loading protection disabled after grace period")
        }
    }

    fun startSyncMonitoring() {
        if (isShutdown.get()) {
            Log.w(TAG, "Cannot start sync monitoring - manager is shut down")
            return
        }

        Log.d(TAG, "Starting sync monitoring")
        stopSyncMonitoring()

        // Setup network monitoring with startup protection
        networkMonitoringJob = coroutineScope.launch {
            try {
                app.networkStatusManager.connectionState
                    .filter { it is ConnectionState.Connected }
                    .collect {
                        if (!isShutdown.get()) {
                            Log.d(TAG, "Network connection detected")

                            // Check if we're in startup grace period
                            val timeSinceStartup = System.currentTimeMillis() - startupTime.get()
                            if (uiLoadingProtection.get() && timeSinceStartup < STARTUP_GRACE_PERIOD) {
                                Log.d(TAG, "Skipping sync during UI loading protection period")
                                return@collect
                            }

                            // Only attempt sync if we haven't synced recently
                            if (System.currentTimeMillis() - lastSyncTime.get() > MIN_SYNC_INTERVAL) {
                                scheduleDelayedSync()
                            } else {
                                Log.d(TAG, "Skipping sync, last sync was too recent")
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in network monitoring", e)
                if (!isShutdown.get()) {
                    delay(5000)
                    startSyncMonitoring()
                }
            }
        }

        startPeriodicSyncJob()
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

                    if (!isShutdown.get() &&
                        app.networkStatusManager.isNetworkAvailable() &&
                        !uiLoadingProtection.get()) {

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
     * Schedule a delayed sync to avoid interfering with immediate UI operations
     */
    private fun scheduleDelayedSync() {
        coroutineScope.launch {
            // Add a small delay to let UI operations complete
            delay(2000)

            if (!isShutdown.get() &&
                app.networkStatusManager.isNetworkAvailable() &&
                !uiLoadingProtection.get()) {

                Log.d(TAG, "Executing scheduled delayed sync")
                performSync(silent = true)
            }
        }
    }

    /**
     * Performs synchronization with proper race condition prevention and error handling
     */
    suspend fun performSync(silent: Boolean = false): Result<Boolean> {
        if (isShutdown.get()) {
            return Result.Error(Exception("SyncManager is shut down"))
        }

        return syncMutex.withLock {
            performSyncInternal(silent)
        }
    }

    private suspend fun performSyncInternal(silent: Boolean): Result<Boolean> {
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

                val result = withTimeout(SYNC_TIMEOUT) {
                    try {
                        // Use safe repository sync that won't interfere with UI flows
                        val success = app.repositoryProvider.syncAllRepositoriesSafely()

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

                when (result) {
                    is Result.Success -> {
                        syncAttempts.set(0)
                        withContext(Dispatchers.Main) {
                            _syncStatus.value = SyncStatus.Completed
                        }
                        updatePendingOperationsCount()
                    }
                    is Result.Error -> {
                        withContext(Dispatchers.Main) {
                            _syncStatus.value = SyncStatus.Failed(result.message)
                        }
                    }
                    is Result.Loading -> {
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
    suspend fun forceSync(): Result<Boolean> {
        Log.d(TAG, "Force sync requested")
        lastSyncTime.set(0)
        syncAttempts.set(0)
        uiLoadingProtection.set(false) // Disable protection for forced sync
        return performSync(silent = false)
    }

    /**
     * Enable UI loading protection temporarily
     */
    fun enableUIProtection(durationMs: Long = STARTUP_GRACE_PERIOD) {
        uiLoadingProtection.set(true)
        coroutineScope.launch {
            delay(durationMs)
            uiLoadingProtection.set(false)
            Log.d(TAG, "UI protection disabled after ${durationMs}ms")
        }
    }

    fun isSyncInProgress(): Boolean = isSyncing.get()
    fun getLastSyncTime(): Long = lastSyncTime.get()
    fun getCurrentSyncAttempts(): Int = syncAttempts.get()
}

sealed class SyncStatus {
    object Idle : SyncStatus()
    object InProgress : SyncStatus()
    object Completed : SyncStatus()
    data class Failed(val reason: String) : SyncStatus()
}