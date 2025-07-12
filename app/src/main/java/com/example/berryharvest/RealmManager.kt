package com.example.berryharvest

import android.util.Log
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.PaymentBalance
import com.example.berryharvest.data.model.PaymentRecord
import com.example.berryharvest.data.model.Settings
import com.example.berryharvest.data.model.Worker
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.mongodb.Credentials
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.sync.SyncConfiguration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import com.example.berryharvest.data.model.Row
import com.example.berryharvest.util.MemoryMonitor

/**
 * Manages Realm instances in a coroutine-friendly way.
 * Replaces the thread-local approach with a more reliable solution
 * for use with Kotlin coroutines.
 */
class RealmManager(private val application: BerryHarvestApplication) {
    companion object {
        private val activeInstances = mutableMapOf<String, Realm>()
        private val instanceCreationTimes = mutableMapOf<String, Long>()
        private val instanceCreationStacks = mutableMapOf<String, String>()

        fun trackInstance(key: String, realm: Realm) {
            activeInstances[key] = realm
            instanceCreationTimes[key] = System.currentTimeMillis()

            // Capture stack trace to see where instance was created
            val stackTrace = Thread.currentThread().stackTrace.take(10).joinToString("\n") {
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
            instanceCreationStacks[key] = stackTrace

            Log.d("RealmTracker", "📊 Active Realm instances: ${activeInstances.size}")
            Log.d("RealmTracker", "📝 New instance: $key")
            MemoryMonitor.logMemoryUsage("RealmCreated-$key")
        }

        fun removeInstance(key: String) {
            activeInstances.remove(key)
            instanceCreationTimes.remove(key)
            instanceCreationStacks.remove(key)
            Log.d("RealmTracker", "📊 Active Realm instances: ${activeInstances.size} (removed $key)")
            MemoryMonitor.logMemoryUsage("RealmRemoved-$key")
        }

        fun getInstanceReport(): String {
            return buildString {
                appendLine("=== REALM INSTANCES REPORT ===")
                appendLine("Total Active: ${activeInstances.size}")
                appendLine("Expected Maximum: 1-2 (ideal)")
                if (activeInstances.size > 2) {
                    appendLine("⚠️ WARNING: Too many instances detected!")
                }
                appendLine()

                activeInstances.forEach { (key, realm) ->
                    val age = System.currentTimeMillis() - (instanceCreationTimes[key] ?: 0)
                    val isOpen = !realm.isClosed()
                    val status = if (isOpen) "OPEN" else "CLOSED"

                    appendLine("Instance: $key")
                    appendLine("  Status: $status")
                    appendLine("  Age: ${age/1000}s")
                    appendLine("  Created at:")
                    val stack = instanceCreationStacks[key] ?: "Unknown"
                    appendLine(stack.split("\n").take(3).joinToString("\n"))
                    appendLine()
                }

                appendLine("Memory Info: ${MemoryMonitor.getSimpleMemoryInfo()}")
            }
        }

        fun getActiveInstanceCount(): Int = activeInstances.size

        fun getInstanceKeys(): Set<String> = activeInstances.keys.toSet()

        // Force close all instances (for emergency cleanup)
        fun closeAllInstances() {
            Log.w("RealmTracker", "🚨 FORCE CLOSING ALL REALM INSTANCES")
            activeInstances.values.forEach { realm ->
                try {
                    if (!realm.isClosed()) {
                        realm.close()
                    }
                } catch (e: Exception) {
                    Log.e("RealmTracker", "Error closing realm", e)
                }
            }
            activeInstances.clear()
            instanceCreationTimes.clear()
            instanceCreationStacks.clear()
            MemoryMonitor.logMemoryUsage("AllRealmsClosed")
        }
    }

    private val TAG = "RealmManager"

    // Map to store Realm instances by context key
    private val instances = ConcurrentHashMap<String, Realm>()

    // Mutex for thread-safe access to Realm instances
    private val mutex = Mutex()

    // Default timeout for operations
    private val DEFAULT_TIMEOUT = 15.seconds

    // Increased timeout for initial sync
    private val INITIAL_SYNC_TIMEOUT = 10.seconds

    // Track if manager is closed to prevent operations after shutdown
    @Volatile
    private var isClosed = false

    /**
     * Gets a Realm instance for the specified context key.
     * If an instance for this key already exists and is valid, returns it.
     * Otherwise, creates and returns a new instance.
     *
     * @param contextKey A key to identify the context in which the Realm is used
     * @return A valid Realm instance
     * @throws IllegalStateException if the manager has been closed
     */
    suspend fun getInstance(contextKey: String = "default"): Realm {
        if (isClosed) {
            throw IllegalStateException("RealmManager has been closed")
        }

        // Check if we already have a valid instance for this key
        instances[contextKey]?.let { realm ->
            if (!realm.isClosed()) {
                Log.d(TAG, "Reusing existing Realm instance for context: $contextKey")
                return realm
            }
            // Instance exists but is closed, remove it safely
            Log.d(TAG, "Removing closed Realm instance for context: $contextKey")
            instances.remove(contextKey)
            removeInstance(contextKey) // ADD THIS LINE
        }

        // Create a new instance with thread safety
        return mutex.withLock {
            // Double-check after acquiring the lock
            instances[contextKey]?.let { realm ->
                if (!realm.isClosed()) {
                    return@withLock realm
                }
                instances.remove(contextKey)
                removeInstance(contextKey) // ADD THIS LINE
            }

            Log.d(TAG, "Creating new Realm instance for context: $contextKey")

            try {
                val newRealm = withTimeout(DEFAULT_TIMEOUT) {
                    createRealmInstance()
                }

                // Store the new instance
                instances[contextKey] = newRealm
                trackInstance(contextKey, newRealm) // ADD THIS LINE
                newRealm
            } catch (e: Exception) {
                Log.e(TAG, "Error creating Realm instance", e)
                val fallbackRealm = createFallbackRealmInstance()
                instances[contextKey] = fallbackRealm
                trackInstance(contextKey, fallbackRealm) // ADD THIS LINE
                fallbackRealm
            }
        }
    }

    /**
     * Creates a new Realm instance based on the current network status.
     */
    private suspend fun createRealmInstance(): Realm {
        Log.d(TAG, "Creating new Realm instance")
        val app = application.app ?: throw IllegalStateException("App is not initialized")

        // If network is not available or in forced offline mode, create local-only Realm
        if (application.forceOfflineMode || !application.networkStatusManager.isNetworkAvailable()) {
            Log.d(TAG, "Network unavailable, creating local Realm")
            val config = RealmConfiguration.Builder(application.realmModels)
                .schemaVersion(1)
                .directory("local_realm")
                .build()

            return Realm.open(config).also {
                Log.d(TAG, "Local Realm opened successfully")
            }
        }

        // Try to login with anonymous credentials
        Log.d(TAG, "Attempting anonymous login")
        val user = try {
            app.login(Credentials.anonymous())
        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}")
            throw e
        }
        Log.d(TAG, "Login successful, configuring sync")

        // Configure sync with increased initial data download time (10 seconds)
        val config = SyncConfiguration.Builder(user, application.realmModels)
            .schemaVersion(1)
            .initialSubscriptions { realm ->
                // 'this' is MutableSubscriptionSet
                add(realm.query<Worker>(), "workers")
                add(realm.query<Gather>(), "gathers")
                add(realm.query<Assignment>(), "assignments")
                add(realm.query<Settings>(), "settings")
                add(realm.query<PaymentRecord>(), "payment_records")
                add(realm.query<PaymentBalance>(), "payment_balances")
                add(realm.query<Row>(), "rows")
            }
            .waitForInitialRemoteData(INITIAL_SYNC_TIMEOUT)
            .build()

        Log.d(TAG, "Opening synced Realm")

        try {
            return Realm.open(config).also {
                Log.d(TAG, "Synced Realm opened successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open synced Realm: ${e.message}")
            throw e
        }
    }

    /**
     * Creates a fallback local-only Realm instance for emergency use when
     * normal initialization fails.
     */
    private fun createFallbackRealmInstance(): Realm {
        Log.d(TAG, "Creating fallback local Realm")
        try {
            val config = RealmConfiguration.Builder(application.realmModels)
                .schemaVersion(1)
                .directory("local_fallback")
                .build()

            return Realm.open(config).also {
                Log.d(TAG, "Fallback local Realm opened successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create fallback Realm", e)

            // Last resort - try in-memory Realm
            try {
                val config = RealmConfiguration.Builder(application.realmModels)
                    .schemaVersion(1)
                    .inMemory()
                    .build()

                return Realm.open(config).also {
                    Log.d(TAG, "In-memory Realm opened as final fallback")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to create in-memory Realm", e2)
                throw IllegalStateException("Unable to create any Realm instance", e2)
            }
        }
    }

    /**
     * Closes a Realm instance for a specific context key.
     */
    fun closeInstance(contextKey: String) {
        val realm = instances.remove(contextKey)
        removeInstance(contextKey) // ADD THIS LINE
        closeRealmSafely(realm, contextKey)
    }

    /**
     * Safely closes a Realm instance with proper error handling.
     */
    private fun closeRealmSafely(realm: Realm?, contextKey: String) {
        try {
            if (realm != null && !realm.isClosed()) {
                realm.close()
                Log.d(TAG, "Closed Realm instance for context: $contextKey")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Realm instance for context: $contextKey", e)
        }
    }

    /**
     * Closes all Realm instances.
     */
    fun closeAllInstances() {
        isClosed = true

        // Get all current instances to avoid concurrent modification
        val currentInstances = instances.toMap()
        instances.clear()

        // Clear tracking data
        Companion.closeAllInstances() // ADD THIS LINE

        // Close all instances
        currentInstances.forEach { (key, realm) ->
            closeRealmSafely(realm, key)
        }

        Log.d(TAG, "Closed all Realm instances")
    }

    /**
     * Performs cleanup of potentially leaked Realm instances.
     */
    suspend fun performCleanup(aggressive: Boolean = false) {
        if (isClosed) return

        mutex.withLock {
            var closedCount = 0

            // Copy keys to avoid concurrent modification
            val keys = instances.keys.toList()

            for (key in keys) {
                val realm = instances[key]
                try {
                    if (realm == null || realm.isClosed() || aggressive) {
                        instances.remove(key)
                        removeInstance(key) // ADD THIS LINE
                        if (realm != null && !realm.isClosed()) {
                            realm.close()
                        }
                        closedCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing Realm during cleanup for key: $key", e)
                    // Remove from map even if close failed to prevent accumulation
                    instances.remove(key)
                    removeInstance(key) // ADD THIS LINE
                    closedCount++
                }
            }

            if (closedCount > 0) {
                Log.d(TAG, "Cleaned up $closedCount Realm instances")
            }
        }
    }

    /**
     * Returns the number of active Realm instances.
     */
    fun getActiveInstanceCount(): Int = instances.size

    /**
     * Checks if the manager has been closed.
     */
    fun isClosed(): Boolean = isClosed
}