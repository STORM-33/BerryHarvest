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
import com.example.berryharvest.util.MemoryMonitor

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

        // Test memory monitoring right away
        MemoryMonitor.logMemoryUsage("AppStart")
        Log.d("RealmTest", "Starting app - checking Realm instances")

        try {
            initializeApplication()
        } catch (e: Exception) {
            Log.e("BerryHarvestApp", "Error during application initialization", e)
            // Don't crash the app, but log the error
        }

        MemoryMonitor.logMemoryUsage("AppInitialized")
        Log.d("RealmTest", RealmManager.getInstanceReport())
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
     * Comprehensive sync debugging tool
     * This will tell us exactly what's wrong with sync
     */
    suspend fun debugRealmSync(): String {
        val debugInfo = StringBuilder()

        try {
            debugInfo.appendLine("=== REALM SYNC DEBUG REPORT ===")
            debugInfo.appendLine("Timestamp: ${System.currentTimeMillis()}")
            debugInfo.appendLine()

            // 1. Check App and User Status
            debugInfo.appendLine("1. APP & USER STATUS:")
            debugInfo.appendLine("App initialized: ${app != null}")

            if (app != null) {
                debugInfo.appendLine("App ID: ${app!!.configuration.appId}")
                debugInfo.appendLine("Current user: ${app!!.currentUser != null}")

                if (app!!.currentUser != null) {
                    val user = app!!.currentUser!!
                    debugInfo.appendLine("User ID: ${user.id}")
                    debugInfo.appendLine("User state: ${user.state}")
                    debugInfo.appendLine("User identity: ${user.identity}")
                } else {
                    debugInfo.appendLine("❌ NO CURRENT USER")
                }
            } else {
                debugInfo.appendLine("❌ APP NOT INITIALIZED")
            }
            debugInfo.appendLine()

            // 2. Check Network Status
            debugInfo.appendLine("2. NETWORK STATUS:")
            debugInfo.appendLine("Network available: ${networkStatusManager.isNetworkAvailable()}")
            debugInfo.appendLine("Connection state: ${networkStatusManager.connectionState.value}")
            debugInfo.appendLine()

            // 3. Check Realm Instance
            debugInfo.appendLine("3. REALM INSTANCE:")
            try {
                val realm = getRealmInstance("debug")
                debugInfo.appendLine("Realm opened successfully: ${!realm.isClosed()}")
                debugInfo.appendLine("Realm path: ${realm.configuration}")

                // Check if it's a synced realm
                val configString = realm.configuration.toString()
                val isSyncedRealm = configString.contains("Sync", ignoreCase = true)
                debugInfo.appendLine("Is synced realm: $isSyncedRealm")

                if (isSyncedRealm) {
                    // 4. Check Subscriptions
                    debugInfo.appendLine()
                    debugInfo.appendLine("4. SYNC SUBSCRIPTIONS:")
                    debugInfo.appendLine("Total subscriptions: ${realm.subscriptions.size}")

                    if (realm.subscriptions.size > 0) {
                        debugInfo.appendLine("Subscription details:")
                        realm.subscriptions.forEach { subscription ->
                            debugInfo.appendLine("  - Name: ${subscription.name ?: "unnamed"}")
                            debugInfo.appendLine("    Query: ${subscription.queryDescription}")
                            debugInfo.appendLine("    Object type: ${subscription.objectType}")
                        }

                        // Try to check subscription state
                        try {
                            debugInfo.appendLine("Waiting for sync...")
                            realm.subscriptions.waitForSynchronization(kotlin.time.Duration.parse("5s"))
                            debugInfo.appendLine("✅ Subscriptions synchronized successfully")
                        } catch (e: Exception) {
                            debugInfo.appendLine("❌ Subscription sync failed: ${e.message}")
                        }
                    } else {
                        debugInfo.appendLine("❌ NO SUBSCRIPTIONS FOUND")
                    }

                    // 5. Check Data Counts
                    debugInfo.appendLine()
                    debugInfo.appendLine("5. LOCAL DATA COUNTS:")
                    try {
                        val workerCount = realm.query<Worker>().count().find()
                        val gatherCount = realm.query<Gather>().count().find()
                        val assignmentCount = realm.query<Assignment>().count().find()
                        val settingsCount = realm.query<Settings>().count().find()

                        debugInfo.appendLine("Workers: $workerCount")
                        debugInfo.appendLine("Gathers: $gatherCount")
                        debugInfo.appendLine("Assignments: $assignmentCount")
                        debugInfo.appendLine("Settings: $settingsCount")

                        // Check unsynced counts
                        val unsyncedWorkers = realm.query<Worker>("isSynced == false").count().find()
                        val unsyncedGathers = realm.query<Gather>("isSynced == false").count().find()

                        debugInfo.appendLine("Unsynced workers: $unsyncedWorkers")
                        debugInfo.appendLine("Unsynced gathers: $unsyncedGathers")

                    } catch (e: Exception) {
                        debugInfo.appendLine("❌ Error counting data: ${e.message}")
                    }
                } else {
                    debugInfo.appendLine("Using local-only Realm (no sync)")
                }

            } catch (e: Exception) {
                debugInfo.appendLine("❌ Error opening Realm: ${e.message}")
                debugInfo.appendLine("Exception: ${e.javaClass.simpleName}")
                debugInfo.appendLine("Stack trace: ${e.stackTrace.take(3).joinToString()}")
            }

            // 6. Check Sync Manager
            debugInfo.appendLine()
            debugInfo.appendLine("6. SYNC MANAGER:")
            if (::syncManager.isInitialized) {
                debugInfo.appendLine("Sync manager initialized: true")
                // Add sync manager status if available
            } else {
                debugInfo.appendLine("❌ Sync manager not initialized")
            }

            // 7. Check Repository Status
            debugInfo.appendLine()
            debugInfo.appendLine("7. REPOSITORY STATUS:")
            try {
                val workerRepo = repositoryProvider.workerRepository
                debugInfo.appendLine("Worker repository pending ops: ${workerRepo.getPendingOperationsCount()}")
                debugInfo.appendLine("Worker repository has pending: ${workerRepo.hasPendingOperations()}")
            } catch (e: Exception) {
                debugInfo.appendLine("❌ Error checking repositories: ${e.message}")
            }

            debugInfo.appendLine()
            debugInfo.appendLine("=== END DEBUG REPORT ===")

        } catch (e: Exception) {
            debugInfo.appendLine("❌ CRITICAL ERROR IN DEBUG: ${e.message}")
            debugInfo.appendLine("Exception: ${e.javaClass.simpleName}")
        }

        val report = debugInfo.toString()
        Log.d("RealmSyncDebug", report)
        return report
    }

    /**
     * Quick sync status check
     */
    suspend fun getQuickSyncStatus(): String {
        return try {
            val hasApp = app != null
            val hasUser = app?.currentUser != null
            val userStateOk = app?.currentUser?.state?.name == "LOGGED_IN"
            val networkOk = networkStatusManager.isNetworkAvailable()

            val realm = getRealmInstance("quick_check")
            val isSynced = realm.configuration.toString().contains("Sync", ignoreCase = true)
            val hasSubscriptions = if (isSynced) realm.subscriptions.size > 0 else false

            when {
                !hasApp -> "❌ App not initialized"
                !hasUser -> "❌ No user logged in"
                !userStateOk -> "❌ User not logged in properly"
                !networkOk -> "⚠️ Network unavailable"
                !isSynced -> "ℹ️ Using local Realm (offline mode)"
                !hasSubscriptions -> "❌ No sync subscriptions"
                else -> "✅ Sync appears healthy"
            }
        } catch (e: Exception) {
            "❌ Error: ${e.message}"
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