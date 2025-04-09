package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.MyApplication
import com.example.berryharvest.data.network.EnhancedNetworkManager
import com.example.berryharvest.ui.add_worker.Worker
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.Sort
import io.realm.kotlin.query.max
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout

private const val TAG = "WorkerRepository"
private const val ENTITY_TYPE = "worker"

class WorkerRepository(
    private val application: Application,
    private val networkManager: EnhancedNetworkManager
) : BaseRepository<Worker> {
    private val app: MyApplication = application as MyApplication
    private var _realm: Realm? = null
    private val _pendingOperations = MutableStateFlow<List<PendingOperation>>(emptyList())
    val pendingOperations: StateFlow<List<PendingOperation>> = _pendingOperations.asStateFlow()

    private suspend fun getRealm(): Realm {
        if (_realm != null) {
            return _realm!!
        }

        // Get realm outside of synchronized block to avoid suspension in critical section
        return try {
            val realm = app.getRealmInstance()
            synchronized(this) {
                _realm = realm
            }
            realm
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Realm instance", e)
            throw e
        }
    }

    override suspend fun getAll(): Flow<Result<List<Worker>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Worker>().sort("sequenceNumber", Sort.ASCENDING).asFlow()
                .map { result -> Result.Success(result.list.toList()) }
                .catch { e ->
                    Log.e(TAG, "Error in getAll flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAll", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Get multiple workers by their IDs as a reactive Flow
     */
    suspend fun getWorkersByIds(ids: List<String>): Flow<Result<List<Worker>>> = flow {
        emit(Result.Loading)
        try {
            if (ids.isEmpty()) {
                emit(Result.Success(emptyList()))
                return@flow
            }

            val realm = getRealm()
            // Create a query with OR conditions for each ID
            val queryString = if (ids.size == 1) {
                "_id == $0"
            } else {
                ids.mapIndexed { index, _ -> "_id == \$$index" }.joinToString(" OR ")
            }

            realm.query<Worker>(queryString, *ids.toTypedArray()).asFlow()
                .map { results -> Result.Success(results.list.toList()) }
                .catch { e ->
                    Log.e(TAG, "Error in getWorkersByIds flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getWorkersByIds", e)
            emit(Result.Error(e))
        }
    }

    override suspend fun getById(id: String): Result<Worker?> {
        return try {
            val realm = getRealm()
            val worker = realm.query<Worker>("_id == $0", id).first().find()
            Result.Success(worker)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getById", e)
            Result.Error(e)
        }
    }

    override suspend fun add(item: Worker): Result<String> {
        return try {
            val realm = getRealm()
            var workerId = ""

            // Get next sequence number outside of write transaction
            val nextSequence = getNextSequenceNumber(realm)

            realm.write {
                val newWorker = copyToRealm(Worker().apply {
                    sequenceNumber = nextSequence
                    fullName = item.fullName
                    phoneNumber = item.phoneNumber
                    isSynced = networkManager.isNetworkAvailable()
                })
                workerId = newWorker._id
                Log.d(TAG, "Added worker: $workerId")
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(workerId, ENTITY_TYPE)
                )
            }

            Result.Success(workerId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in add", e)
            Result.Error(e)
        }
    }

    override suspend fun update(item: Worker): Result<Boolean> {
        return try {
            val realm = getRealm()
            realm.write {
                val worker = query<Worker>("_id == $0", item._id).first().find()
                worker?.apply {
                    fullName = item.fullName
                    phoneNumber = item.phoneNumber
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(TAG, "Updated worker: ${item._id}")
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(item._id, ENTITY_TYPE)
                )
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in update", e)
            Result.Error(e)
        }
    }

    override suspend fun delete(id: String): Result<Boolean> {
        return try {
            val realm = getRealm()
            realm.write {
                val worker = query<Worker>("_id == $0", id).first().find()
                worker?.let {
                    delete(it)
                    Log.d(TAG, "Deleted worker: $id")
                }
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Delete(id, ENTITY_TYPE)
                )
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in delete", e)
            Result.Error(e)
        }
    }

    override fun getConnectionState(): Flow<ConnectionState> {
        return networkManager.connectionState
    }

    override suspend fun syncPendingChanges(): Result<Boolean> {
        return try {
            if (!networkManager.isNetworkAvailable()) {
                return Result.Error(Exception("Network not available"))
            }

            val operations = _pendingOperations.value
            Log.d(TAG, "Syncing ${operations.size} pending operations")

            val realm = getRealm()

            // Try with a timeout to avoid indefinite waiting
            withTimeout(10000) {
                realm.write {
                    val unsyncedWorkers = query<Worker>("isSynced == false").find()
                    Log.d(TAG, "Found ${unsyncedWorkers.size} unsynced workers")

                    unsyncedWorkers.forEach { worker ->
                        worker.isSynced = true
                        Log.d(TAG, "Marked worker ${worker._id} as synced")
                    }
                }
            }

            // Clear pending operations after successful sync
            _pendingOperations.value = emptyList()

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncPendingChanges", e)
            Result.Error(e)
        }
    }

    private fun addPendingOperation(operation: PendingOperation) {
        val currentOperations = _pendingOperations.value.toMutableList()
        currentOperations.add(operation)
        _pendingOperations.value = currentOperations
        Log.d(TAG, "Added pending operation: $operation")
    }

    private fun getNextSequenceNumber(realm: Realm): Int {
        val max = realm.query<Worker>().max<Int>("sequenceNumber").find()
        return (max ?: 0) + 1
    }

    // Helper method to resolve sequence conflicts (e.g., after offline operations)
    suspend fun resolveSequenceConflicts() {
        try {
            val realm = getRealm()
            realm.write {
                val allWorkers = query<Worker>().sort("sequenceNumber", Sort.ASCENDING).find()
                val seenNumbers = mutableSetOf<Int>()

                // First pass to identify duplicates
                allWorkers.forEach { worker ->
                    if (seenNumbers.contains(worker.sequenceNumber)) {
                        // Found duplicate - increase number to first available
                        var newNumber = worker.sequenceNumber + 1
                        while (seenNumbers.contains(newNumber)) {
                            newNumber++
                        }
                        worker.sequenceNumber = newNumber
                    }
                    seenNumbers.add(worker.sequenceNumber)
                }

                // Second pass to ensure sequential numbering
                var sequence = 1
                query<Worker>().sort("sequenceNumber", Sort.ASCENDING).find().forEach { worker ->
                    worker.sequenceNumber = sequence++
                }
            }
            Log.d(TAG, "Resolved sequence conflicts")
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving sequence conflicts", e)
        }
    }

    override fun close() {
        _realm?.close()
        _realm = null
        Log.d(TAG, "Repository closed")
    }
}