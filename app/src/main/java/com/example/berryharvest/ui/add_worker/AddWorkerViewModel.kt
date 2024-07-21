package com.example.berryharvest.ui.add_worker

import android.app.Application
import androidx.lifecycle.*
import io.realm.kotlin.*
import io.realm.kotlin.mongodb.*
import io.realm.kotlin.mongodb.sync.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log
import com.example.berryharvest.MyApplication
import io.realm.kotlin.ext.query

class AddWorkerViewModel(application: Application) : AndroidViewModel(application) {
    private val app: MyApplication
        get() = getApplication() as MyApplication

    private val _realmLiveData = MutableLiveData<Realm>()
    val realmLiveData: LiveData<Realm> = _realmLiveData

    private lateinit var realm: Realm

    init {
        initRealm()
    }

    private fun initRealm() {
        viewModelScope.launch {
            try {
                realm = app.getRealmInstance()
                createWorkerSubscription()
                _realmLiveData.postValue(realm)

                // Wait for subscriptions to be ready
                realm.subscriptions.waitForSynchronization()

                // Observe changes in the sync status
                observeWorkerChanges()
            } catch (e: Exception) {
                Log.e("Realm", "Error initializing Realm: ${e.message}")
            }
        }
    }

    val workers: Flow<List<Worker>> = realm.query<Worker>().asFlow().map { it.list }
        .catch { throwable -> Log.e("Realm", "Error fetching workers: $throwable") }

    fun addWorker(fullName: String, phoneNumber: String) {
        viewModelScope.launch {
            try {
                waitForSubscriptionSync()
                realm.write {
                    copyToRealm(Worker().apply {
                        this.fullName = fullName
                        this.phoneNumber = phoneNumber
                        this.isSynced = false // Mark as not synced
                    })
                }
            } catch (e: Exception) {
                Log.e("Realm", "Error adding worker: $e")
            }
        }
    }

    fun updateWorker(id: String, fullName: String, phoneNumber: String) {
        viewModelScope.launch {
            try {
                waitForSubscriptionSync()
                realm.write {
                    val worker = query<Worker>("_id == $0", id).first().find()
                    worker?.let {
                        it.fullName = fullName
                        it.phoneNumber = phoneNumber
                    }
                }
            } catch (e: Exception) {
                Log.e("Realm", "Error updating worker: $e")
            }
        }
    }

    fun deleteWorker(id: String) {
        viewModelScope.launch {
            try {
                waitForSubscriptionSync()
                realm.write {
                    val worker = query<Worker>("_id == $0", id).first().find()
                    worker?.let { delete(it) }
                }
            } catch (e: Exception) {
                Log.e("Realm", "Error deleting worker: $e")
            }
        }
    }

    private fun createWorkerSubscription() {
        viewModelScope.launch {
            realm.subscriptions.update {
                add(realm.query<Worker>())
            }
        }
    }

    private suspend fun waitForSubscriptionSync() {
        realm.subscriptions.waitForSynchronization()
    }

    private suspend fun observeWorkerChanges() {
        realm.query<Worker>().asFlow().collect { changes ->
            changes.list.forEach { worker ->
                if (!worker.isSynced) {
                    checkSyncStatus(worker)
                }
            }
        }
    }

    private fun checkSyncStatus(worker: Worker) {
        // Check the sync status and update the worker's isSynced property
        realm.writeBlocking {
            findLatest(worker)?.let { latestWorker ->
                latestWorker.isSynced = true // Update sync status
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (::realm.isInitialized) {
            realm.close()
        }
    }
}

