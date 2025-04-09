package com.example.berryharvest

import android.app.Application
import android.util.Log
import com.example.berryharvest.data.repository.RepositoryProvider
import com.example.berryharvest.ui.add_worker.Worker
import com.example.berryharvest.ui.assign_rows.Assignment
import com.example.berryharvest.ui.gather.Gather
import com.example.berryharvest.ui.gather.Settings
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.AppConfiguration
import io.realm.kotlin.mongodb.Credentials
import io.realm.kotlin.mongodb.sync.SyncConfiguration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MyApplication : Application() {
    var app: App? = null
        private set

    // For caching the Realm instance
    private var realmInstance: Realm? = null

    // Repository provider for accessing all repositories
    val repositoryProvider by lazy {
        RepositoryProvider(this)
    }

    override fun onCreate() {
        super.onCreate()
        val appID = "application-1-rgotpim"
        app = App.create(AppConfiguration.Builder(appID).build())
    }

    suspend fun getRealmInstance(): Realm {
        // If we already have an instance that is open, return it
        realmInstance?.let {
            if (!it.isClosed()) return it
        }

        try {
            return withTimeout(15000) { // 15 second timeout
                val app = app ?: throw IllegalStateException("App is not initialized")
                val user = app.login(Credentials.anonymous())
                val config = SyncConfiguration.Builder(
                    user,
                    setOf(Worker::class, Gather::class, Assignment::class, Settings::class)
                )
                    .schemaVersion(1)
                    .compactOnLaunch()
                    .initialSubscriptions { realm ->
                        add(realm.query<Worker>())
                        add(realm.query<Gather>())
                        add(realm.query<Assignment>())
                        add(realm.query<Settings>())
                    }
                    .build()

                Realm.open(config).also {
                    realmInstance = it
                }
            }
        } catch (e: Exception) {
            Log.e("Realm", "Error initializing Realm: ${e.message}")
            throw e
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