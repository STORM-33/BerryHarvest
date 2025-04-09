package com.example.berryharvest

import android.app.Application
import android.util.Log
import com.example.berryharvest.data.repository.RepositoryProvider
import com.example.berryharvest.ui.add_worker.Worker
import com.example.berryharvest.ui.assign_rows.Assignment
import com.example.berryharvest.ui.gather.Gather
import com.example.berryharvest.ui.gather.Settings
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.AppConfiguration
import io.realm.kotlin.mongodb.Credentials
import io.realm.kotlin.mongodb.sync.SyncConfiguration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class MyApplication : Application() {
    var app: App? = null
        private set

    var forceOfflineMode: Boolean = false

    // For caching the Realm instance
    private var realmInstance: Realm? = null

    // Repository provider for accessing all repositories
    val repositoryProvider by lazy {
        RepositoryProvider(this)
    }

    var networkManager: NetworkConnectivityManager? = null

    override fun onCreate() {
        super.onCreate()
        val appID = "application-1-rgotpim"
        app = App.create(AppConfiguration.Builder(appID).build())

        // Initialize network manager
        networkManager = NetworkConnectivityManager(this)
    }

    suspend fun getRealmInstance(): Realm {
        // If we already have an instance that is open, return it
        realmInstance?.let {
            if (!it.isClosed()) return it
        }

        Log.d("Realm", "Creating new Realm instance")

        try {
            return withTimeout(15000) { // 15 second timeout
                val app = app ?: throw IllegalStateException("App is not initialized")

                // If network is not available, create local-only Realm to prevent sync issues
                if (networkManager?.isNetworkAvailable() == false) {
                    Log.d("Realm", "Network unavailable, creating local Realm")
                    val config = RealmConfiguration.Builder(
                        setOf(Worker::class, Gather::class, Assignment::class, Settings::class)
                    )
                        .schemaVersion(1)
                        .build()

                    return@withTimeout Realm.open(config).also {
                        Log.d("Realm", "Local Realm opened successfully")
                        realmInstance = it
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

                // Configure sync and keep it simple
                val config = SyncConfiguration.Builder(
                    user,
                    setOf(Worker::class, Gather::class, Assignment::class, Settings::class)
                )
                    .schemaVersion(1)
                    .initialSubscriptions { realm ->
                        // Keep subscriptions minimal
                        add(realm.query<Worker>())
                        add(realm.query<Gather>())
                        add(realm.query<Assignment>())
                        add(realm.query<Settings>())
                    }
                    .waitForInitialRemoteData(1.seconds) // Wait only 1 second max
                    .build()

                Log.d("Realm", "Opening synced Realm")

                try {
                    Realm.open(config).also {
                        Log.d("Realm", "Synced Realm opened successfully")
                        realmInstance = it
                    }
                } catch (e: Exception) {
                    Log.e("Realm", "Failed to open synced Realm: ${e.message}")

                    // Fallback to local if sync fails
                    Log.d("Realm", "Falling back to local Realm")
                    val localConfig = RealmConfiguration.Builder(
                        setOf(Worker::class, Gather::class, Assignment::class, Settings::class)
                    )
                        .schemaVersion(1)
                        .build()

                    Realm.open(localConfig).also {
                        Log.d("Realm", "Local fallback Realm opened successfully")
                        realmInstance = it
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Realm", "Error initializing Realm with timeout: ${e.message}")

            // Last resort - try a simple local Realm
            try {
                Log.d("Realm", "Final fallback to basic local Realm")
                val config = RealmConfiguration.Builder(
                    setOf(Worker::class, Gather::class, Assignment::class, Settings::class)
                )
                    .schemaVersion(1)
                    .inMemory() // Use in-memory as last resort to avoid any disk issues
                    .build()

                return Realm.open(config).also {
                    Log.d("Realm", "In-memory Realm opened as final fallback")
                    realmInstance = it
                }
            } catch (innerE: Exception) {
                Log.e("Realm", "All Realm initialization attempts failed", innerE)
                throw innerE
            }
        }
    }

    // Close Realm and repositories when application terminates
    override fun onTerminate() {
        repositoryProvider.closeAll()
        realmInstance?.close()
        realmInstance = null
        super.onTerminate()
    }
}