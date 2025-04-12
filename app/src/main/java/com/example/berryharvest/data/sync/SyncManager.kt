package com.example.berryharvest.data.sync

import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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

        // Check if we've exceeded max attempts
        if (syncAttempts.incrementAndGet() > MAX_SYNC_ATTEMPTS) {
            Log.d(TAG, "Maximum sync attempts reached, resetting and skipping")
            syncAttempts.set(0)
            isSyncing.set(false)
            return Result.Error(Exception("Maximum sync attempts reached"))
        }

        try {
            if (!app.networkStatusManager.isNetworkAvailable()) {
                Log.d(TAG, "No network available for sync")
                syncAttempts.decrementAndGet()
                isSyncing.set(false)
                return Result.Error(Exception("Network not available"))
            }

            Log.d(TAG, "Starting sync process (attempt: ${syncAttempts.get()})")

            // Set timeout for the entire sync operation
            val result = withTimeout(30.seconds) {
                val repositories = app.repositoryProvider

                // Sync workers
                val workerResult = repositories.workerRepository.syncPendingChanges()

                // Sync assignments
                val assignmentResult = repositories.assignmentRepository.syncPendingChanges()

                // Sync settings
                val settingsResult = repositories.settingsRepository.syncPendingChanges()

                // Sync payments - add this line
                val paymentResult = repositories.paymentRepository.syncPendingChanges()

                // Check results
                val allSuccessful =
                    workerResult is Result.Success &&
                            assignmentResult is Result.Success &&
                            settingsResult is Result.Success &&
                            paymentResult is Result.Success // Add payment result check

                if (allSuccessful) {
                    Log.d(TAG, "Sync completed successfully")
                    Result.Success(true)
                } else {
                    val errors = listOfNotNull(
                        (workerResult as? Result.Error)?.message,
                        (assignmentResult as? Result.Error)?.message,
                        (settingsResult as? Result.Error)?.message,
                        (paymentResult as? Result.Error)?.message // Add payment error
                    ).joinToString("; ")

                    Log.e(TAG, "Sync completed with errors: $errors")
                    Result.Error(Exception("Sync errors: $errors"))
                }
            }

            // Reset attempts on success
            if (result is Result.Success) {
                syncAttempts.set(0)
            }

            return result

        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Sync operation timed out after 30 seconds")
            return Result.Error(Exception("Sync timed out"))
        } catch (e: Exception) {
            Log.e(TAG, "Error during sync", e)
            return Result.Error(e)
        } finally {
            lastSyncTime.set(System.currentTimeMillis())
            isSyncing.set(false)
        }
    }

    fun hasPendingChanges(): Boolean {
        val repositories = app.repositoryProvider

        return repositories.workerRepository.hasPendingOperations() ||
                repositories.assignmentRepository.hasPendingOperations() ||
                repositories.settingsRepository.hasPendingOperations() ||
                repositories.paymentRepository.hasPendingOperations() // Add payment repository check
    }

    fun getPendingChangesCount(): Int {
        val repositories = app.repositoryProvider

        return repositories.workerRepository.getPendingOperationsCount() +
                repositories.assignmentRepository.getPendingOperationsCount() +
                repositories.settingsRepository.getPendingOperationsCount() +
                repositories.paymentRepository.getPendingOperationsCount() // Add payment repository count
    }
}