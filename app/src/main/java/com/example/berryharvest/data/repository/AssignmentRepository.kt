package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.MyApplication
import com.example.berryharvest.data.network.EnhancedNetworkManager
import com.example.berryharvest.ui.assign_rows.Assignment
import com.example.berryharvest.ui.assign_rows.AssignmentGroup
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout

private const val TAG = "AssignmentRepository"
private const val ENTITY_TYPE = "assignment"

class AssignmentRepository(
    private val application: Application,
    private val networkManager: EnhancedNetworkManager
) : BaseRepository<Assignment> {

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

    override suspend fun getAll(): Flow<Result<List<Assignment>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Assignment>().asFlow()
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
     * Get assignments grouped by row number.
     * @return Flow of Result containing AssignmentGroups
     */
    fun getAllGroupedByRow(): Flow<Result<List<AssignmentGroup>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Assignment>().asFlow()
                .map { results ->
                    // Group assignments by row
                    val assignmentsByRow = results.list.groupBy { it.rowNumber }

                    // Convert to AssignmentGroup objects
                    val groups = assignmentsByRow.map { (rowNumber, assignments) ->
                        AssignmentGroup(rowNumber, assignments.toList())
                    }.sortedBy { it.rowNumber }

                    Result.Success(groups)
                }
                .catch { e ->
                    Log.e(TAG, "Error in getAllGroupedByRow flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAllGroupedByRow", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Get assignments for a specific row.
     * @param rowNumber The row number to filter by
     * @return Flow of Result containing assignments for the specified row
     */
    fun getByRow(rowNumber: Int): Flow<Result<List<Assignment>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Assignment>("rowNumber == $0", rowNumber).asFlow()
                .map { result -> Result.Success(result.list.toList()) }
                .catch { e ->
                    Log.e(TAG, "Error in getByRow flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getByRow", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Get assignment for a specific worker.
     * @param workerId The worker ID to find the assignment for
     * @return Result containing the assignment or null if not found
     */
    suspend fun getByWorkerId(workerId: String): Result<Assignment?> {
        return try {
            val realm = getRealm()
            val assignment = realm.query<Assignment>("workerId == $0", workerId).first().find()
            Result.Success(assignment)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getByWorkerId", e)
            Result.Error(e)
        }
    }

    override suspend fun getById(id: String): Result<Assignment?> {
        return try {
            val realm = getRealm()
            val assignment = realm.query<Assignment>("_id == $0", id).first().find()
            Result.Success(assignment)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getById", e)
            Result.Error(e)
        }
    }

    override suspend fun add(item: Assignment): Result<String> {
        return try {
            val realm = getRealm()
            var assignmentId = ""

            // First check if the worker already has an assignment
            val existingAssignment = realm.query<Assignment>("workerId == $0", item.workerId).first().find()

            realm.write {
                if (existingAssignment != null) {
                    // Update existing assignment instead of creating a new one
                    findLatest(existingAssignment)?.apply {
                        rowNumber = item.rowNumber
                        isSynced = networkManager.isNetworkAvailable()
                    }
                    assignmentId = existingAssignment._id
                    Log.d(TAG, "Updated existing assignment for worker: ${item.workerId}")
                } else {
                    // Create a new assignment
                    val newAssignment = copyToRealm(Assignment().apply {
                        _id = item._id
                        rowNumber = item.rowNumber
                        workerId = item.workerId
                        isSynced = networkManager.isNetworkAvailable()
                    })
                    assignmentId = newAssignment._id
                    Log.d(TAG, "Created new assignment: $assignmentId")
                }
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(assignmentId, ENTITY_TYPE)
                )
            }

            Result.Success(assignmentId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in add", e)
            Result.Error(e)
        }
    }

    override suspend fun update(item: Assignment): Result<Boolean> {
        return try {
            val realm = getRealm()
            realm.write {
                val assignment = query<Assignment>("_id == $0", item._id).first().find()
                assignment?.apply {
                    rowNumber = item.rowNumber
                    workerId = item.workerId
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(TAG, "Updated assignment: ${item._id}")
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

    /**
     * Delete all assignments for a specific row.
     * @param rowNumber The row number to delete assignments for
     * @return Result indicating success or failure
     */
    suspend fun deleteByRow(rowNumber: Int): Result<Boolean> {
        return try {
            val realm = getRealm()
            val assignments = realm.query<Assignment>("rowNumber == $0", rowNumber).find()

            realm.write {
                assignments.forEach { assignment ->
                    delete(assignment)
                    Log.d(TAG, "Deleted assignment in row $rowNumber: ${assignment._id}")

                    if (!networkManager.isNetworkAvailable()) {
                        addPendingOperation(
                            PendingOperation.Delete(assignment._id, ENTITY_TYPE)
                        )
                    }
                }
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in deleteByRow", e)
            Result.Error(e)
        }
    }

    override suspend fun delete(id: String): Result<Boolean> {
        return try {
            val realm = getRealm()
            realm.write {
                val assignment = query<Assignment>("_id == $0", id).first().find()
                assignment?.let {
                    delete(it)
                    Log.d(TAG, "Deleted assignment: $id")
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
                    val unsyncedAssignments = query<Assignment>("isSynced == false").find()
                    Log.d(TAG, "Found ${unsyncedAssignments.size} unsynced assignments")

                    unsyncedAssignments.forEach { assignment ->
                        assignment.isSynced = true
                        Log.d(TAG, "Marked assignment ${assignment._id} as synced")
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

    override fun close() {
        _realm?.close()
        _realm = null
        Log.d(TAG, "Repository closed")
    }
}