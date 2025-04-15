package com.example.berryharvest.data.repository

import android.app.Application
import com.example.berryharvest.data.network.EnhancedNetworkManager

/**
 * Provides access to all repositories in the application.
 * This class follows the Service Locator pattern and ensures
 * that only one instance of each repository is created.
 */
class RepositoryProvider(private val application: Application) {
    val networkManager = EnhancedNetworkManager(application)

    // Lazy initialization for all repositories using the new implementations
    val workerRepository: WorkerRepository =
        WorkerRepositoryImpl(application, networkManager)

    val assignmentRepository: AssignmentRepository =
        AssignmentRepositoryImpl(application, networkManager)

    val settingsRepository: SettingsRepository =
        SettingsRepositoryImpl(application, networkManager)

    val gatherRepository: GatherRepository =
        GatherRepositoryImpl(application, networkManager)

    // Mock the payment repository for now since we haven't implemented it
    val paymentRepository: PaymentRepository =
        PaymentRepositoryImpl(application, networkManager)

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
}