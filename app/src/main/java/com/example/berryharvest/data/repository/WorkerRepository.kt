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

    // Add error state for better error propagation
    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private suspend fun getRealm(): Realm {
        if (_realm != null && !_realm!!.isClosed()) {
            return _realm!!
        }

        // Get realm outside of synchronized block to avoid suspension in critical section
        return try {
            Log.d(TAG, "Getting Realm instance")
            val realm = app.getRealmInstance()
            synchronized(this) {
                _realm = realm
            }
            Log.d(TAG, "Successfully obtained Realm instance")
            realm
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Realm instance", e)
            _errorState.value = "Failed to initialize database: ${e.message}"
            throw e
        }
    }

    override suspend fun getAll(): Flow<Result<List<Worker>>> = flow {
        emit(Result.Loading)
        try {
            Log.d(TAG, "Getting all workers")
            val realm = getRealm()
            realm.query<Worker>().sort("sequenceNumber", Sort.ASCENDING).asFlow()
                .map { result ->
                    Log.d(TAG, "Received ${result.list.size} workers in flow")
                    Result.Success(result.list.toList())
                }
                .catch { e ->
                    Log.e(TAG, "Error in getAll flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAll", e)
            _errorState.value = "Failed to load workers: ${e.message}"
            emit(Result.Error(e))
        }
    }

    suspend fun getWorkersByIds(ids: List<String>): Flow<Result<List<Worker>>> = flow {
        emit(Result.Loading)
        try {
            if (ids.isEmpty()) {
                Log.d(TAG, "Empty ID list provided, returning empty result")
                emit(Result.Success(emptyList()))
                return@flow
            }

            Log.d(TAG, "Getting workers by IDs: ${ids.joinToString()}")
            val realm = getRealm()

            // Create a query with OR conditions for each ID
            val queryString = if (ids.size == 1) {
                "_id == $0"
            } else {
                ids.mapIndexed { index, _ -> "_id == \$$index" }.joinToString(" OR ")
            }

            realm.query<Worker>(queryString, *ids.toTypedArray()).asFlow()
                .map { results ->
                    Log.d(TAG, "Found ${results.list.size} workers by IDs")
                    Result.Success(results.list.toList())
                }
                .catch { e ->
                    Log.e(TAG, "Error in getWorkersByIds flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getWorkersByIds", e)
            _errorState.value = "Failed to load workers by IDs: ${e.message}"
            emit(Result.Error(e))
        }
    }

    override suspend fun getById(id: String): Result<Worker?> {
        return try {
            Log.d(TAG, "Getting worker by ID: $id")
            val realm = getRealm()
            val worker = realm.query<Worker>("_id == $0", id).first().find()
            Log.d(TAG, "Worker found: ${worker != null}")
            Result.Success(worker)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getById", e)
            _errorState.value = "Failed to get worker: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun add(item: Worker): Result<String> {
        Log.d(TAG, "Starting add operation for worker: ${item.fullName}")
        return try {
            val realm = getRealm()
            var workerId = ""

            // Get next sequence number outside of write transaction
            val nextSequence = getNextSequenceNumber(realm)
            Log.d(TAG, "Next sequence number: $nextSequence")

            // Keep transaction small and focused
            realm.write {
                val newWorker = copyToRealm(Worker().apply {
                    sequenceNumber = nextSequence
                    fullName = item.fullName
                    phoneNumber = item.phoneNumber
                    isSynced = networkManager.isNetworkAvailable()
                })
                workerId = newWorker._id
                Log.d(TAG, "Added worker: $workerId with sequence $nextSequence")
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(workerId, ENTITY_TYPE)
                )
            }

            Log.d(TAG, "Add operation completed successfully")
            Result.Success(workerId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in add", e)
            _errorState.value = "Failed to add worker: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun update(item: Worker): Result<Boolean> {
        Log.d(TAG, "Starting update operation for worker: ${item._id}")
        return try {
            val realm = getRealm()

            // Keep transaction small and focused
            realm.write {
                // Find the live object INSIDE transaction
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

            Log.d(TAG, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in update", e)
            _errorState.value = "Failed to update worker: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun delete(id: String): Result<Boolean> {
        Log.d(TAG, "Starting delete operation for worker: $id")
        return try {
            val realm = getRealm()

            // Keep transaction small and focused
            realm.write {
                // Find the live object INSIDE transaction
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

            Log.d(TAG, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in delete", e)
            _errorState.value = "Failed to delete worker: ${e.message}"
            Result.Error(e)
        }
    }

    override fun getConnectionState(): Flow<ConnectionState> {
        return networkManager.connectionState
    }

    override suspend fun syncPendingChanges(): Result<Boolean> {
        Log.d(TAG, "Starting syncPendingChanges operation")
        return try {
            if (!networkManager.isNetworkAvailable()) {
                Log.d(TAG, "Network not available, skipping sync")
                return Result.Error(Exception("Network not available"))
            }

            val operations = _pendingOperations.value
            Log.d(TAG, "Syncing ${operations.size} pending operations")

            val realm = getRealm()

            // Only start transaction if there are unsynced workers
            val unsyncedCount = realm.query<Worker>("isSynced == false").count().find()

            if (unsyncedCount > 0) {
                Log.d(TAG, "Found $unsyncedCount unsynced workers")

                // Try with a timeout to avoid indefinite waiting
                withTimeout(10000) {
                    realm.write {
                        val unsyncedWorkers = query<Worker>("isSynced == false").find()
                        Log.d(TAG, "Processing ${unsyncedWorkers.size} unsynced workers")

                        unsyncedWorkers.forEach { worker ->
                            worker.isSynced = true
                            Log.d(TAG, "Marked worker ${worker._id} as synced")
                        }
                    }
                }
            } else {
                Log.d(TAG, "No unsynced workers found")
            }

            // Clear pending operations after successful sync
            _pendingOperations.value = emptyList()
            Log.d(TAG, "Cleared pending operations")

            Log.d(TAG, "SyncPendingChanges operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncPendingChanges", e)
            _errorState.value = "Failed to sync changes: ${e.message}"
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
            Log.d(TAG, "Starting sequence conflict resolution")
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
                        Log.d(TAG, "Resolved duplicate: Worker ${worker._id} assigned new sequence $newNumber")
                    }
                    seenNumbers.add(worker.sequenceNumber)
                }

                // Second pass to ensure sequential numbering
                var sequence = 1
                query<Worker>().sort("sequenceNumber", Sort.ASCENDING).find().forEach { worker ->
                    worker.sequenceNumber = sequence++
                    Log.d(TAG, "Renumbered: Worker ${worker._id} assigned sequence ${worker.sequenceNumber}")
                }
            }
            Log.d(TAG, "Sequence conflict resolution completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving sequence conflicts", e)
            _errorState.value = "Failed to resolve sequence conflicts: ${e.message}"
        }
    }

    override fun close() {
        _realm?.close()
        _realm = null
        Log.d(TAG, "Repository closed")
    }
}