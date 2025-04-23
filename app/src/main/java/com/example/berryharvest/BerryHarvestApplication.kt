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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + createCoroutineErrorHandler())

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

    // Realm instance management
    private val realmInstanceManager = RealmInstanceManager()

    /**
     * Creates an error handler for coroutines to prevent crashes from uncaught exceptions
     */
    private fun createCoroutineErrorHandler() = CoroutineExceptionHandler { _, throwable ->
        Log.e("BerryHarvestApp", "Uncaught exception in coroutine", throwable)
        // Here you could integrate with a crash reporting service
    }

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
        return realmInstanceManager.getRealmInstance()
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
                    realmInstanceManager.closeCurrentThreadInstance()

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
    ): T = withContext(Dispatchers.IO) {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            try {
                return@withContext safeWriteTransaction(block)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // Don't catch cancellation exceptions

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
     * Executes a database operation on the IO dispatcher with proper error handling.
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
        applicationScope.launch {
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

    private fun scheduleRealmInstanceCleanup() {
        applicationScope.launch {
            while (!isShuttingDown.get()) {
                try {
                    // Perform cleanup every 5 minutes
                    delay(5 * 60 * 1000L)
                    realmInstanceManager.performCleanup()
                } catch (e: Exception) {
                    Log.e("Realm", "Error during Realm instance cleanup", e)
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
        realmInstanceManager.closeAllInstances()

        super.onTerminate()
    }

    // Clean up resources when memory is low
    override fun onLowMemory() {
        super.onLowMemory()

        applicationScope.launch {
            realmInstanceManager.performCleanup(aggressive = true)
        }
    }

    /**
     * Inner class for managing Realm instances with proper thread safety and resource cleanup
     */
    private inner class RealmInstanceManager {
        private val realmMutex = Mutex()
        private val threadLocalRealm = ThreadLocal<Realm?>()
        private val activeThreadIds = mutableSetOf<Long>()

        /**
         * Gets or creates a Realm instance for the current thread
         */
        suspend fun getRealmInstance(): Realm {
            if (isShuttingDown.get()) {
                throw IllegalStateException("Application is shutting down")
            }

            // First check thread-local cache
            threadLocalRealm.get()?.let { realm ->
                if (!realm.isClosed()) {
                    return realm
                }
                // Clean up closed instance
                threadLocalRealm.set(null)
            }

            // Create new instance with mutex to prevent race conditions
            return realmMutex.withLock {
                try {
                    // Double-check if another thread created an instance
                    threadLocalRealm.get()?.let { realm ->
                        if (!realm.isClosed()) {
                            return@withLock realm
                        }
                        threadLocalRealm.set(null)
                    }

                    Log.d("Realm", "Creating new Realm instance for thread ${Thread.currentThread().id}")

                    // Create new instance
                    val newRealm = withTimeout(15.seconds) {
                        createRealmInstance()
                    }

                    // Save to thread-local storage
                    threadLocalRealm.set(newRealm)

                    // Track the thread ID for cleanup
                    activeThreadIds.add(Thread.currentThread().id)

                    newRealm
                } catch (e: Exception) {
                    Log.e("Realm", "Error creating Realm instance", e)
                    val fallbackRealm = createFallbackRealmInstance()
                    threadLocalRealm.set(fallbackRealm)
                    fallbackRealm
                }
            }
        }

        /**
         * Creates a new Realm instance based on the current network status.
         */
        private suspend fun createRealmInstance(): Realm {
            Log.d("Realm", "Creating new Realm instance")
            val app = this@BerryHarvestApplication.app ?: throw IllegalStateException("App is not initialized")

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
         * Closes the Realm instance for the current thread
         */
        fun closeCurrentThreadInstance() {
            val threadId = Thread.currentThread().id
            val realm = threadLocalRealm.get()

            try {
                if (realm != null && !realm.isClosed()) {
                    realm.close()
                    Log.d("Realm", "Closed Realm instance for thread $threadId")
                }
            } catch (e: Exception) {
                Log.e("Realm", "Error closing Realm instance for thread $threadId", e)
            } finally {
                threadLocalRealm.set(null)
                activeThreadIds.remove(threadId)
            }
        }

        /**
         * Performs cleanup of unused or leaked Realm instances
         */
        suspend fun performCleanup(aggressive: Boolean = false) {
            realmMutex.withLock {
                var closedCount = 0
                val currentThreadId = Thread.currentThread().id

                // In aggressive mode, close all instances except the current thread's
                if (aggressive) {
                    for (threadId in activeThreadIds.toList()) {
                        if (threadId != currentThreadId) {
                            try {
                                // We can't access other threads' thread-local storage directly,
                                // so we just remove them from our tracking set
                                activeThreadIds.remove(threadId)
                                closedCount++
                            } catch (e: Exception) {
                                Log.e("Realm", "Error in aggressive cleanup for thread $threadId", e)
                            }
                        }
                    }
                } else {
                    // Regular cleanup just checks for dead threads
                    val deadThreads = activeThreadIds.filter {
                        try {
                            // This is a best-effort check - there's no reliable way
                            // to check if a thread is alive from another thread
                            // We'll just clean out our tracking set periodically
                            Thread.getAllStackTraces().keys.none { t -> t.id == it }
                        } catch (e: Exception) {
                            // If we get an error, assume the thread is dead
                            true
                        }
                    }

                    // Remove dead threads from our tracking
                    activeThreadIds.removeAll(deadThreads)
                    closedCount = deadThreads.size
                }

                if (closedCount > 0) {
                    Log.d("Realm", "Cleaned up tracking for $closedCount thread(s)")
                }
            }
        }

        /**
         * Closes all Realm instances
         */
        fun closeAllInstances() {
            // Close current thread's instance
            closeCurrentThreadInstance()

            // Clear tracking of all threads
            activeThreadIds.clear()
            Log.d("Realm", "Cleared all Realm instance tracking")
        }
    }
}