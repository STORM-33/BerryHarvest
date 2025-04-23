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
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.Credentials
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.sync.SyncConfiguration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Manages Realm instances in a coroutine-friendly way.
 * Replaces the thread-local approach with a more reliable solution
 * for use with Kotlin coroutines.
 */
class RealmManager(private val application: BerryHarvestApplication) {
    private val TAG = "RealmManager"

    // Map to store Realm instances by context key
    private val instances = ConcurrentHashMap<String, Realm>()

    // Mutex for thread-safe access to Realm instances
    private val mutex = Mutex()

    // Default timeout for operations
    private val DEFAULT_TIMEOUT = 15.seconds

    // Increased timeout for initial sync
    private val INITIAL_SYNC_TIMEOUT = 10.seconds

    /**
     * Gets a Realm instance for the specified context key.
     * If an instance for this key already exists and is valid, returns it.
     * Otherwise, creates and returns a new instance.
     *
     * @param contextKey A key to identify the context in which the Realm is used
     * @return A valid Realm instance
     */
    suspend fun getInstance(contextKey: String = "default"): Realm {
        // Check if we already have a valid instance for this key
        instances[contextKey]?.let { realm ->
            if (!realm.isClosed()) {
                return realm
            }
            // Instance exists but is closed, remove it
            instances.remove(contextKey)
        }

        // Create a new instance with thread safety
        return mutex.withLock {
            // Double-check after acquiring the lock
            instances[contextKey]?.let { realm ->
                if (!realm.isClosed()) {
                    return@withLock realm
                }
                instances.remove(contextKey)
            }

            Log.d(TAG, "Creating new Realm instance for context: $contextKey")

            // Create a new instance with timeout
            try {
                val newRealm = withTimeout(DEFAULT_TIMEOUT) {
                    createRealmInstance()
                }

                // Store the new instance
                instances[contextKey] = newRealm
                newRealm
            } catch (e: Exception) {
                Log.e(TAG, "Error creating Realm instance", e)
                val fallbackRealm = createFallbackRealmInstance()
                instances[contextKey] = fallbackRealm
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
        if (application.forceOfflineMode || application.networkStatusManager.isNetworkAvailable() == false) {
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
            val config = RealmConfiguration.Builder(application.realmModels)
                .schemaVersion(1)
                .inMemory()
                .build()

            return Realm.open(config).also {
                Log.d(TAG, "In-memory Realm opened as final fallback")
            }
        }
    }

    /**
     * Closes a Realm instance for a specific context key.
     */
    fun closeInstance(contextKey: String) {
        val realm = instances.remove(contextKey)
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
        instances.keys.toList().forEach { key ->
            closeInstance(key)
        }
        instances.clear()
        Log.d(TAG, "Closed all Realm instances")
    }

    /**
     * Performs cleanup of potentially leaked Realm instances.
     */
    suspend fun performCleanup(aggressive: Boolean = false) {
        mutex.withLock {
            var closedCount = 0

            // Copy keys to avoid concurrent modification
            val keys = instances.keys.toList()

            for (key in keys) {
                val realm = instances[key]
                if (realm == null || realm.isClosed() || aggressive) {
                    instances.remove(key)
                    realm?.let {
                        try {
                            if (!it.isClosed()) it.close() else TODO()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing Realm during cleanup", e)
                        }
                    }
                    closedCount++
                }
            }

            if (closedCount > 0) {
                Log.d(TAG, "Cleaned up $closedCount Realm instances")
            }
        }
    }
}