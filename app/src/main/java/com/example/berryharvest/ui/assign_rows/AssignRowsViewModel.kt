package com.example.berryharvest.ui.assign_rows

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.berryharvest.MyApplication
import com.example.berryharvest.NetworkConnectivityManager
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.sync.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import io.realm.kotlin.mongodb.subscriptions
import io.realm.kotlin.mongodb.syncSession

class AssignRowsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: MyApplication
        get() = getApplication() as MyApplication

    private val networkManager = NetworkConnectivityManager(application)

    private lateinit var realm: Realm

    init {
        initRealm()
        observeNetworkConnectivity()
    }

    private fun initRealm() {
        viewModelScope.launch {
            try {
                realm = app.getRealmInstance()
                createAssignmentSubscription()
                realm.subscriptions.waitForSynchronization()
                observeAssignmentChanges()
            } catch (e: Exception) {
                Log.e("Realm", "Error initializing Realm: ${e.message}")
            }
        }
    }

    private fun observeNetworkConnectivity() {
        networkManager.registerNetworkCallback { isConnected ->
            if (isConnected) {
                Log.d("Network", "Connection restored, syncing data")
                syncUnsyncedAssignments()
            }
        }
    }

    val assignments: Flow<List<Assignment>> = realm.query<Assignment>().asFlow().map { it.list }
        .catch { throwable -> Log.e("Realm", "Error fetching assignments: $throwable") }

    private fun syncUnsyncedAssignments() {
        viewModelScope.launch {
            if (!networkManager.isNetworkAvailable()) {
                Log.d("Sync", "Network unavailable, skipping sync of unsynced assignments")
                return@launch
            }

            realm.write {
                val unsyncedAssignments = query<Assignment>("isSynced == false").find()
                Log.d("Sync", "Found ${unsyncedAssignments.size} unsynced assignments")
                unsyncedAssignments.forEach { assignment ->
                    if (assignment.isDeleted) {
                        delete(assignment)
                        Log.d("Sync", "Deleted assignment: ${assignment._id}")
                    } else {
                        assignment.isSynced = true
                        Log.d("Sync", "Synced assignment: ${assignment._id}")
                    }
                }
            }
        }
    }

    private fun createAssignmentSubscription() {
        viewModelScope.launch {
            realm.subscriptions.update {
                add(realm.query<Assignment>())
            }
        }
    }

    private suspend fun waitForSubscriptionSync() {
        realm.subscriptions.waitForSynchronization()
    }

    private fun observeAssignmentChanges() {
        viewModelScope.launch {
            realm.query<Assignment>().asFlow().collect { changes ->
                changes.list.forEach { assignment ->
                    if (!assignment.isSynced) {
                        checkSyncStatus(assignment)
                    }
                }
            }
        }
    }

    private fun checkSyncStatus(assignment: Assignment) {
        viewModelScope.launch {
            try {
                if (!networkManager.isNetworkAvailable()) {
                    Log.d("Sync", "Network unavailable, skipping sync check for assignment ${assignment._id}")
                    return@launch
                }

                realm.write {
                    val latestAssignment = findLatest(assignment)
                    latestAssignment?.let {
                        val syncedAssignment = realm.query<Assignment>("_id == $0", it._id).first().find()
                        val connectionState = realm.syncSession.connectionState
                        val isConnected = connectionState == ConnectionState.CONNECTED
                        val newSyncStatus = syncedAssignment != null && isConnected
                        it.isSynced = newSyncStatus
                        Log.d("Sync", "Assignment ${it._id} synced status updated to: ${it.isSynced}")
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
