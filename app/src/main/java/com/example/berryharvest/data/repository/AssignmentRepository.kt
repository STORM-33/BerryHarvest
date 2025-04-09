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

    override suspend fun getAll(): Flow<Result<List<Assignment>>> = flow {
        emit(Result.Loading)
        try {
            Log.d(TAG, "Getting all assignments")
            val realm = getRealm()
            realm.query<Assignment>().asFlow()
                .map { result ->
                    Log.d(TAG, "Received ${result.list.size} assignments in flow")
                    Result.Success(result.list.toList())
                }
                .catch { e ->
                    Log.e(TAG, "Error in getAll flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAll", e)
            _errorState.value = "Failed to load assignments: ${e.message}"
            emit(Result.Error(e))
        }
    }

    fun getAllGroupedByRow(): Flow<Result<List<AssignmentGroup>>> = flow {
        emit(Result.Loading)
        try {
            Log.d(TAG, "Getting assignments grouped by row")
            val realm = getRealm()
            realm.query<Assignment>().asFlow()
                .map { results ->
                    // Group assignments by row
                    val assignmentsByRow = results.list.groupBy { it.rowNumber }

                    // Convert to AssignmentGroup objects
                    val groups = assignmentsByRow.map { (rowNumber, assignments) ->
                        AssignmentGroup(rowNumber, assignments.toList())
                    }.sortedBy { it.rowNumber }

                    Log.d(TAG, "Created ${groups.size} assignment groups")
                    Result.Success(groups)
                }
                .catch { e ->
                    Log.e(TAG, "Error in getAllGroupedByRow flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAllGroupedByRow", e)
            _errorState.value = "Failed to load assignment groups: ${e.message}"
            emit(Result.Error(e))
        }
    }

    override suspend fun getById(id: String): Result<Assignment?> {
        return try {
            Log.d(TAG, "Getting assignment by ID: $id")
            val realm = getRealm()
            val assignment = realm.query<Assignment>("_id == $0", id).first().find()
            Log.d(TAG, "Assignment found: ${assignment != null}")
            Result.Success(assignment)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getById", e)
            _errorState.value = "Failed to get assignment: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun add(item: Assignment): Result<String> {
        Log.d(TAG, "Starting add operation for assignment with workerId=${item.workerId}")
        return try {
            val realm = getRealm()
            var assignmentId = ""

            // First check if the worker already has an assignment - do this outside transaction
            val existingAssignment = realm.query<Assignment>("workerId == $0", item.workerId).first().find()
            Log.d(TAG, "Existing assignment check: ${existingAssignment?._id ?: "none"}")

            // Keep transaction small and focused
            realm.write {
                if (existingAssignment != null) {
                    // Update existing assignment instead of creating a new one
                    // Get live object INSIDE transaction
                    val liveAssignment = query<Assignment>("_id == $0", existingAssignment._id).first().find()
                    liveAssignment?.apply {
                        rowNumber = item.rowNumber
                        isSynced = networkManager.isNetworkAvailable()
                    }
                    assignmentId = existingAssignment._id
                    Log.d(TAG, "Updated existing assignment: $assignmentId")
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

            Log.d(TAG, "Add operation completed successfully")
            Result.Success(assignmentId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in add", e)
            _errorState.value = "Failed to add assignment: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun update(item: Assignment): Result<Boolean> {
        Log.d(TAG, "Starting update operation for assignment: ${item._id}")
        return try {
            val realm = getRealm()

            // Keep transaction small and focused
            realm.write {
                // Find the live object INSIDE transaction
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

            Log.d(TAG, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in update", e)
            _errorState.value = "Failed to update assignment: ${e.message}"
            Result.Error(e)
        }
    }

    suspend fun deleteByRow(rowNumber: Int): Result<Boolean> {
        Log.d(TAG, "Starting deleteByRow operation for row: $rowNumber")
        return try {
            val realm = getRealm()

            // Get the IDs of assignments to delete OUTSIDE transaction
            val assignmentIds = realm.query<Assignment>("rowNumber == $0", rowNumber)
                .find()
                .map { it._id }

            Log.d(TAG, "Found ${assignmentIds.size} assignments to delete in row $rowNumber")

            // Keep transaction small and focused
            realm.write {
                assignmentIds.forEach { id ->
                    // Find the live object INSIDE transaction
                    val liveAssignment = query<Assignment>("_id == $0", id).first().find()
                    liveAssignment?.let {
                        delete(it)
                        Log.d(TAG, "Deleted assignment: $id in row $rowNumber")
                    }
                }
            }

            if (!networkManager.isNetworkAvailable()) {
                assignmentIds.forEach { id ->
                    addPendingOperation(
                        PendingOperation.Delete(id, ENTITY_TYPE)
                    )
                }
            }

            Log.d(TAG, "DeleteByRow operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in deleteByRow", e)
            _errorState.value = "Failed to delete row: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun delete(id: String): Result<Boolean> {
        Log.d(TAG, "Starting delete operation for assignment: $id")
        return try {
            val realm = getRealm()

            // Keep transaction small and focused
            realm.write {
                // Find the live object INSIDE transaction
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

            Log.d(TAG, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in delete", e)
            _errorState.value = "Failed to delete assignment: ${e.message}"
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

            // Only start transaction if there are unsynced assignments
            val unsyncedCount = realm.query<Assignment>("isSynced == false").count().find()

            if (unsyncedCount > 0) {
                Log.d(TAG, "Found $unsyncedCount unsynced assignments")

                // Try with a timeout to avoid indefinite waiting
                withTimeout(10000) {
                    realm.write {
                        val unsyncedAssignments = query<Assignment>("isSynced == false").find()
                        Log.d(TAG, "Processing ${unsyncedAssignments.size} unsynced assignments")

                        unsyncedAssignments.forEach { assignment ->
                            assignment.isSynced = true
                            Log.d(TAG, "Marked assignment ${assignment._id} as synced")
                        }
                    }
                }
            } else {
                Log.d(TAG, "No unsynced assignments found")
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

    override fun close() {
        _realm?.close()
        _realm = null
        Log.d(TAG, "Repository closed")
    }
}