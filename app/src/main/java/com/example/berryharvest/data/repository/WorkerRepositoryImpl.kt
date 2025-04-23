package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.network.EnhancedNetworkManager
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmQuery
import io.realm.kotlin.query.Sort
import io.realm.kotlin.query.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Implementation of WorkerRepository that extends BaseRepositoryImpl.
 * Handles all Worker-specific data operations.
 */
class WorkerRepositoryImpl(
    application: Application,
    networkManager: EnhancedNetworkManager,
    databaseTransactionManager: DatabaseTransactionManager
) : BaseRepositoryImpl<Worker>(
    application = application,
    networkManager = networkManager,
    databaseTransactionManager = databaseTransactionManager,
    entityType = "worker",
    logTag = "WorkerRepository"
), WorkerRepository {

    /**
     * Create the base query for Worker entities.
     */
    override fun createQuery(realm: Realm): RealmQuery<Worker> {
        return realm.query<Worker>("isDeleted == false").sort("sequenceNumber", Sort.ASCENDING)
    }

    /**
     * Create a query to find a Worker by ID.
     */
    override fun createQueryById(realm: Realm, id: String): RealmQuery<Worker> {
        return realm.query<Worker>("_id == $0", id)
    }

    /**
     * Get the query to find unsynced Worker entities.
     */
    override fun getUnsyncedQuery(realm: Realm): RealmQuery<Worker> {
        return realm.query<Worker>("isSynced == false")
    }

    /**
     * Mark a Worker entity as synced.
     */
    override fun MutableRealm.markAsSynced(entity: Worker) {
        entity.isSynced = true
    }

    /**
     * Find a worker by QR code with enhanced error handling and logging.
     */
    override suspend fun getWorkerByQrCode(qrCode: String): Result<Worker?> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Finding worker by QR code: $qrCode")

            // First try exact match on the qrCode field
            val worker = realm.query<Worker>("qrCode == $0 AND isDeleted == false", qrCode).first().find()
            if (worker != null) {
                Log.d(logTag, "Found worker by exact QR match: ${worker.fullName}")
                return@withDatabaseContext Result.Success(worker)
            }

            // If not found, check if the QR code might be a worker ID directly
            Log.d(logTag, "Worker not found by QR code, trying as direct ID")
            val workerById = realm.query<Worker>("_id == $0 AND isDeleted == false", qrCode).first().find()
            if (workerById != null) {
                Log.d(logTag, "Found worker by treating QR as ID: ${workerById.fullName}")
                return@withDatabaseContext Result.Success(workerById)
            }

            // If the QR code is in format "WORKER-{id}-{timestamp}", extract the ID
            if (qrCode.startsWith("WORKER-")) {
                try {
                    val parts = qrCode.split("-")
                    if (parts.size >= 2) {
                        val workerId = parts[1]
                        Log.d(logTag, "Extracted worker ID from QR: $workerId")

                        val workerByExtractedId = realm.query<Worker>("_id == $0 AND isDeleted == false", workerId).first().find()
                        if (workerByExtractedId != null) {
                            Log.d(logTag, "Found worker by extracted ID: ${workerByExtractedId.fullName}")
                            return@withDatabaseContext Result.Success(workerByExtractedId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Error parsing worker ID from QR", e)
                }
            }

            Log.d(logTag, "Worker not found by any method for QR: $qrCode")
            Result.Success(null)
        } catch (e: Exception) {
            Log.e(logTag, "Error finding worker by QR code", e)
            setError("Error finding worker: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Add a new Worker entity.
     */
    override suspend fun add(item: Worker): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting add operation for worker: ${item.fullName}")
            var workerId = ""

            // Get next sequence number outside of write transaction
            val nextSequence = getNextSequenceNumber(realm)
            Log.d(logTag, "Next sequence number: $nextSequence")

            // Use safe write with retry
            safeWrite {
                val newWorker = copyToRealm(Worker().apply {
                    _id = item._id.ifEmpty { UUID.randomUUID().toString() }
                    sequenceNumber = nextSequence
                    fullName = item.fullName
                    phoneNumber = item.phoneNumber
                    qrCode = item.qrCode
                    createdAt = System.currentTimeMillis()
                    isSynced = networkManager.isNetworkAvailable()
                    isDeleted = false
                })
                workerId = newWorker._id
                Log.d(logTag, "Added worker: $workerId with sequence $nextSequence")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(workerId, entityType)
                )
            }

            Log.d(logTag, "Add operation completed successfully")
            Result.Success(workerId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in add", e)
            setError("Failed to add worker: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Update an existing Worker entity.
     */
    override suspend fun update(item: Worker): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting update operation for worker: ${item._id}")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val worker = query<Worker>("_id == $0", item._id).first().find()
                worker?.apply {
                    fullName = item.fullName
                    phoneNumber = item.phoneNumber
                    // Only update QR code if provided
                    if (item.qrCode.isNotEmpty()) {
                        qrCode = item.qrCode
                    }
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(logTag, "Updated worker: ${item._id}")
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
            setError("Failed to update worker: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete a Worker entity by ID.
     * Uses soft delete by marking isDeleted flag.
     */
    override suspend fun delete(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting delete operation for worker: $id")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val worker = query<Worker>("_id == $0", id).first().find()
                worker?.apply {
                    // Soft delete
                    isDeleted = true
                    isSynced = networkManager.isNetworkAvailable()
                    Log.d(logTag, "Soft deleted worker: $id")
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
            setError("Failed to delete worker: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Hard delete a Worker entity - only used for admin purposes.
     */
    override suspend fun hardDelete(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting hard delete operation for worker: $id")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val worker = query<Worker>("_id == $0", id).first().find()
                worker?.let {
                    delete(it)
                    Log.d(logTag, "Hard deleted worker: $id")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Delete(id, entityType)
                )
            }

            Log.d(logTag, "Hard delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in hard delete", e)
            setError("Failed to hard delete worker: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Get the next sequence number for a new Worker.
     */
    private fun getNextSequenceNumber(realm: Realm): Int {
        val max = realm.query<Worker>().max<Int>("sequenceNumber").find()
        return (max ?: 0) + 1
    }

    /**
     * Get Workers filtered by name.
     */
    override fun getWorkersByName(name: String): Flow<Result<List<Worker>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Worker>("fullName CONTAINS[c] $0 AND isDeleted == false", name)
                .sort("sequenceNumber", Sort.ASCENDING)
                .asFlow()
                .map { Result.Success(it.list.toList()) }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getWorkersByName", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Get worker IDs by names (for batch operations)
     */
    override suspend fun getWorkerIdsByNames(names: List<String>): Result<List<String>> = withDatabaseContext { realm ->
        try {
            if (names.isEmpty()) {
                return@withDatabaseContext Result.Success(emptyList())
            }

            val workers = realm.query<Worker>("isDeleted == false").find()
                .filter { worker ->
                    names.any { name -> worker.fullName.contains(name, ignoreCase = true) }
                }
                .map { it._id }

            Result.Success(workers)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting worker IDs by names", e)
            Result.Error(e)
        }
    }

    /**
     * Resolve sequence conflicts (e.g., after offline operations).
     */
    override suspend fun resolveSequenceConflicts(): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting sequence conflict resolution")

            safeWrite {
                val allWorkers = query<Worker>("isDeleted == false").sort("sequenceNumber", Sort.ASCENDING).find()
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
                        Log.d(logTag, "Resolved duplicate: Worker ${worker._id} assigned new sequence $newNumber")
                    }
                    seenNumbers.add(worker.sequenceNumber)
                }

                // Second pass to ensure sequential numbering
                var sequence = 1
                query<Worker>("isDeleted == false").sort("sequenceNumber", Sort.ASCENDING).find().forEach { worker ->
                    worker.sequenceNumber = sequence++
                    Log.d(logTag, "Renumbered: Worker ${worker._id} assigned sequence ${worker.sequenceNumber}")
                }
            }

            Log.d(logTag, "Sequence conflict resolution completed")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error resolving sequence conflicts", e)
            setError("Failed to resolve sequence conflicts: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Generate a QR code for a worker and update their record
     */
    override suspend fun generateAndAssignQrCode(workerId: String): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Generating QR code for worker: $workerId")

            // Generate a unique QR code
            val qrContent = "WORKER-${workerId}-${System.currentTimeMillis()}"

            // Update the worker with the new QR code
            safeWrite {
                val worker = query<Worker>("_id == $0", workerId).first().find()
                worker?.apply {
                    qrCode = qrContent
                    isSynced = networkManager.isNetworkAvailable()
                }
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(workerId, entityType)
                )
            }

            Result.Success(qrContent)
        } catch (e: Exception) {
            Log.e(logTag, "Error generating QR code", e)
            Result.Error(e)
        }
    }

    /**
     * Get multiple workers by their IDs.
     */
    override suspend fun getWorkersByIds(ids: List<String>): Flow<Result<List<Worker>>> = flow {
        emit(Result.Loading)
        try {
            if (ids.isEmpty()) {
                Log.d(logTag, "Empty ID list provided, returning empty result")
                emit(Result.Success(emptyList()))
                return@flow
            }

            Log.d(logTag, "Getting workers by IDs: ${ids.joinToString()}")
            val realm = getRealm()

            // Create a query with OR conditions for each ID
            val queryString = if (ids.size == 1) {
                "_id == $0"
            } else {
                ids.mapIndexed { index, _ -> "_id == \$$index" }.joinToString(" OR ")
            }

            realm.query<Worker>(queryString, *ids.toTypedArray())
                .asFlow()
                .map { results ->
                    Log.d(logTag, "Found ${results.list.size} workers by IDs")
                    Result.Success(results.list.toList())
                }
                .catch { e ->
                    Log.e(logTag, "Error in getWorkersByIds flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getWorkersByIds", e)
            setError("Failed to load workers by IDs: ${e.message}")
            emit(Result.Error(e))
        }
    }

    /**
     * Adds a worker with the provided details.
     */
    override suspend fun addWorkerWithDetails(fullName: String, phoneNumber: String): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting add operation for worker: $fullName")
            var workerId = ""

            // Get next sequence number outside of write transaction
            val nextSequence = getNextSequenceNumber()
            Log.d(logTag, "Next sequence number: $nextSequence")

            // Use safe write with retry
            safeWrite {
                val newWorker = copyToRealm(Worker().apply {
                    _id = UUID.randomUUID().toString()
                    sequenceNumber = nextSequence
                    this.fullName = fullName
                    this.phoneNumber = phoneNumber
                    createdAt = System.currentTimeMillis()
                    isSynced = networkManager.isNetworkAvailable()
                    isDeleted = false
                })
                workerId = newWorker._id
                Log.d(logTag, "Added worker: $workerId with sequence $nextSequence")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(workerId, entityType)
                )
            }

            Log.d(logTag, "Add operation completed successfully")
            Result.Success(workerId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in addWorkerWithDetails", e)
            setError("Failed to add worker: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Updates a worker with the provided details.
     */
    override suspend fun updateWorkerWithDetails(id: String, fullName: String, phoneNumber: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting update operation for worker: $id")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val worker = query<Worker>("_id == $0", id).first().find()
                worker?.apply {
                    this.fullName = fullName
                    this.phoneNumber = phoneNumber
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(logTag, "Updated worker: $id with name=$fullName, phone=$phoneNumber")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(id, entityType)
                )
            }

            Log.d(logTag, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in updateWorkerWithDetails", e)
            setError("Failed to update worker: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Completely deletes a worker.
     */
    override suspend fun completelyDeleteWorker(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting complete delete operation for worker: $id")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val worker = query<Worker>("_id == $0", id).first().find()
                worker?.let {
                    delete(it)
                    Log.d(logTag, "Completely deleted worker: $id")
                }
            }

            Log.d(logTag, "Complete delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in completelyDeleteWorker", e)
            setError("Failed to delete worker: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Gets the next available sequence number for a worker.
     */
    override suspend fun getNextSequenceNumber(): Int = withDatabaseContext { realm ->
        val max = realm.query<Worker>().max<Int>("sequenceNumber").find()
        (max ?: 0) + 1
    }

    override fun getEntityId(entity: Worker): String {
        return entity._id
    }

    override fun MutableRealm.findEntityById(id: String): Worker? {
        return this.query<Worker>("_id == $0", id).first().find()
    }
}