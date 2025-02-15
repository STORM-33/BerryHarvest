package com.example.berryharvest

import android.app.Application
import com.example.berryharvest.ui.add_worker.Worker
import com.example.berryharvest.ui.assign_rows.Assignment
import com.example.berryharvest.ui.gather.Gather
import io.realm.kotlin.Realm
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.AppConfiguration
import io.realm.kotlin.mongodb.Credentials
import io.realm.kotlin.mongodb.sync.SyncConfiguration
import kotlinx.coroutines.runBlocking

class MyApplication : Application() {
    lateinit var app: App
        private set

    override fun onCreate() {
        super.onCreate()
        val appID = "application-1-rgotpim"
        app = App.create(AppConfiguration.Builder(appID).build())
    }

    fun getRealmInstance(): Realm {
        val user = runBlocking { app.login(Credentials.anonymous()) }
        val config = SyncConfiguration.Builder(
            user,
            setOf(Worker::class, Gather::class, Assignment::class) // Include Assignment class
        )
            .initialSubscriptions { realm ->
                add(realm.query(Worker::class))
                add(realm.query(Gather::class))
                add(realm.query(Assignment::class)) // Add Assignment subscription
            }
            .build()
        return Realm.open(config)
    }
}