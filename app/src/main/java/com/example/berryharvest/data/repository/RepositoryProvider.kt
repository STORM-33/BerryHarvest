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

    // Lazy initialization for all repositories
    val workerRepository: WorkerRepository by lazy {
        WorkerRepository(application, networkManager)
    }

    val assignmentRepository: AssignmentRepository by lazy {
        AssignmentRepository(application, networkManager)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(application, networkManager)
    }

    // Add the new payment repository
    val paymentRepository: PaymentRepository by lazy {
        PaymentRepositoryImpl(application, networkManager)
    }

    /**
     * Close all repositories and release resources.
     * Call this method when the application is shutting down.
     */
    fun closeAll() {
        workerRepository.close()
        assignmentRepository.close()
        settingsRepository.close()
        paymentRepository.close() // Add closing the payment repository
    }
}