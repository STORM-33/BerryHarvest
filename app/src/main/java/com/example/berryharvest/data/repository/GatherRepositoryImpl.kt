package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.network.EnhancedNetworkManager
import com.example.berryharvest.ui.gather.GatherWithDetails
import com.example.berryharvest.ui.gather.TodayStats
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmQuery
import io.realm.kotlin.query.Sort
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Implementation of GatherRepository that extends BaseRepositoryImpl.
 * Handles all Gather-specific data operations.
 */
class GatherRepositoryImpl(
    application: Application,
    networkManager: EnhancedNetworkManager,
    databaseTransactionManager: DatabaseTransactionManager
) : BaseRepositoryImpl<Gather>(
    application = application,
    networkManager = networkManager,
    databaseTransactionManager = databaseTransactionManager,
    entityType = "gather",
    logTag = "GatherRepository"
), GatherRepository {

    /**
     * Create the base query for Gather entities.
     */
    override fun createQuery(realm: Realm): RealmQuery<Gather> {
        return realm.query<Gather>("isDeleted == false").sort("dateTime", Sort.DESCENDING)
    }

    /**
     * Create a query to find a Gather by ID.
     */
    override fun createQueryById(realm: Realm, id: String): RealmQuery<Gather> {
        return realm.query<Gather>("_id == $0", id)
    }

    /**
     * Get the query to find unsynced Gather entities.
     */
    override fun getUnsyncedQuery(realm: Realm): RealmQuery<Gather> {
        return realm.query<Gather>("isSynced == false")
    }

    /**
     * Mark a Gather entity as synced.
     */
    override fun MutableRealm.markAsSynced(entity: Gather) {
        entity.isSynced = true
    }

    /**
     * Add a new Gather entity.
     */
    override suspend fun add(item: Gather): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting add operation for gather")
            var gatherId = ""

            // Use safe write with retry
            safeWrite {
                val newGather = copyToRealm(Gather().apply {
                    _id = item._id.ifEmpty { UUID.randomUUID().toString() }
                    workerId = item.workerId
                    rowNumber = item.rowNumber
                    numOfPunnets = item.numOfPunnets
                    punnetCost = item.punnetCost
                    dateTime = item.dateTime ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    isSynced = networkManager.isNetworkAvailable()
                    isDeleted = false
                })
                gatherId = newGather._id
                Log.d(logTag, "Added gather: $gatherId")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(gatherId, entityType)
                )
            }

            Log.d(logTag, "Add operation completed successfully")
            Result.Success(gatherId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in add", e)
            setError("Failed to add gather: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Update an existing Gather entity.
     */
    override suspend fun update(item: Gather): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting update operation for gather: ${item._id}")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val gather = query<Gather>("_id == $0", item._id).first().find()
                gather?.apply {
                    workerId = item.workerId ?: this.workerId
                    rowNumber = item.rowNumber ?: this.rowNumber
                    numOfPunnets = item.numOfPunnets ?: this.numOfPunnets
                    punnetCost = item.punnetCost ?: this.punnetCost
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(logTag, "Updated gather: ${item._id}")
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
            setError("Failed to update gather: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete (soft delete) a Gather entity by ID.
     */
    override suspend fun delete(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting delete operation for gather: $id")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val gather = query<Gather>("_id == $0", id).first().find()
                gather?.apply {
                    isDeleted = true
                    isSynced = networkManager.isNetworkAvailable()
                    Log.d(logTag, "Marked gather as deleted: $id")
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
            setError("Failed to delete gather: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Get today's gathers with worker details.
     */
    override suspend fun getTodayGathers(): Result<List<GatherWithDetails>> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Getting today's gathers")

            // Calculate start of day timestamp
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDayStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(calendar.time)

            // Query gathers from today
            val gathers = realm.query<Gather>("dateTime >= $0 AND isDeleted == false", startOfDayStr)
                .sort("dateTime", Sort.DESCENDING)
                .find()

            // Create list of GatherWithDetails
            val gathersWithDetails = mutableListOf<GatherWithDetails>()

            for (gather in gathers) {
                val worker = realm.query<Worker>("_id == $0", gather.workerId).first().find()
                val workerName = if (worker != null) {
                    "${worker.fullName} [${worker.sequenceNumber}]"
                } else {
                    "Невідомий працівник"
                }

                gathersWithDetails.add(
                    GatherWithDetails(
                        gather = gather,
                        workerName = workerName,
                        dateTime = gather.dateTime
                    )
                )
            }

            Log.d(logTag, "Found ${gathersWithDetails.size} gathers for today")
            Result.Success(gathersWithDetails)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting today's gathers", e)
            setError("Failed to get today's gathers: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Get gathers for a specific worker.
     */
    override suspend fun getGathersByWorkerId(workerId: String): Result<List<Gather>> = withDatabaseContext { realm ->
        try {
            val gathers = realm.query<Gather>("workerId == $0 AND isDeleted == false", workerId)
                .sort("dateTime", Sort.DESCENDING)
                .find()
                .toList()

            Result.Success(gathers)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting gathers by worker ID", e)
            Result.Error(e)
        }
    }

    /**
     * Calculate statistics for today.
     */
    override suspend fun calculateTodayStats(): Result<TodayStats> = withDatabaseContext { realm ->
        try {
            // Calculate start of day timestamp
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDayStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(calendar.time)

            // Query gathers from today
            val gathers = realm.query<Gather>("dateTime >= $0 AND isDeleted == false", startOfDayStr)
                .find()

            // Calculate total punnets and amount
            var totalPunnets = 0
            var totalAmount = 0.0f

            for (gather in gathers) {
                val punnets = gather.numOfPunnets ?: 0
                val cost = gather.punnetCost ?: 0.0f

                totalPunnets += punnets
                totalAmount += punnets * cost
            }

            Result.Success(TodayStats(totalPunnets, totalAmount))
        } catch (e: Exception) {
            Log.e(logTag, "Error calculating today stats", e)
            Result.Error(e)
        }
    }

    /**
     * Get count of unsynced gathers.
     */
    override suspend fun getUnsyncedGathersCount(): Int = withDatabaseContext { realm ->
        try {
            val count = realm.query<Gather>("isSynced == false AND isDeleted == false").count().find()
            count.toInt()
        } catch (e: Exception) {
            Log.e(logTag, "Error counting unsynced gathers", e)
            0
        }
    }

    /**
     * Get total earnings for a worker.
     */
    override suspend fun getWorkerEarnings(workerId: String): Result<Float> = withDatabaseContext { realm ->
        try {
            val gathers = realm.query<Gather>("workerId == $0 AND isDeleted == false", workerId).find()

            var totalEarnings = 0.0f
            for (gather in gathers) {
                val punnets = gather.numOfPunnets ?: 0
                val punnetCost = gather.punnetCost ?: 0.0f
                totalEarnings += punnets * punnetCost
            }

            Result.Success(totalEarnings)
        } catch (e: Exception) {
            Log.e(logTag, "Error calculating worker earnings", e)
            Result.Error(e)
        }
    }

    /**
     * Get worker's production for today.
     */
    override suspend fun getWorkerTodayProduction(workerId: String): Result<Int> = withDatabaseContext { realm ->
        try {
            // Calculate start of day timestamp
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDayStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(calendar.time)

            val gathers = realm.query<Gather>(
                "workerId == $0 AND dateTime >= $1 AND isDeleted == false",
                workerId, startOfDayStr
            ).find()

            val todayPunnets = gathers.sumOf { it.numOfPunnets ?: 0 }
            Result.Success(todayPunnets)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting worker's today production", e)
            Result.Error(e)
        }
    }

    /**
     * Record a new gather with full details.
     */
    override suspend fun recordGather(workerId: String, rowNumber: Int, numOfPunnets: Int, punnetCost: Float): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting record gather operation for worker $workerId, row $rowNumber")
            var gatherId = ""

            // Generate current timestamp
            val currentDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            safeWrite {
                val newGather = copyToRealm(Gather().apply {
                    _id = UUID.randomUUID().toString()
                    this.workerId = workerId
                    this.rowNumber = rowNumber
                    this.numOfPunnets = numOfPunnets
                    this.punnetCost = punnetCost
                    this.dateTime = currentDate
                    this.isSynced = networkManager.isNetworkAvailable()
                    this.isDeleted = false
                })
                gatherId = newGather._id
                Log.d(logTag, "Recorded gather: $gatherId")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(gatherId, entityType)
                )
            }

            Log.d(logTag, "Record gather operation completed successfully")
            Result.Success(gatherId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in recordGather", e)
            setError("Failed to record gather: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Update an existing gather record.
     */
    override suspend fun updateGatherDetails(gatherId: String, numOfPunnets: Int): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting update gather operation for gather $gatherId")

            safeWrite {
                val gather = query<Gather>("_id == $0", gatherId).first().find()
                gather?.apply {
                    this.numOfPunnets = numOfPunnets
                    isSynced = networkManager.isNetworkAvailable()
                    Log.d(logTag, "Updated gather: $gatherId with $numOfPunnets punnets")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(gatherId, entityType)
                )
            }

            Log.d(logTag, "Update gather operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in updateGatherDetails", e)
            setError("Failed to update gather: ${e.message}")
            Result.Error(e)
        }
    }
}