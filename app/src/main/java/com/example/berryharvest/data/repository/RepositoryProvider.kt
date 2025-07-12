package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.NetworkConnectivityManager

/**
 * Provides access to all repositories in the application.
 * This class follows the Service Locator pattern and ensures
 * that only one instance of each repository is created.
 */
class RepositoryProvider(private val application: Application) {
    val networkManager = NetworkConnectivityManager(application)
    val databaseTransactionManager = DatabaseTransactionManager(application as BerryHarvestApplication)

    // Lazy initialization for all repositories with the transaction manager
    val workerRepository: WorkerRepository =
        WorkerRepositoryImpl(application, networkManager, databaseTransactionManager)

    val assignmentRepository: AssignmentRepository =
        AssignmentRepositoryImpl(application, networkManager, databaseTransactionManager)

    val settingsRepository: SettingsRepository =
        SettingsRepositoryImpl(application, networkManager, databaseTransactionManager)

    val gatherRepository: GatherRepository =
        GatherRepositoryImpl(application, networkManager, databaseTransactionManager)

    val paymentRepository: PaymentRepository =
        PaymentRepositoryImpl(application, networkManager, databaseTransactionManager)

    val rowRepository: RowRepository =
        RowRepositoryImpl(application, networkManager, databaseTransactionManager)

    /**
     * Close all repositories and release resources.
     * Call this method when the application is shutting down.
     */
    fun closeAll() {
        (workerRepository as BaseRepositoryImpl<*>).close()
        (assignmentRepository as BaseRepositoryImpl<*>).close()
        (settingsRepository as BaseRepositoryImpl<*>).close()
        (gatherRepository as BaseRepositoryImpl<*>).close()
        (paymentRepository as BaseRepositoryImpl<*>).close()
        (rowRepository as BaseRepositoryImpl<*>).close()
    }
    /**
     * Synchronize all repositories with the server.
     * @return True if all syncs were successful
     */
    suspend fun syncAllRepositories(): Boolean {
        var allSuccessful = true

        // Worker repository
        val workerResult = workerRepository.syncPendingChanges()
        if (workerResult !is Result.Success) {
            allSuccessful = false
        }

        // Assignment repository
        val assignmentResult = assignmentRepository.syncPendingChanges()
        if (assignmentResult !is Result.Success) {
            allSuccessful = false
        }

        // Settings repository
        val settingsResult = settingsRepository.syncPendingChanges()
        if (settingsResult !is Result.Success) {
            allSuccessful = false
        }

        // Gather repository
        val gatherResult = gatherRepository.syncPendingChanges()
        if (gatherResult !is Result.Success) {
            allSuccessful = false
        }

        // Payment repository
        val paymentResult = paymentRepository.syncPendingChanges()
        if (paymentResult !is Result.Success) {
            allSuccessful = false
        }

        //Row repository
        val rowResult = rowRepository.syncPendingChanges()
        if (rowResult !is Result.Success) {
            allSuccessful = false
        }

        return allSuccessful
    }

    /**
     * Check if any repository has pending operations.
     */
    fun hasPendingOperations(): Boolean {
        return workerRepository.hasPendingOperations() ||
                assignmentRepository.hasPendingOperations() ||
                settingsRepository.hasPendingOperations() ||
                gatherRepository.hasPendingOperations() ||
                rowRepository.hasPendingOperations() ||
                paymentRepository.hasPendingOperations()
    }

    /**
     * Get the total count of pending operations across all repositories.
     */
    fun getTotalPendingOperationsCount(): Int {
        return workerRepository.getPendingOperationsCount() +
                assignmentRepository.getPendingOperationsCount() +
                settingsRepository.getPendingOperationsCount() +
                gatherRepository.getPendingOperationsCount() +
                paymentRepository.getPendingOperationsCount()
    }

    /**
     * Safely sync all repositories without interfering with UI data flows.
     * This method performs sync operations in isolation to prevent UI flickering.
     */
    suspend fun syncAllRepositoriesSafely(): Boolean {
        return try {
            Log.d("RepositoryProvider", "Starting safe sync of all repositories")

            // Sync each repository individually with error isolation
            val results = listOf(
                safeSyncRepository("Worker") { workerRepository.syncPendingChanges() },
                safeSyncRepository("Assignment") { assignmentRepository.syncPendingChanges() },
                safeSyncRepository("Settings") { settingsRepository.syncPendingChanges() },
                safeSyncRepository("Gather") { gatherRepository.syncPendingChanges() },
                safeSyncRepository("Payment") { paymentRepository.syncPendingChanges() }
            )

            val successCount = results.count { it }
            Log.d("RepositoryProvider", "Safe sync completed: $successCount/${results.size} repositories synced successfully")

            // Return true if at least 80% of repositories synced successfully
            successCount >= (results.size * 0.8).toInt()

        } catch (e: Exception) {
            Log.e("RepositoryProvider", "Error in safe sync", e)
            false
        }
    }

    /**
     * Safely sync a single repository with error isolation
     */
    private suspend fun safeSyncRepository(
        repositoryName: String,
        syncOperation: suspend () -> Result<Boolean>
    ): Boolean {
        return try {
            when (val result = syncOperation()) {
                is Result.Success -> {
                    Log.d("RepositoryProvider", "$repositoryName repository synced successfully")
                    true
                }
                is Result.Error -> {
                    Log.w("RepositoryProvider", "$repositoryName repository sync failed: ${result.message}")
                    false // Don't fail entire sync for one repository
                }
                is Result.Loading -> {
                    Log.w("RepositoryProvider", "$repositoryName repository sync returned Loading state")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("RepositoryProvider", "Error syncing $repositoryName repository", e)
            false
        }
    }
}