package com.example.berryharvest

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.berryharvest.data.repository.RepositoryProvider
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.Settings
import com.example.berryharvest.data.sync.SyncManager
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.AppConfiguration
import io.realm.kotlin.mongodb.Credentials
import io.realm.kotlin.mongodb.sync.SyncConfiguration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration.Companion.seconds

class BerryHarvestApplication : Application() {
    var app: App? = null
        private set

    var forceOfflineMode: Boolean = false

    // For caching the Realm instance
    private var realmInstance: Realm? = null

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

    override fun onCreate() {
        super.onCreate()
        val appID = "application-1-rgotpim"
        app = App.create(AppConfiguration.Builder(appID).build())

        // Initialize sync manager after repositories
        syncManager = SyncManager(this, ProcessLifecycleOwner.get().lifecycleScope)
        syncManager.startSyncMonitoring()
    }

    suspend fun getRealmInstance(): Realm {
        // If we already have an instance that is open, return it
        realmInstance?.let {
            if (!it.isClosed()) {
                Log.d("Realm", "Returning existing Realm instance")
                return it
            }
        }

        Log.d("Realm", "Creating new Realm instance")

        try {
            // Use app's files directory which is guaranteed to be accessible
            val realmDirectory = File(filesDir, "realm_data").apply {
                if (!exists()) {
                    if (!mkdirs()) {
                        Log.w("Realm", "Failed to create directory: $path")
                    }
                }
            }

            // Always create a local configuration first
            val localConfig = RealmConfiguration.Builder(
                setOf(Worker::class, Gather::class, Assignment::class, Settings::class)
            )
                .schemaVersion(1)
                .directory(realmDirectory.path) // Use the app's files directory
                .name("local.realm")
                .build()

            // If network is not available or offline mode is forced, use local-only config right away
            if (forceOfflineMode || networkStatusManager.isNetworkAvailable() == false) {
                Log.d("Realm", "Network unavailable, creating local Realm")
                return Realm.open(localConfig).also {
                    Log.d("Realm", "Local Realm opened successfully")
                    realmInstance = it
                }
            }

            // Otherwise try to use sync config
            return withTimeout(15000) { // 15 second timeout
                try {
                    val app = app ?: throw IllegalStateException("App is not initialized")
                    Log.d("Realm", "App reference obtained: ${app.configuration.appId}")

                    // Try to login with anonymous credentials
                    Log.d("Realm", "Attempting anonymous login")
                    val user = try {
                        app.login(Credentials.anonymous())
                    } catch (e: Exception) {
                        Log.e("Realm", "Login failed: ${e.message}")
                        // If login failed, fall back to local database
                        return@withTimeout Realm.open(localConfig).also {
                            Log.d("Realm", "Fallback to local Realm after login failure")
                            realmInstance = it
                        }
                    }

                    // Configure sync
                    val syncConfig = SyncConfiguration.Builder(
                        user,
                        setOf(Worker::class, Gather::class, Assignment::class, Settings::class)
                    )
                        .schemaVersion(1)
                        .initialSubscriptions { realm ->
                            add(realm.query<Worker>())
                            add(realm.query<Gather>())
                            add(realm.query<Assignment>())
                            add(realm.query<Settings>())
                        }
                        .waitForInitialRemoteData(1.seconds) // Short timeout
                        .build()

                    Log.d("Realm", "Opening synced Realm")

                    try {
                        Realm.open(syncConfig).also {
                            Log.d("Realm", "Synced Realm opened successfully")
                            realmInstance = it
                        }
                    } catch (e: Exception) {
                        Log.e("Realm", "Failed to open synced Realm: ${e.message}")

                        // Fall back to local if sync fails
                        Realm.open(localConfig).also {
                            Log.d("Realm", "Local fallback Realm opened successfully")
                            realmInstance = it
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Realm", "Error in sync attempt: ${e.message}")

                    // Fall back to local in any case of error
                    Realm.open(localConfig).also {
                        Log.d("Realm", "Local fallback Realm opened after error")
                        realmInstance = it
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Realm", "Error initializing Realm: ${e.message}", e)

            // Create a fallback in-memory instance
            try {
                val config = RealmConfiguration.Builder(
                    setOf(Worker::class, Gather::class, Assignment::class, Settings::class)
                )
                    .schemaVersion(1)
                    .inMemory() // Use in-memory as last resort
                    .name("fallback")
                    .build()

                return Realm.open(config).also {
                    Log.d("Realm", "In-memory Realm opened as final fallback")
                    realmInstance = it
                }
            } catch (innerE: Exception) {
                Log.e("Realm", "Even in-memory fallback failed", innerE)
                throw innerE
            }
        }
    }

    suspend fun <T> safeWriteTransaction(block: MutableRealm.() -> T): T {
        val realm = runBlocking { getRealmInstance() }
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
                    realm.close()
                } catch (closeEx: Exception) {
                    Log.e("Realm", "Error closing problematic Realm", closeEx)
                }

                // Clear the cached instance
                realmInstance = null

                // Create fresh instance and retry
                val freshRealm = runBlocking { getRealmInstance() }
                Log.d("Realm", "Retrying transaction with fresh Realm instance")
                freshRealm.write(block)
            } else {
                throw e
            }
        }
    }

    // Close Realm and repositories when application terminates
    override fun onTerminate() {
        repositoryProvider.closeAll()
        syncManager.stopPeriodicSyncJob()
        realmInstance?.close()
        realmInstance = null
        super.onTerminate()
    }

    // Clean up resources when memory is low
    override fun onLowMemory() {
        super.onLowMemory()

        // If we're low on memory, we can close the realm instance
        // to free up resources, it'll be recreated when needed
        if (realmInstance != null && !realmInstance!!.isClosed()) {
            Log.d("Realm", "Closing Realm due to low memory")
            realmInstance?.close()
            realmInstance = null
        }
    }
}