package com.example.berryharvest.ui.add_worker

import androidx.lifecycle.ViewModel
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AddWorkerViewModel : ViewModel() {

    private val realm: Realm

    init {
        val config = RealmConfiguration.Builder(schema = setOf(Worker::class))
            .name("myapp.realm")
            .schemaVersion(1)
            .build()
        realm = Realm.open(config)
    }

    val workers: Flow<List<Worker>> = realm.query<Worker>().asFlow().map { it.list }

    fun addWorker(fullName: String, phoneNumber: String) {
        realm.writeBlocking {
            copyToRealm(Worker().apply {
                this.fullName = fullName
                this.phoneNumber = phoneNumber
            })
        }
    }

    override fun onCleared() {
        super.onCleared()
        realm.close()
    }
}
