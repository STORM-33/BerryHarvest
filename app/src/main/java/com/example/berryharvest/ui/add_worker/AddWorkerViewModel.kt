package com.example.berryharvest.ui.add_worker

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.MyApplication
import com.example.berryharvest.NetworkConnectivityManager
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.subscriptions
import io.realm.kotlin.mongodb.sync.ConnectionState
import io.realm.kotlin.mongodb.syncSession
import io.realm.kotlin.query.Sort
import io.realm.kotlin.query.max
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AddWorkerViewModel(application: Application) : AndroidViewModel(application) {

    private val app: MyApplication
        get() = getApplication() as MyApplication

    private val networkManager = NetworkConnectivityManager(application)

    private lateinit var realm: Realm

    private val _workers = MutableStateFlow<List<Worker>>(emptyList())
    val workers: StateFlow<List<Worker>> = _workers.asStateFlow()

    init {
        initRealm()
        observeNetworkConnectivity()
    }

    private fun initRealm() {
        viewModelScope.launch {
            try {
                realm = app.getRealmInstance()
                createWorkerSubscription()
                realm.subscriptions.waitForSynchronization()
                observeWorkers()
                observeWorkerChanges()
                Log.d("AddWorkerViewModel", "Realm initialized successfully")
            } catch (e: Exception) {
                Log.e("Realm", "Error initializing Realm: ${e.message}")
            }
        }
    }

    private fun observeWorkers() {
        viewModelScope.launch {
            realm.query<Worker>().asFlow()
                .map { it.list.toList() }
                .catch { throwable ->
                    Log.e("Realm", "Error fetching workers: $throwable")
                    emit(emptyList())
                }
                .collect { workerList ->
                    _workers.value = workerList
                }
        }
    }

    private fun observeWorkerChanges() {
        viewModelScope.launch {
            realm.query<Worker>().asFlow().collect { changes ->
                changes.list.forEach { worker ->
                    if (!worker.isSynced) {
                        checkSyncStatus(worker)
                        // Нет необходимости уведомлять наблюдателей через LiveData
                        // StateFlow автоматически обновит список
                    }
                }
            }
        }
    }

    private fun observeNetworkConnectivity() {
        networkManager.registerNetworkCallback { isConnected ->
            if (isConnected) {
                Log.d("Network", "Connection restored, syncing data")
                syncUnsyncedWorkers()
            }
        }
    }

    fun addWorker(fullName: String, phoneNumber: String) {
        viewModelScope.launch {
            try {
                val nextSequence = getNextSequenceNumber()
                realm.write {
                    copyToRealm(Worker().apply {
                        this.sequenceNumber = nextSequence
                        this.fullName = fullName
                        this.phoneNumber = phoneNumber
                        this.isSynced = networkManager.isNetworkAvailable()
                    })
                }
                Log.d("Realm", "Worker added: $fullName")
            } catch (e: Exception) {
                Log.e("Realm", "Error adding worker: $e")
            }
        }
    }

    private fun getNextSequenceNumber(): Int {
        val max = realm.query<Worker>().max<Int>("sequenceNumber").find()
        return (max ?: 0) + 1
    }

    fun updateWorker(id: String, fullName: String, phoneNumber: String) {
        viewModelScope.launch {
            try {
                realm.write {
                    val worker = query<Worker>("_id == $0", id).first().find()
                    worker?.let {
                        it.fullName = fullName
                        it.phoneNumber = phoneNumber
                        it.isSynced = networkManager.isNetworkAvailable()
                    }
                }
                if (!networkManager.isNetworkAvailable()) {
                    Log.d("Sync", "Worker updated offline. Will sync when connection is available.")
                }
            } catch (e: Exception) {
                Log.e("Realm", "Error updating worker: $e")
            }
        }
    }

    fun deleteWorker(id: String) {
        viewModelScope.launch {
            try {
                realm.write {
                    val worker = query<Worker>("_id == $0", id).first().find()
                    worker?.let {
                        delete(it)
                    }
                }
                Log.d("Sync", "Worker deleted permanently.")
            } catch (e: Exception) {
                Log.e("Realm", "Error deleting worker: $e")
            }
        }
    }

    private suspend fun resolveSequenceConflicts() {
        realm.write {
            val allWorkers = query<Worker>().sort("sequenceNumber", Sort.ASCENDING).find()
            val seenNumbers = mutableSetOf<Int>()

            allWorkers.forEach { worker ->
                if (seenNumbers.contains(worker.sequenceNumber)) {
                    // Найден дубликат - увеличиваем номер до первого свободного
                    var newNumber = worker.sequenceNumber + 1
                    while (seenNumbers.contains(newNumber)) {
                        newNumber++
                    }
                    worker.sequenceNumber = newNumber
                }
                seenNumbers.add(worker.sequenceNumber)
            }
        }
    }

    private fun syncUnsyncedWorkers() {
        viewModelScope.launch {
            if (!networkManager.isNetworkAvailable()) return@launch

            resolveSequenceConflicts() // Теперь можно вызвать

            realm.write {
                val unsyncedWorkers = query<Worker>("isSynced == false").find()
                unsyncedWorkers.forEach { worker ->
                    worker.isSynced = true
                }
            }

            renumberWorkersSequentially()
        }
    }


    private suspend fun renumberWorkersSequentially() {
        realm.write {
            val workers = query<Worker>().sort("createdAt", Sort.ASCENDING).find()
            var sequence = 1
            workers.forEach { worker ->
                worker.sequenceNumber = sequence++
            }
        }
    }

    private fun createWorkerSubscription() {
        viewModelScope.launch {
            realm.subscriptions.update {
                add(realm.query<Worker>(), "WorkerSubscription")
            }
        }
    }

    private suspend fun waitForSubscriptionSync() {
        realm.subscriptions.waitForSynchronization()
    }

    private fun checkSyncStatus(worker: Worker) {
        viewModelScope.launch {
            try {
                if (!networkManager.isNetworkAvailable()) {
                    Log.d("Sync", "Network unavailable, skipping sync check for worker ${worker._id}")
                    return@launch
                }

                realm.write {
                    val latestWorker = findLatest(worker)
                    latestWorker?.let {
                        val syncedWorker = realm.query<Worker>("_id == $0", it._id).first().find()
                        val connectionState = realm.syncSession.connectionState
                        val isConnected = connectionState == ConnectionState.CONNECTED
                        val newSyncStatus = syncedWorker != null && isConnected
                        it.isSynced = newSyncStatus
                        Log.d(
                            "Sync",
                            "Worker ${it._id} synced status updated to: ${it.isSynced}. Network available: ${networkManager.isNetworkAvailable()}, Realm connection state: $connectionState"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("Sync", "Error checking sync status: ${e.message}")
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


