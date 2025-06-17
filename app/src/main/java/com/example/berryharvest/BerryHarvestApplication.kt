package com.example.berryharvest

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.berryharvest.data.repository.RepositoryProvider
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.PaymentBalance
import com.example.berryharvest.data.model.PaymentRecord
import com.example.berryharvest.data.model.Settings
import com.example.berryharvest.data.sync.SyncManager
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.AppConfiguration
import io.realm.kotlin.mongodb.subscriptions
import io.realm.kotlin.mongodb.sync.MutableSubscriptionSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import com.example.berryharvest.data.model.Row
import com.example.berryharvest.data.repository.RowExpirationManager

/**
 * Main application class responsible for initializing global dependencies
 * and managing Realm database lifecycle.
 * Enhanced version with proper resource management and shutdown handling.
 */
class BerryHarvestApplication : Application() {

    // MongoDB Realm App instance
    var app: App? = null
        private set

    // Flag to force offline mode if network is unavailable
    var forceOfflineMode: Boolean = false

    // Repository provider for accessing all repositories
    val repositoryProvider by lazy {
        RepositoryProvider(this)
    }

    // Centralized network status manager
    val networkStatusManager by lazy {
        NetworkStatusManager(this)
    }

    // Global synchronization manager
    lateinit var syncManager: SyncManager
        private set

    lateinit var rowExpirationManager: RowExpirationManager

    // Application-scoped coroutine scope for background operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + createCoroutineErrorHandler())

    // Flags to track application state
    private val isShuttingDown = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    // Models included in the Realm schema
    val realmModels = setOf(
        Worker::class,
        Gather::class,
        Assignment::class,
        Settings::class,
        PaymentRecord::class,
        PaymentBalance::class,
        Row::class
    )

    // RealmManager instance
    val realmManager = RealmManager(this)

