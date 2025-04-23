package com.example.berryharvest

import android.app.Application
import android.util.Log
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
import com.example.berryharvest.data.sync.SyncStatus
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.AppConfiguration
import io.realm.kotlin.mongodb.Credentials
import io.realm.kotlin.mongodb.subscriptions
import io.realm.kotlin.mongodb.sync.SyncConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Main application class responsible for initializing global dependencies
 * and managing Realm database lifecycle.
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

    // Application-scoped coroutine scope for background operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Thread-safe map to store Realm instances per thread
    private val realmInstances = ConcurrentHashMap<Long, Realm>()

    // Mutex for synchronized access to Realm instances
    private val realmMutex = Mutex()

    // Flag to track if application is shutting down
    private val isShuttingDown = AtomicBoolean(false)



    // Models included in the Realm schema
    private val realmModels = setOf(
        Worker::class,
        Gather::class,
        Assignment::class,
        Settings::class,
        PaymentRecord::class,
        PaymentBalance::class
    )

    override fun onCreate() {
        super.onCreate()
        val appID = "application-1-rgotpim"
        app = App.create(AppConfiguration.Builder(appID).build())

        // Initialize sync manager after repositories
        syncManager = SyncManager(this, ProcessLifecycleOwner.get().lifecycleScope)
        syncManager.startSyncMonitoring()

        // Monitor sync status
        monitorSyncStatus()

        // Schedule periodic cleanup of unused Realm instances
        scheduleRealmInstanceCleanup()
    }

    /**
     * Gets a Realm instance for the current thread.
     *
     * If a cached instance exists and is valid, it will be returned.
     * Otherwise, a new instance will be created.
     *
     * @return A valid Realm instance
     * @throws IllegalStateException if Realm initialization fails
     */
    internal suspend fun getRealmInstance(): Realm {
        if (isShuttingDown.get()) {
            throw IllegalStateException("Application is shutting down")
        }

        val threadId = Thread.currentThread().id

        // Try to get cached instance first
        realmInstances[threadId]?.let { realm ->
            if (!realm.isClosed()) {
                return realm
            }
            // Remove closed instance from cache
            realmInstances.remove(threadId)
        }

        // Create a new instance with mutex to prevent race conditions
        return realmMutex.withLock {
            try {
                // Double-check if another thread created an instance while we were waiting
                realmInstances[threadId]?.let { realm ->
                    if (!realm.isClosed()) {
                        return@withLock realm
                    }
                    realmInstances.remove(threadId)
                }

                Log.d("Realm", "Creating new Realm instance for thread $threadId")

                // Create new instance with timeout
                withTimeout(15.seconds) {
                    createRealmInstance().also { newRealm ->
                        realmInstances[threadId] = newRealm
                        Log.d("Realm", "Cached new Realm instance for thread $threadId")
                    }
                }
            } catch (e: Exception) {
                Log.e("Realm", "Error getting Realm instance: ${e.message}", e)

                // Emergency fallback - try to create a local-only instance
                createFallbackRealmInstance().also { fallbackRealm ->
                    realmInstances[threadId] = fallbackRealm
                    Log.d("Realm", "Using fallback Realm instance for thread $threadId")
                }
            }
        }
    }

    /**
     * Ensures all required subscriptions are set up for the synced Realm.
     */
    suspend fun ensureSubscriptions() {
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
        val realm = getRealmInstance()
        return try {
            Log.d("Realm", "Starting safe write transaction")
            val result = realm.write(block)
            Log.d("Realm", "Write transaction completed successfully")
            result
        } catch (e: IllegalStateException) {
            if (e.message?.contains("wrong transaction state", ignoreCase = true) == true) {
                Log.w("Realm", "Transaction state error - recreating Realm instance")

                // Close potentially problematic instance
                try {
                    closeRealmInstance(Thread.currentThread().id)
                } catch (closeEx: Exception) {
                    Log.e("Realm", "Error closing problematic Realm", closeEx)
                }

                // Create fresh instance and retry
                val freshRealm = getRealmInstance()
                Log.d("Realm", "Retrying transaction with fresh Realm instance")
                freshRealm.write(block)
            } else {
                throw e
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
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            try {
                return safeWriteTransaction(block)
            } catch (e: Exception) {
                lastException = e
                Log.w("Realm", "Transaction failed (attempt ${attempts + 1}/$maxRetries): ${e.message}")
                attempts++

                if (attempts < maxRetries) {
                    // Exponential backoff
                    val delayMs = (100L * (1 shl attempts))
                    delay(delayMs)
                }
            }
        }

        throw lastException ?: IllegalStateException("Transaction failed after $maxRetries attempts")
    }

    /**
     * Creates a new Realm instance based on the current network status.
     *
     * @return A new Realm instance
     * @throws IllegalStateException if initialization fails
     */
    private suspend fun createRealmInstance(): Realm {
        Log.d("Realm", "Creating new Realm instance")
        val app = this.app ?: throw IllegalStateException("App is not initialized")

        // If network is not available or in forced offline mode, create local-only Realm
        if (forceOfflineMode || networkStatusManager.isNetworkAvailable() == false) {
            Log.d("Realm", "Network unavailable, creating local Realm")
            val config = RealmConfiguration.Builder(realmModels)
                .schemaVersion(1)
                .directory("local_realm")
                .build()

            return Realm.open(config).also {
                Log.d("Realm", "Local Realm opened successfully")
            }
        }

        // Try to login with anonymous credentials
        Log.d("Realm", "Attempting anonymous login")
        val user = try {
            app.login(Credentials.anonymous())
        } catch (e: Exception) {
            Log.e("Realm", "Login failed: ${e.message}")
            throw e
        }
        Log.d("Realm", "Login successful, configuring sync")

        // Configure sync with reduced initial data download time
        val config = SyncConfiguration.Builder(
            user,
            realmModels
        )
            .schemaVersion(1)
            .initialSubscriptions { realm ->
                // Keep subscriptions minimal but with named subscriptions
                add(realm.query<Worker>(), "workers")
                add(realm.query<Gather>(), "gathers")
                add(realm.query<Assignment>(), "assignments")
                add(realm.query<Settings>(), "settings")
                add(realm.query<PaymentRecord>(), "payment_records")
                add(realm.query<PaymentBalance>(), "payment_balances")
            }
            .waitForInitialRemoteData(1.seconds) // Reduced from default to 1 second max
            .build()

        Log.d("Realm", "Opening synced Realm")

        try {
            return Realm.open(config).also {
                Log.d("Realm", "Synced Realm opened successfully")
            }
        } catch (e: Exception) {
            Log.e("Realm", "Failed to open synced Realm: ${e.message}")
            throw e
        }
    }

    /**
     * Creates a fallback local-only Realm instance for emergency use when
     * normal initialization fails.
     *
     * @return A basic local Realm instance
     * @throws IllegalStateException if fallback initialization fails
     */
    private fun createFallbackRealmInstance(): Realm {
        Log.d("Realm", "Creating fallback local Realm")
        try {
            val config = RealmConfiguration.Builder(realmModels)
                .schemaVersion(1)
                .directory("local_fallback")
                .build()

            return Realm.open(config).also {
                Log.d("Realm", "Fallback local Realm opened successfully")
            }
        } catch (e: Exception) {
            Log.e("Realm", "Failed to create fallback Realm", e)

            // Last resort - try in-memory Realm
            val config = RealmConfiguration.Builder(realmModels)
                .schemaVersion(1)
                .inMemory()
                .build()

            return Realm.open(config).also {
                Log.d("Realm", "In-memory Realm opened as final fallback")
            }
        }
    }

    /**
     * Closes a specific Realm instance by thread ID.
     *
     * @param threadId The ID of the thread whose Realm instance should be closed
     */
    private fun closeRealmInstance(threadId: Long) {
        realmInstances[threadId]?.let { realm ->
            try {
                if (!realm.isClosed()) {
                    realm.close()
                    Log.d("Realm", "Closed Realm instance for thread $threadId")
                } else {
                    Log.d("Realm", "Realm instance already closed for thread $threadId")
                }
            } catch (e: Exception) {
                Log.e("Realm", "Error closing Realm instance for thread $threadId", e)
            } finally {
                realmInstances.remove(threadId)
            }
        }
    }

    /**
     * Closes all cached Realm instances.
     */
    private fun closeAllRealmInstances() {
        realmInstances.keys.forEach { threadId ->
            closeRealmInstance(threadId)
        }
        realmInstances.clear()
        Log.d("Realm", "All Realm instances closed")
    }

    /**
     * Schedules periodic cleanup of unused Realm instances to prevent memory leaks.
     */
    private fun scheduleRealmInstanceCleanup() {
        applicationScope.launch {
            while (!isShuttingDown.get()) {
                try {
                    // Perform cleanup every 5 minutes
                    delay(5 * 60 * 1000L)

                    realmMutex.withLock {
                        val threadIds = realmInstances.keys.toList()
                        var closedCount = 0

                        for (threadId in threadIds) {
                            val realm = realmInstances[threadId]
                            if (realm == null || realm.isClosed()) {
                                realmInstances.remove(threadId)
                                closedCount++
                            }
                        }

                        if (closedCount > 0) {
                            Log.d("Realm", "Cleaned up $closedCount unused Realm instances")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Realm", "Error during Realm instance cleanup", e)
                }
            }
        }
    }

    /**
     * Executes a database operation on the IO dispatcher with proper error handling.
     *
     * @param block The database operation to execute
     * @return The result of the operation
     */
    suspend fun <T> withDatabaseContext(block: suspend (Realm) -> T): T {
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

    private fun monitorSyncStatus() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            syncManager.syncStatus.collect { status ->
                when (status) {
                    is SyncStatus.InProgress -> {
                        Log.d("SyncStatus", "Synchronization in progress")
                        // Notify UI components if needed
                    }
                    is SyncStatus.Completed -> {
                        Log.d("SyncStatus", "Synchronization completed successfully")
                        // Refresh UI components
                    }
                    is SyncStatus.Failed -> {
                        Log.e("SyncStatus", "Synchronization failed: ${status.reason}")
                        // Show error notification if needed
                    }
                    is SyncStatus.Idle -> {
                        // Nothing to do
                    }
                }
            }
        }
    }

    // Close Realm and repositories when application terminates
    override fun onTerminate() {
        isShuttingDown.set(true)
        repositoryProvider.closeAll()
        syncManager.stopPeriodicSyncJob()

        // Cancel all background jobs
        applicationScope.cancel()

        // Close all Realm instances
        closeAllRealmInstances()

        super.onTerminate()
    }

    // Clean up resources when memory is low
    override fun onLowMemory() {
        super.onLowMemory()

        applicationScope.launch {
            realmMutex.withLock {
                // Close instances that aren't actively being used
                val threadIds = realmInstances.keys.toList()
                for (threadId in threadIds) {
                    // Keep only the instance for the current thread
                    if (threadId != Thread.currentThread().id) {
                        closeRealmInstance(threadId)
                    }
                }
            }
        }
    }
}