package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.network.EnhancedNetworkManager
import com.example.berryharvest.ui.assign_rows.AssignmentGroup
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Implementation of AssignmentRepository that extends BaseRepositoryImpl.
 * Handles all Assignment-specific data operations.
 */
class AssignmentRepositoryImpl(
    application: Application,
    networkManager: EnhancedNetworkManager,
    databaseTransactionManager: DatabaseTransactionManager
) : BaseRepositoryImpl<Assignment>(
    application = application,
    networkManager = networkManager,
    databaseTransactionManager = databaseTransactionManager,
    entityType = "assignment",
    logTag = "AssignmentRepository"
), AssignmentRepository {

    /**
     * Create the base query for Assignment entities.
     */
    override fun createQuery(realm: Realm): RealmQuery<Assignment> {
        return realm.query<Assignment>("isDeleted == false")
    }

    /**
     * Create a query to find an Assignment by ID.
     */
    override fun createQueryById(realm: Realm, id: String): RealmQuery<Assignment> {
        return realm.query<Assignment>("_id == $0", id)
    }

    /**
     * Get the query to find unsynced Assignment entities.
     */
    override fun getUnsyncedQuery(realm: Realm): RealmQuery<Assignment> {
        return realm.query<Assignment>("isSynced == false")
    }

    /**
     * Mark an Assignment entity as synced.
     */
    override fun MutableRealm.markAsSynced(entity: Assignment) {
        entity.isSynced = true
    }

    /**
     * Get all assignments grouped by row number.
     */
    override suspend fun getAllGroupedByRow(): Flow<Result<List<AssignmentGroup>>> {
        return try {
            val realm = getRealm()

            realm.query<Assignment>("isDeleted == false")
                .asFlow()
                .map { results ->
                    val groups = results.list
                        .groupBy { it.rowNumber }
                        .map { (rowNumber, assignments) ->
                            AssignmentGroup(rowNumber, assignments.toList())
                        }
                        .sortedBy { it.rowNumber }

                    Log.d(logTag, "Transformed ${results.list.size} assignments into ${groups.size} groups")
                    Result.Success(groups) as Result<List<AssignmentGroup>>
                }
                .catch { e ->
                    Log.e(logTag, "Error in getAllGroupedByRow flow", e)
                    emit(Result.Error(e))
                }
        } catch (e: Exception) {
            Log.e(logTag, "Error setting up getAllGroupedByRow", e)
            flow { emit(Result.Error(e)) }
        }
    }

    /**
     * Add a new Assignment entity.
     */
    override suspend fun add(item: Assignment): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting add operation for assignment")
            var assignmentId = ""

            // First check if the worker already has an assignment
            val existingAssignment = realm.query<Assignment>(
                "workerId == $0 AND isDeleted == false",
                item.workerId
            ).first().find()

            Log.d(logTag, "Existing assignment check: ${existingAssignment?._id ?: "none"}")

            // Keep transaction small and focused
            safeWrite {
                if (existingAssignment != null) {
                    // Update existing assignment instead of creating a new one
                    val liveAssignment = query<Assignment>("_id == $0", existingAssignment._id).first().find()
                    liveAssignment?.apply {
                        rowNumber = item.rowNumber
                        isSynced = networkManager.isNetworkAvailable()
                    }
                    assignmentId = existingAssignment._id
                    Log.d(logTag, "Updated existing assignment: $assignmentId")
                } else {
                    // Create a new assignment
                    val newAssignment = copyToRealm(Assignment().apply {
                        _id = item._id.ifEmpty { UUID.randomUUID().toString() }
                        rowNumber = item.rowNumber
                        workerId = item.workerId
                        isSynced = networkManager.isNetworkAvailable()
                        isDeleted = false
                    })
                    assignmentId = newAssignment._id
                    Log.d(logTag, "Created new assignment: $assignmentId")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(assignmentId, entityType)
                )
            }

            Log.d(logTag, "Add operation completed successfully")
            Result.Success(assignmentId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in add", e)
            setError("Failed to add assignment: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Update an existing Assignment entity.
     */
    override suspend fun update(item: Assignment): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting update operation for assignment: ${item._id}")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val assignment = query<Assignment>("_id == $0", item._id).first().find()
                assignment?.apply {
                    rowNumber = item.rowNumber
                    workerId = item.workerId
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(logTag, "Updated assignment: ${item._id}")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(item._id, entityType)
                )
            }

            Log.d(logTag, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in update", e)
            setError("Failed to update assignment: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete assignments for a specific row.
     */
    override suspend fun deleteByRow(rowNumber: Int): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting deleteByRow operation for row: $rowNumber")

            // Get the IDs of assignments to delete
            val assignmentIds = realm.query<Assignment>("rowNumber == $0 AND isDeleted == false", rowNumber)
                .find()
                .map { it._id }

            Log.d(logTag, "Found ${assignmentIds.size} assignments to delete in row $rowNumber")

            if (assignmentIds.isEmpty()) {
                return@withDatabaseContext Result.Success(true)
            }

            // Keep transaction small and focused
            safeWrite {
                assignmentIds.forEach { id ->
                    // Find the live object INSIDE transaction
                    val liveAssignment = query<Assignment>("_id == $0", id).first().find()
                    liveAssignment?.apply {
                        isDeleted = true
                        isSynced = networkManager.isNetworkAvailable()
                        Log.d(logTag, "Marked assignment as deleted: $id in row $rowNumber")
                    }
                }
            }

            // Track pending operations if offline
            if (!networkManager.isNetworkAvailable()) {
                assignmentIds.forEach { id ->
                    addPendingOperation(
                        PendingOperation.Delete(id, entityType)
                    )
                }
            }

            Log.d(logTag, "DeleteByRow operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in deleteByRow", e)
            setError("Failed to delete row: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete an Assignment entity by ID.
     */
    override suspend fun delete(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting delete operation for assignment: $id")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val assignment = query<Assignment>("_id == $0", id).first().find()
                assignment?.apply {
                    isDeleted = true
                    isSynced = networkManager.isNetworkAvailable()
                    Log.d(logTag, "Marked assignment as deleted: $id")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Delete(id, entityType)
                )
            }

            Log.d(logTag, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in delete", e)
            setError("Failed to delete assignment: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Get all assigned row numbers.
     */
    override suspend fun getAllRowNumbers(): List<Int> = withDatabaseContext { realm ->
        try {
            val assignments = realm.query<Assignment>("isDeleted == false").find()
            val rowNumbers = assignments.map { it.rowNumber }.distinct()
            Log.d(logTag, "Found ${rowNumbers.size} distinct row numbers")
            rowNumbers
        } catch (e: Exception) {
            Log.e(logTag, "Error getting row numbers", e)
            emptyList()
        }
    }

    /**
     * Find the row assigned to a worker.
     * @return Row number or -1 if not assigned
     */
    override suspend fun findWorkerRow(workerId: String): Int = withDatabaseContext { realm ->
        try {
            val assignment = realm.query<Assignment>(
                "workerId == $0 AND isDeleted == false",
                workerId
            ).first().find()

            assignment?.rowNumber ?: -1
        } catch (e: Exception) {
            Log.e(logTag, "Error finding worker row", e)
            -1
        }
    }

    /**
     * Get all workers assigned to a specific row
     */
    override suspend fun getWorkersInRow(rowNumber: Int): Result<List<String>> = withDatabaseContext { realm ->
        try {
            val workerIds = realm.query<Assignment>(
                "rowNumber == $0 AND isDeleted == false",
                rowNumber
            ).find().map { it.workerId }

            Result.Success(workerIds)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting workers in row", e)
            Result.Error(e)
        }
    }

    /**
     * Move all workers from one row to another
     */
    override suspend fun moveRow(fromRow: Int, toRow: Int): Result<Boolean> = withDatabaseContext { realm ->
        try {
            // Get assignments to move
            val assignmentIds = realm.query<Assignment>(
                "rowNumber == $0 AND isDeleted == false",
                fromRow
            ).find().map { it._id }

            if (assignmentIds.isEmpty()) {
                return@withDatabaseContext Result.Success(false)
            }

            safeWrite {
                assignmentIds.forEach { id ->
                    val assignment = query<Assignment>("_id == $0", id).first().find()
                    assignment?.apply {
                        rowNumber = toRow
                        isSynced = networkManager.isNetworkAvailable()
                    }
                }
            }

            if (!networkManager.isNetworkAvailable()) {
                assignmentIds.forEach { id ->
                    addPendingOperation(
                        PendingOperation.Update(id, entityType)
                    )
                }
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error moving row", e)
            Result.Error(e)
        }
    }

    /**
     * Assign a worker to a row.
     */
    override suspend fun assignWorkerToRow(workerId: String, rowNumber: Int): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting assign worker $workerId to row $rowNumber")
            var assignmentId = ""

            // First check if the worker already has an assignment
            val existingAssignment = realm.query<Assignment>(
                "workerId == $0 AND isDeleted == false",
                workerId
            ).first().find()

            Log.d(logTag, "Existing assignment check: ${existingAssignment?._id ?: "none"}")

            // Keep transaction small and focused
            safeWrite {
                if (existingAssignment != null) {
                    // Update existing assignment instead of creating a new one
                    val liveAssignment = query<Assignment>("_id == $0", existingAssignment._id).first().find()
                    liveAssignment?.apply {
                        this.rowNumber = rowNumber
                        isSynced = networkManager.isNetworkAvailable()
                    }
                    assignmentId = existingAssignment._id
                    Log.d(logTag, "Updated existing assignment: $assignmentId")
                } else {
                    // Create a new assignment
                    val newAssignment = copyToRealm(Assignment().apply {
                        _id = UUID.randomUUID().toString()
                        this.rowNumber = rowNumber
                        this.workerId = workerId
                        isSynced = networkManager.isNetworkAvailable()
                        isDeleted = false
                    })
                    assignmentId = newAssignment._id
                    Log.d(logTag, "Created new assignment: $assignmentId")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(assignmentId, entityType)
                )
            }

            Log.d(logTag, "Assign operation completed successfully")
            Result.Success(assignmentId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in assignWorkerToRow", e)
            setError("Failed to assign worker to row: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Move a worker to a different row.
     */
    override suspend fun moveWorkerToRow(assignmentId: String, newRowNumber: Int): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting move assignment $assignmentId to row $newRowNumber")

            safeWrite {
                val assignment = query<Assignment>("_id == $0", assignmentId).first().find()
                assignment?.apply {
                    rowNumber = newRowNumber
                    isSynced = networkManager.isNetworkAvailable()
                    Log.d(logTag, "Moved assignment to row $newRowNumber")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(assignmentId, entityType)
                )
            }

            Log.d(logTag, "Move operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in moveWorkerToRow", e)
            setError("Failed to move worker to row: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Check if a worker is already assigned to a row.
     */
    override suspend fun getWorkerAssignment(workerId: String): Result<Assignment?> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Checking assignment for worker $workerId")

            val assignment = realm.query<Assignment>(
                "workerId == $0 AND isDeleted == false",
                workerId
            ).first().find()

            Log.d(logTag, "Assignment found: ${assignment != null}")
            Result.Success(assignment)
        } catch (e: Exception) {
            Log.e(logTag, "Error checking worker assignment", e)
            setError("Failed to check worker assignment: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete all assignments in a row.
     */
    override suspend fun completelyDeleteRow(rowNumber: Int): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting complete row deletion for row $rowNumber")

            // Get all assignments in the row
            val assignments = realm.query<Assignment>("rowNumber == $0", rowNumber).find()

            if (assignments.isEmpty()) {
                Log.d(logTag, "No assignments found in row $rowNumber")
                return@withDatabaseContext Result.Success(true)
            }

            safeWrite {
                assignments.forEach { assignment ->
                    val liveAssignment = query<Assignment>("_id == $0", assignment._id).first().find()
                    liveAssignment?.let {
                        delete(it)
                        Log.d(logTag, "Deleted assignment ${assignment._id} from row $rowNumber")
                    }
                }
            }

            Log.d(logTag, "Row deletion completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in completelyDeleteRow", e)
            setError("Failed to delete row: ${e.message}")
            Result.Error(e)
        }
    }

    override fun getEntityId(entity: Assignment): String {
        return entity._id
    }

    override fun MutableRealm.findEntityById(id: String): Assignment? {
        return this.query<Assignment>("_id == $0", id).first().find()
    }
}