    /**
     * Creates an error handler for coroutines to prevent crashes from uncaught exceptions
     */
    private fun createCoroutineErrorHandler() = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e("BerryHarvestApp", "Uncaught exception in coroutine", throwable)
            // Here you could integrate with a crash reporting service
        }
    }

    override fun onCreate() {
        super.onCreate()

        try {
            initializeApplication()
        } catch (e: Exception) {
            Log.e("BerryHarvestApp", "Error during application initialization", e)
            // Don't crash the app, but log the error
        }
    }

    private fun initializeApplication() {
        if (isInitialized.getAndSet(true)) {
            Log.w("BerryHarvestApp", "Application already initialized")
            return
        }

        Log.d("BerryHarvestApp", "Initializing BerryHarvest Application")

        // Initialize MongoDB Realm App
        val appID = "application-1-rgotpim"
        app = App.create(AppConfiguration.Builder(appID).build())

        // Initialize sync manager after repositories
        syncManager = SyncManager(this, ProcessLifecycleOwner.get().lifecycleScope)

        // Start sync monitoring
        applicationScope.launch {
            try {
                // Add small delay to ensure all components are ready
                delay(1000)
                if (!isShuttingDown.get()) {
                    syncManager.startSyncMonitoring()
                }
            } catch (e: Exception) {
                Log.e("BerryHarvestApp", "Error starting sync monitoring", e)
            }
        }

        // Schedule periodic cleanup of unused Realm instances
        scheduleRealmInstanceCleanup()

        rowExpirationManager = RowExpirationManager(this)
        rowExpirationManager.startPeriodicExpiration()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                super.onStart(owner)
                // App came to foreground, check for expired rows
                rowExpirationManager.checkNow()
            }

            override fun onStop(owner: LifecycleOwner) {
                super.onStop(owner)
                // App went to background, continue background checks
            }
        })

        Log.d("BerryHarvestApp", "Application initialization completed")
    }

    /**
     * Gets a Realm instance.
     * Uses the RealmManager to get a Realm instance with proper lifecycle management.
     *
     * @param contextKey An optional key to identify the context of the Realm use
     * @return A valid Realm instance
     * @throws IllegalStateException if application is shutting down
     */
    suspend fun getRealmInstance(contextKey: String = "default"): Realm {
        if (isShuttingDown.get()) {
            throw IllegalStateException("Application is shutting down")
        }

        return try {
            realmManager.getInstance(contextKey)
        } catch (e: Exception) {
            Log.e("BerryHarvestApp", "Error getting Realm instance", e)
            throw e
        }
    }

    /**
     * Sets up initial subscriptions for the synced Realm.
     * This is extracted to a method to make it reusable.
     */
    fun MutableSubscriptionSet.setupInitialSubscriptions(realm: Realm) {
        add(realm.query<Worker>(), "workers")
        add(realm.query<Gather>(), "gathers")
        add(realm.query<Assignment>(), "assignments")
        add(realm.query<Settings>(), "settings")
        add(realm.query<PaymentRecord>(), "payment_records")
        add(realm.query<PaymentBalance>(), "payment_balances")
    }

    /**
     * Ensures all required subscriptions are set up for the synced Realm.
     */
    suspend fun ensureSubscriptions() {
        if (isShuttingDown.get()) return

        try {
            val realm = getRealmInstance()

            // Check if this is a synced realm
            if (realm.configuration.toString().contains("Sync")) {
                Log.d("Realm", "Checking subscriptions on synced Realm")

                realm.subscriptions.waitForSynchronization()

                // Check existing subscriptions
                val subscriptionNames = mutableSetOf<String>()
                realm.subscriptions.forEach { subscription ->
                    subscriptionNames.add(subscription.name ?: "unnamed")
                }

                // Log found subscriptions
                Log.d("Realm", "Found subscriptions: ${subscriptionNames.joinToString()}")

                // Update subscriptions if needed
                realm.subscriptions.update {
                    if (!subscriptionNames.contains("workers")) {
                        add(realm.query<Worker>(), "workers")
                        Log.d("Realm", "Added missing 'workers' subscription")
                    }

                    if (!subscriptionNames.contains("gathers")) {
                        add(realm.query<Gather>(), "gathers")
                        Log.d("Realm", "Added missing 'gathers' subscription")
                    }

                    if (!subscriptionNames.contains("assignments")) {
                        add(realm.query<Assignment>(), "assignments")
                        Log.d("Realm", "Added missing 'assignments' subscription")
                    }

                    if (!subscriptionNames.contains("settings")) {
                        add(realm.query<Settings>(), "settings")
                        Log.d("Realm", "Added missing 'settings' subscription")
                    }

                    if (!subscriptionNames.contains("payment_records")) {
                        add(realm.query<PaymentRecord>(), "payment_records")
                        Log.d("Realm", "Added missing 'payment_records' subscription")
                    }

                    if (!subscriptionNames.contains("payment_balances")) {
                        add(realm.query<PaymentBalance>(), "payment_balances")
                        Log.d("Realm", "Added missing 'payment_balances' subscription")
                    }
                }
            } else {
                Log.d("Realm", "Not a synced Realm, no need to check subscriptions")
            }
        } catch (e: Exception) {
            Log.e("Realm", "Error ensuring subscriptions", e)
        }
    }

    /**
     * Safely executes a write transaction on a Realm instance.
     *
     * This method handles transaction state errors by recreating the Realm instance if needed.
     *
     * @param block The transaction code to execute
     * @return The result of the transaction
     */
    suspend fun <T> safeWriteTransaction(block: MutableRealm.() -> T): T {
        if (isShuttingDown.get()) {
            throw IllegalStateException("Application is shutting down")
        }

        return withContext(Dispatchers.IO) {
            val realm = getRealmInstance()
            try {
                Log.d("Realm", "Starting safe write transaction")
                val result = realm.write(block)
                Log.d("Realm", "Write transaction completed successfully")
                result
            } catch (e: IllegalStateException) {
                if (e.message?.contains("wrong transaction state", ignoreCase = true) == true) {
                    Log.w("Realm", "Transaction state error - recreating Realm instance")

                    // Attempt to close the problematic instance
                    realmManager.closeInstance("default")

                    // Create fresh instance and retry
                    val freshRealm = getRealmInstance()
                    Log.d("Realm", "Retrying transaction with fresh Realm instance")
                    freshRealm.write(block)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * Safely executes a write transaction with retry logic.
     *
     * This method will attempt to retry the transaction if it fails due to
     * transient errors like network issues or transaction conflicts.
     *
     * @param maxRetries Maximum number of retries
     * @param block The transaction code to execute
     * @return The result of the transaction
     */
    suspend fun <T> safeWriteTransactionWithRetry(
        maxRetries: Int = 3,
        block: MutableRealm.() -> T
    ): T {
        if (isShuttingDown.get()) {
            throw IllegalStateException("Application is shutting down")
        }

        return withContext(Dispatchers.IO) {
            var attempts = 0
            var lastException: Exception? = null

            while (attempts < maxRetries && !isShuttingDown.get()) {
                try {
                    return@withContext safeWriteTransaction(block)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e // Don't catch cancellation exceptions

                    lastException = e
                    Log.w("Realm", "Transaction failed (attempt ${attempts + 1}/$maxRetries): ${e.message}")
                    attempts++

                    if (attempts < maxRetries && !isShuttingDown.get()) {
                        // Exponential backoff
                        val delayMs = (100L * (1 shl attempts))
                        delay(delayMs)
                    }
                }
            }

            throw lastException ?: IllegalStateException("Transaction failed after $maxRetries attempts")
        }
    }

    /**
     * Executes a database operation on the IO dispatcher with proper error handling.
     */
    suspend fun <T> withDatabaseContext(block: suspend (Realm) -> T): T {
        if (isShuttingDown.get()) {
            throw IllegalStateException("Application is shutting down")
        }

        return withContext(Dispatchers.IO) {
            val realm = getRealmInstance()
            try {
                block(realm)
            } catch (e: Exception) {
                Log.e("Realm", "Database operation failed: ${e.message}", e)
                throw e
            }
        }
    }

    private fun scheduleRealmInstanceCleanup() {
        applicationScope.launch {
            while (!isShuttingDown.get()) {
                try {
                    // Perform cleanup every 5 minutes
                    delay(5 * 60 * 1000L)
                    if (!isShuttingDown.get()) {
                        realmManager.performCleanup()
                    }
                } catch (e: CancellationException) {
                    Log.d("BerryHarvestApp", "Cleanup task cancelled")
                    break
                } catch (e: Exception) {
                    Log.e("Realm", "Error during Realm instance cleanup", e)
                }
            }
        }
    }

    // Close Realm and repositories when application terminates
    override fun onTerminate() {
        shutdown()
        super.onTerminate()
        if (::rowExpirationManager.isInitialized) {
            rowExpirationManager.stopPeriodicExpiration()
        }
    }

    // Clean up resources when memory is low
    override fun onLowMemory() {
        super.onLowMemory()

        if (!isShuttingDown.get()) {
            applicationScope.launch {
                try {
                    realmManager.performCleanup(aggressive = true)
                } catch (e: Exception) {
                    Log.e("BerryHarvestApp", "Error during low memory cleanup", e)
                }
            }
        }
    }

    /**
     * Properly shutdown the application and clean up all resources
     */
    fun shutdown() {
        if (isShuttingDown.getAndSet(true)) {
            Log.d("BerryHarvestApp", "Shutdown already in progress")
            return
        }

        Log.d("BerryHarvestApp", "Starting application shutdown")

        try {
            // Stop sync manager first
            if (::syncManager.isInitialized) {
                syncManager.shutdown()
            }

            // Close repositories
            repositoryProvider.closeAll()

            // Cancel all background jobs
            applicationScope.cancel()

            // Close all Realm instances
            realmManager.closeAllInstances()

            Log.d("BerryHarvestApp", "Application shutdown completed")
        } catch (e: Exception) {
            Log.e("BerryHarvestApp", "Error during shutdown", e)
        }
    }

    /**
     * Check if the application is shutting down
     */
    fun isShuttingDown(): Boolean = isShuttingDown.get()

    /**
     * Check if the application is properly initialized
     */
    fun isInitialized(): Boolean = isInitialized.get()
}